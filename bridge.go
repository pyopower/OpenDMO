package main

import (
	"sync"
	"time"
)

// Puente bidireccional MMDVM(OpenGD77) <-> peer HBP, modo DMO.
//
// Port FIEL de DmoBridge.kt de OpenDMO (a su vez port del gateway validado en
// producción `rv-dmo/rv_dmo_gw.py`, cuyo mapeo se confirmó por captura con radio
// real el 27-jun-2026).
//
//	RF -> red (OnRfDmr):  el byte de control del cable lleva sync bits:
//	    DMR_SYNC_DATA(0x40)|dtype para datos (1=header, 2=terminator),
//	    DMR_SYNC_AUDIO(0x20) para el burst A de voz, 0x00 para voz B-F.
//	red -> RF (OnNetDmrd): al radio el control es SIEMPRE 0x00 (el tipo va embebido
//	    en el slot-type del burst de 33 B). Jitter buffer de prebuf tramas (~240 ms)
//	    + half-duplex (mientras hay RF no se mete red).

// DmrSink es el destino de los bursts red->RF (el módem, o un fake en tests).
type DmrSink interface {
	Connected() bool
	SendDmr(slot, control int, data []byte)
}

type sendDmrdFn func(seqNo, rfSrc, dst, slot, frameType, dtypeVseq int, streamID, burst []byte)

const prebuf = 4 // 4×60 ms ≈ 240 ms de cojín (igual que la web y la app)

type DmoBridge struct {
	mu sync.Mutex

	radioID     int
	talkgroup   int
	networkSlot int
	dynamicTg   bool // si true, el dst sale del Full LC que marca la radio (BM/TGIF/ADN)
	sendDmrd    sendDmrdFn
	logf        func(format string, a ...any)
	nameOf      func(id int) string
	modem       DmrSink

	// RF -> red
	txStream []byte
	txSeq    int
	vpos     int
	txDst    int   // TG destino de la llamada RF en curso (latcheado en la cabecera)
	rfUntil  int64 // ms monotónicos: RF activo hasta aquí (half-duplex)
	rfFrames int

	// red -> RF (con jitter buffer)
	rxStream  int
	rxActive  bool
	rxLast    int64
	txbuf     [][]byte
	netFrames int
}

func newDmoBridge(radioID, talkgroup, networkSlot int, dynamicTg bool,
	sendDmrd sendDmrdFn, logf func(string, ...any), nameOf func(int) string) *DmoBridge {
	if nameOf == nil {
		nameOf = func(id int) string { return itoa(id) }
	}
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &DmoBridge{
		radioID: radioID, talkgroup: talkgroup, networkSlot: networkSlot,
		dynamicTg: dynamicTg, sendDmrd: sendDmrd, logf: logf, nameOf: nameOf,
		txDst: talkgroup,
	}
}

func (b *DmoBridge) SetModem(m DmrSink) { b.mu.Lock(); b.modem = m; b.mu.Unlock() }

func nowMs() int64 { return time.Now().UnixNano() / 1e6 }

// OnRfDmr procesa un burst recibido de la radio (lo llama el módem).
// control == 0xFF marca FIN de TX de RF (DMR_LOST).
func (b *DmoBridge) OnRfDmr(slot, control int, data []byte) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if control == 0xFF { // DMR_LOST: fin de TX de RF
		b.txStream = nil
		return
	}
	if len(data) < 33 {
		return
	}
	now := nowMs()
	if b.txStream != nil && now > b.rfUntil { // hueco >1s: la llamada anterior acabó sin term
		b.txStream = nil
	}
	b.rfUntil = now + 1000 // marca RF activo (half-duplex)

	dataSync := control&dmrSyncData != 0
	audioSync := control&dmrSyncAudio != 0
	var ft, dv int
	switch {
	case dataSync:
		dt := control & 0x0F
		if dt == 1 { // VOICE LC HEADER (la radio lo repite ~3x)
			if b.txStream == nil { // SOLO inicia stream si no hay llamada activa
				b.txStream = newStreamID()
				b.txSeq, b.vpos, b.rfFrames = 0, 0, 0
				// TG dinámico: el TG real lo marca la radio en el Full LC de esta cabecera.
				b.txDst = b.talkgroup
				src := b.radioID
				if b.dynamicTg {
					if lc := decodeHeaderLc(data); lc != nil {
						b.txDst = lc.Dst
						src = lc.Src
					}
				}
				b.logf("RF %s → TG%d", b.nameOf(src), b.txDst)
			}
		}
		ft, dv = hbpfDataSync, dt
	case audioSync: // burst A
		b.vpos = 0
		ft, dv = hbpfVoiceSync, 0
	default: // voz B-F
		b.vpos = (b.vpos + 1) % 6
		ft, dv = hbpfVoice, b.vpos
	}
	if b.txStream == nil {
		b.txStream = newStreamID()
		b.txSeq = 0
	}
	b.sendDmrd(b.txSeq&0xFF, b.radioID, b.txDst, b.networkSlot, ft, dv, b.txStream, data)
	b.txSeq++
	b.rfFrames++
	if dataSync && control&0x0F == 2 { // terminator: fin de llamada
		b.txStream = nil
	}
}

// OnNetDmrd procesa un DMRD del master (lo llama el peer HBP tras el filtro de TG).
func (b *DmoBridge) OnNetDmrd(frameType, dtypeVseq, src, dst, streamID int, burst []byte) {
	b.mu.Lock()
	defer b.mu.Unlock()

	m := b.modem
	if m == nil || !m.Connected() {
		return
	}
	now := nowMs()
	if now < b.rfUntil { // half-duplex: en RF no metemos red
		return
	}

	if b.rxActive && now-b.rxLast > 2000 { // se perdió el terminator
		b.flush(m)
		b.rxActive = false
	}
	if !b.rxActive {
		if frameType == hbpfDataSync && dtypeVseq == 2 {
			return // cola de una llamada ya terminada
		}
		b.rxStream = streamID
		b.rxActive = true
		b.txbuf = b.txbuf[:0]
		b.logf("NET→RF %s → TG%d", b.nameOf(src), dst)
	} else if streamID != b.rxStream {
		return // otra llamada simultánea: la 1ª gana
	}
	b.rxLast = now
	b.netFrames++

	// jitter buffer: encola y libera 1 por cada entrante tras prebuffer (ritmo ≈ del master)
	b.txbuf = append(b.txbuf, burst)
	if len(b.txbuf) > prebuf {
		m.SendDmr(b.networkSlot, 0x00, b.txbuf[0])
		b.txbuf = b.txbuf[1:]
	}

	if frameType == hbpfDataSync && dtypeVseq == 2 { // terminator: vacía a la radio
		b.flush(m)
		b.rxActive = false
	}
}

func (b *DmoBridge) flush(m DmrSink) {
	for _, burst := range b.txbuf {
		m.SendDmr(b.networkSlot, 0x00, burst)
	}
	b.txbuf = b.txbuf[:0]
}

func (b *DmoBridge) Stats() (rf, net int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.rfFrames, b.netFrames
}
