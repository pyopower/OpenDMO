package main

import (
	"encoding/hex"
	"testing"
)

// Test "golden" del mapeo del DmoBridge, port 1:1 de DmoBridgeTest.kt de la app
// Android (derivado del comportamiento capturado con radio real el 27-jun-2026,
// gateway rv-dmo validado en producción):
//
//	RF->red:  control 0x41 (DATA|1 header) -> ft=2,dv=1 · 0x20 (burst A) -> ft=1,dv=0
//	          0x00 (voz B-F) -> ft=0,dv=1..5 · 0x42 (terminator) -> ft=2,dv=2 y fin de stream
//	red->RF:  jitter buffer de prebuf(4) tramas, control SIEMPRE 0x00, flush con terminator
//	          half-duplex (RF activo bloquea red) y una sola llamada de red simultánea.
//
// Si alguno de estos asserts rompe, el cambio contradice la captura real: revisar
// antes de tocar bridge.go.

type sent struct {
	seq, src, dst, slot, ft, dv int
	stream                      string
}

type fakeSink struct {
	written []struct {
		control int
		burst   []byte
	}
}

func (f *fakeSink) Connected() bool { return true }
func (f *fakeSink) SendDmr(slot, control int, data []byte) {
	f.written = append(f.written, struct {
		control int
		burst   []byte
	}{control, data})
}

func testBridge(sentOut *[]sent, dynamic bool, tg int) *DmoBridge {
	return newDmoBridge(2130035, tg, 2, dynamic,
		func(seq, src, dst, slot, ft, dv int, sid, _ []byte) {
			*sentOut = append(*sentOut, sent{seq, src, dst, slot, ft, dv, hex.EncodeToString(sid)})
		}, nil, nil)
}

var zeroBurst = make([]byte, 33) // LC indecodificable (dst=0) -> TG de reserva

// ---------- RF -> red ----------

func TestRfCallMapsControlBytesLikeCapturedGateway(t *testing.T) {
	var s []sent
	b := testBridge(&s, true, 666)
	controls := []int{0x41, 0x41, 0x41, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x42}
	for _, c := range controls {
		b.OnRfDmr(2, c, zeroBurst)
	}

	expected := [][2]int{{2, 1}, {2, 1}, {2, 1}, {1, 0},
		{0, 1}, {0, 2}, {0, 3}, {0, 4}, {0, 5}, {2, 2}}
	if len(s) != len(expected) {
		t.Fatalf("enviadas %d tramas, esperaba %d", len(s), len(expected))
	}
	for i, e := range expected {
		if s[i].ft != e[0] || s[i].dv != e[1] {
			t.Errorf("trama %d: ft/dv = %d/%d, esperaba %d/%d", i, s[i].ft, s[i].dv, e[0], e[1])
		}
		if s[i].seq != i { // seq incremental
			t.Errorf("trama %d: seq = %d", i, s[i].seq)
		}
		if s[i].stream != s[0].stream { // mismo streamId
			t.Errorf("trama %d: streamId cambió", i)
		}
		if s[i].dst != 666 || s[i].src != 2130035 || s[i].slot != 2 {
			t.Errorf("trama %d: dst/src/slot = %d/%d/%d", i, s[i].dst, s[i].src, s[i].slot)
		}
	}

	// el terminator cierra el stream: la siguiente cabecera abre stream nuevo
	b.OnRfDmr(2, 0x41, zeroBurst)
	last := s[len(s)-1]
	if last.stream == s[0].stream {
		t.Error("la nueva cabecera reutilizó el streamId anterior")
	}
	if last.seq != 0 {
		t.Errorf("la nueva llamada no reinició seq: %d", last.seq)
	}
}

func TestDynamicTgIsLatchedFromFullLcHeader(t *testing.T) {
	var s []sent
	b := testBridge(&s, true, 666)
	b.OnRfDmr(2, 0x41, headerBurst(214, 2130035))
	b.OnRfDmr(2, 0x20, zeroBurst)
	for i, e := range s {
		if e.dst != 214 { // TG marcado por la radio
			t.Errorf("trama %d: dst = %d, esperaba 214", i, e.dst)
		}
	}
}

func TestStaticTgIgnoresLc(t *testing.T) {
	var s []sent
	b := testBridge(&s, false, 666)
	b.OnRfDmr(2, 0x41, headerBurst(214, 2130035))
	if len(s) != 1 || s[0].dst != 666 {
		t.Fatalf("dst = %+v, esperaba 666", s)
	}
}

// ---------- red -> RF ----------

func TestJitterBufferHoldsPrebufAndFlushesOnTerminator(t *testing.T) {
	var s []sent
	b := testBridge(&s, true, 666)
	sink := &fakeSink{}
	b.SetModem(sink)
	b.OnNetDmrd(2, 1, 1234567, 666, 42, zeroBurst) // header
	for i := 0; i < 3; i++ {
		ft := 0
		if i == 0 {
			ft = 1
		}
		b.OnNetDmrd(ft, i, 1234567, 666, 42, zeroBurst)
	}
	if len(sink.written) != 0 { // 4 tramas: aún prebuffer
		t.Fatalf("escritas %d en prebuffer", len(sink.written))
	}
	b.OnNetDmrd(0, 4, 1234567, 666, 42, zeroBurst)
	if len(sink.written) != 1 { // 5ª: empieza a drenar
		t.Fatalf("escritas %d tras la 5ª", len(sink.written))
	}
	b.OnNetDmrd(2, 2, 1234567, 666, 42, zeroBurst) // terminator
	if len(sink.written) != 6 {                    // flush total
		t.Fatalf("escritas %d tras terminator, esperaba 6", len(sink.written))
	}
	for i, w := range sink.written {
		if w.control != 0x00 { // control siempre 0 hacia la radio
			t.Errorf("trama %d: control = %#x", i, w.control)
		}
	}
}

func TestHalfDuplexBlocksNetWhileRfActive(t *testing.T) {
	var s []sent
	b := testBridge(&s, true, 666)
	sink := &fakeSink{}
	b.SetModem(sink)
	b.OnRfDmr(2, 0x41, zeroBurst) // RF activo (1 s)
	for i := 0; i < 8; i++ {
		ft, dv := 0, i
		if i == 0 {
			ft, dv = 2, 1
		}
		b.OnNetDmrd(ft, dv, 1, 666, 7, zeroBurst)
	}
	if len(sink.written) != 0 {
		t.Fatalf("escritas %d con RF activo", len(sink.written))
	}
}

func TestSecondSimultaneousNetStreamIsIgnored(t *testing.T) {
	var s []sent
	b := testBridge(&s, true, 666)
	sink := &fakeSink{}
	b.SetModem(sink)
	for i := 0; i < 6; i++ {
		ft, dv := 0, i
		if i == 0 {
			ft, dv = 2, 1
		}
		b.OnNetDmrd(ft, dv, 1, 666, 100, zeroBurst)
	}
	drained := len(sink.written)
	b.OnNetDmrd(2, 1, 2, 666, 200, zeroBurst) // otra llamada a la vez
	if len(sink.written) != drained {         // la 1ª gana, la 2ª se descarta
		t.Fatalf("la 2ª llamada simultánea coló tramas")
	}
}

// ---------- DmrLc ----------

func TestDecodeHeaderLcRejectsGarbage(t *testing.T) {
	if decodeHeaderLc(zeroBurst) != nil {
		t.Error("aceptó un burst a ceros")
	}
	if decodeHeaderLc(make([]byte, 10)) != nil {
		t.Error("aceptó un burst corto")
	}
}

func TestDecodeHeaderLcReadsDstAndSrc(t *testing.T) {
	lc := decodeHeaderLc(headerBurst(214, 2130035))
	if lc == nil {
		t.Fatal("no decodificó la cabecera sintética")
	}
	if lc.Dst != 214 || lc.Src != 2130035 || !lc.IsGroup() {
		t.Fatalf("lc = %+v", lc)
	}
}

// ---------- DMRD ----------

func TestDmrdHeaderLayout(t *testing.T) {
	sid := []byte{0xde, 0xad, 0xbe, 0xef}
	h := dmrdHeader(7, 2130035, 214, 213003501, 2, hbpfDataSync, 1, sid)
	if string(h[0:4]) != "DMRD" || h[4] != 7 {
		t.Fatalf("cabecera: % x", h)
	}
	if u24(h, 5) != 2130035 || u24(h, 8) != 214 {
		t.Fatalf("src/dst: % x", h)
	}
	if h[15] != 0x80|0x20|0x01 { // slot2 | ft=2<<4 | dv=1
		t.Fatalf("bits: %#x", h[15])
	}
}

// ---------- helper: construye un VOICE LC HEADER sintético ----------

// headerBurst coloca los 72 bits del LC (flco=0/group) en las posiciones on-air
// que lee decodeHeaderLc (misma tabla lcBits).
func headerBurst(dst, src int) []byte {
	lc := []int{0, 0, 0,
		(dst >> 16) & 0xFF, (dst >> 8) & 0xFF, dst & 0xFF,
		(src >> 16) & 0xFF, (src >> 8) & 0xFF, src & 0xFF}
	var info [196]int
	for i := 0; i < 9; i++ {
		for bit := 0; bit < 8; bit++ {
			info[lcBits[i*8+bit]] = (lc[i] >> (7 - bit)) & 1
		}
	}
	var bits [264]int
	for i := 0; i < 98; i++ {
		bits[i] = info[i]
		bits[166+i] = info[98+i]
	}
	out := make([]byte, 33)
	for i := 0; i < 264; i++ {
		if bits[i] == 1 {
			out[i/8] |= 1 << (7 - i%8)
		}
	}
	return out
}
