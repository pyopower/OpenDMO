package main

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

// Driver del módem MMDVM que expone el OpenGD77 en modo hotspot, por USB (CDC-ACM).
//
// Port de MmdvmModem.kt de OpenDMO. El OpenGD77 imita un módem MMDVM_HS; trama en
// el cable:
//
//	0xE0 | LEN | TYPE | payload...
//
// donde LEN = longitud total de la trama (incluye los 3 bytes de cabecera). Para DMR
// el payload de DMR_DATA1/2 es: control(1 byte) + 33 bytes de burst DMR.
//
// IMPORTANTE: los 33 bytes son el burst DMR YA FORMADO (264 bits, igual que el que
// viaja en el DMRD del HBP). En hotspot NO se reencoda BPTC ni se transcodifica AMBE:
// el burst de RF entra/sale tal cual hacia/desde el peer. La radio hace el vocoder.
//
// En Linux el OpenGD77 aparece como /dev/ttyACM* (kmod-usb-acm en OpenWrt). La capa
// serie es termios raw + DTR activo (el firmware CDC-ACM necesita DTR para abrir el
// flujo, igual que en Android/WebSerial).

// Protocolo MMDVM (subconjunto DMR).
const (
	frameStart = 0xE0
	getVersion = 0x00
	getStatus  = 0x01
	setConfig  = 0x02
	setMode    = 0x03
	setFreq    = 0x04
	dmrData1   = 0x18
	dmrLost1   = 0x19
	dmrData2   = 0x1A
	dmrLost2   = 0x1B

	modeIdle = 0x00
	modeDmr  = 0x02

	// Constantes DMR del byte de control (dmrDefines.c del firmware OpenGD77).
	dmrSyncData  = 0x40
	dmrSyncAudio = 0x20

	dmrFrameBytes = 33
)

// MmdvmModem gestiona el puerto serie del OpenGD77. onDmr recibe (slot, control,
// data33) por cada burst de RF; control == 0xFF marca fin de TX (DMR_LOST).
type MmdvmModem struct {
	dev        string
	colorCode  int
	freqHz     int
	txPowerPct int // potencia TX (rfLevel MMDVM 0-100); 100 = la radio usa su VFO/canal
	onDmr      func(slot, control int, data []byte)
	logf       func(string, ...any)

	f         *os.File
	deadlines bool // el fd admite SetReadDeadline (poller de Go); si no, VTIME hace de timeout
	version   string

	connected atomic.Bool
	stop      atomic.Bool
	txq       chan []byte
	writeMu   sync.Mutex // serializa las escrituras (txLoop vs poll de estado del rxLoop)

	// Espacio libre del buffer DMR TX del firmware (GET_STATUS). -1 = aún desconocido:
	// en ese caso NO se aplica pacing, por si algún firmware no contesta al GET_STATUS.
	dmrSpace     atomic.Int32
	lastStatusMs atomic.Int64
	wg           sync.WaitGroup
}

func newMmdvmModem(dev string, colorCode, freqHz, txPowerPct int,
	onDmr func(int, int, []byte), logf func(string, ...any)) *MmdvmModem {
	m := &MmdvmModem{
		dev: dev, colorCode: colorCode, freqHz: freqHz, txPowerPct: txPowerPct,
		onDmr: onDmr, logf: logf,
		txq: make(chan []byte, 200),
	}
	m.dmrSpace.Store(-1)
	return m
}

func (m *MmdvmModem) Connected() bool { return m.connected.Load() }
func (m *MmdvmModem) Version() string { return m.version }

// ---------- ciclo de vida ----------

func (m *MmdvmModem) Open() error {
	f, err := os.OpenFile(m.dev, os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return err
	}
	m.f = f
	// Si el fd entra en el poller de Go, los timeouts van por SetReadDeadline y VMIN=1.
	// Si no (ErrNoDeadline), VMIN=0/VTIME=1 da lecturas con timeout de 100 ms.
	m.deadlines = f.SetReadDeadline(time.Time{}) == nil
	if err := m.rawMode(); err != nil {
		f.Close()
		return fmt.Errorf("termios: %w", err)
	}
	if err := m.setDtr(); err != nil {
		m.logf("aviso: DTR: %v", err) // algunos cables/gadgets no lo soportan; se intenta igual
	}
	m.stop.Store(false)
	m.handshake()
	m.connected.Store(true)
	m.wg.Add(2)
	go m.rxLoop()
	go m.txLoop()
	m.logf("módem MMDVM abierto (OpenGD77) en %s", m.dev)
	return nil
}

func (m *MmdvmModem) Close() {
	m.stop.Store(true)
	m.connected.Store(false)
	_ = m.frame(setMode, []byte{modeIdle})
	if m.f != nil {
		m.f.Close()
	}
	m.wg.Wait()
}

func (m *MmdvmModem) rawMode() error {
	fd := m.f.Fd()
	var t syscall.Termios
	if err := ioctlTermios(fd, syscall.TCGETS, &t); err != nil {
		return err
	}
	t.Iflag = 0
	t.Oflag = 0
	t.Lflag = 0
	t.Cflag = syscall.CS8 | syscall.CREAD | syscall.CLOCAL | syscall.B115200
	if m.deadlines {
		t.Cc[syscall.VMIN], t.Cc[syscall.VTIME] = 1, 0
	} else {
		t.Cc[syscall.VMIN], t.Cc[syscall.VTIME] = 0, 1 // lecturas bloqueantes con timeout 100 ms
	}
	return ioctlTermios(fd, syscall.TCSETS, &t)
}

func (m *MmdvmModem) setDtr() error {
	bits := uint32(syscall.TIOCM_DTR)
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, m.f.Fd(), syscall.TIOCMBIS, uintptr(unsafe.Pointer(&bits)))
	if e != 0 {
		return e
	}
	return nil
}

func ioctlTermios(fd uintptr, req uint, t *syscall.Termios) error {
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, uintptr(req), uintptr(unsafe.Pointer(t)))
	if e != 0 {
		return e
	}
	return nil
}

// read lee con timeout. Devuelve (0, nil) si venció el plazo sin datos.
func (m *MmdvmModem) read(buf []byte, timeout time.Duration) (int, error) {
	if m.deadlines {
		_ = m.f.SetReadDeadline(time.Now().Add(timeout))
		n, err := m.f.Read(buf)
		if err != nil && errors.Is(err, os.ErrDeadlineExceeded) {
			return n, nil
		}
		return n, err
	}
	// Fallback VTIME: read() devuelve 0 tras ~100 ms sin datos y os.File lo convierte
	// en io.EOF; aquí un EOF de tty es "timeout", no fin de archivo.
	deadline := time.Now().Add(timeout)
	for {
		n, err := m.f.Read(buf)
		if n > 0 || (err != nil && err.Error() != "EOF") {
			return n, err
		}
		if time.Now().After(deadline) {
			return 0, nil
		}
	}
}

// ---------- protocolo ----------

func buildFrame(typ int, payload []byte) []byte {
	out := make([]byte, len(payload)+3)
	out[0] = frameStart
	out[1] = byte(len(payload) + 3)
	out[2] = byte(typ)
	copy(out[3:], payload)
	return out
}

func (m *MmdvmModem) frame(typ int, payload []byte) error {
	m.writeMu.Lock()
	defer m.writeMu.Unlock()
	_, err := m.f.Write(buildFrame(typ, payload))
	return err
}

// freqLE: frecuencia en Hz -> 4 bytes little-endian (como freqLE de dmo.js).
func freqLE(hz int) []byte {
	return []byte{byte(hz), byte(hz >> 8), byte(hz >> 16), byte(hz >> 24)}
}

func (m *MmdvmModem) handshake() {
	// Secuencia CALCADA del cliente web validado (static/dmo.js): la radio entra en DMR
	// y queda en la frecuencia DMO seleccionada. GET_VERSION -> SET_FREQ -> SET_CONFIG -> SET_MODE.
	_ = m.frame(getVersion, nil)
	time.Sleep(250 * time.Millisecond)
	// SET_FREQ (0x04): [0x00, rxFreq(4 LE), txFreq(4 LE), rfLevel(1), pocsagFreq(4 LE pad)]
	// En DMO rx=tx. El padding 0x40,0x0e,0xcf,0x19 (=433 MHz POCSAG) va literal como en dmo.js.
	// rfLevel = potencia TX (0-255). El OpenGD77 lo interpreta como % de su escala (255=100% →
	// usa el VFO/canal de la radio); otros valores los redondea al escalón más cercano (50mW…5W).
	pct := m.txPowerPct
	if pct < 0 {
		pct = 0
	} else if pct > 100 {
		pct = 100
	}
	rfLevel := byte((pct*255 + 50) / 100)
	f := freqLE(m.freqHz)
	pl := append([]byte{0x00}, f...)
	pl = append(pl, f...)
	pl = append(pl, rfLevel, 0x40, 0x0e, 0xcf, 0x19)
	_ = m.frame(setFreq, pl)
	time.Sleep(120 * time.Millisecond)
	// SET_CONFIG (0x02): bloque exacto de dmo.js (cc en el índice 6).
	cfg := []byte{
		0x80, 0x02, 0x0a, 0x00, 0x80, 0x80, byte(m.colorCode), 0x00,
		0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
		0x80, 0x80, 0x04, 0x80, 0x80, 0x05, 0x05, 0x00, 0x00,
	}
	_ = m.frame(setConfig, cfg)
	time.Sleep(120 * time.Millisecond)
	_ = m.frame(setMode, []byte{modeDmr})
	time.Sleep(80 * time.Millisecond)
	// leemos lo que haya (version/ack)
	buf := make([]byte, 256)
	if n, err := m.read(buf, 300*time.Millisecond); err == nil && n > 0 {
		if v := extractVersion(buf[:n]); v != "" {
			m.version = v
			m.logf("radio: %s", v)
		}
	}
}

func extractVersion(buf []byte) string {
	for i := 0; i < len(buf)-2; {
		if buf[i] != frameStart {
			i++
			continue
		}
		ln := int(buf[i+1])
		if buf[i+2] == getVersion && ln >= 4 && i+ln <= len(buf) {
			return strings.TrimSpace(string(buf[i+4 : i+ln]))
		}
		if ln < 1 {
			ln = 1
		}
		i += ln
	}
	return ""
}

// ---------- envío (red -> RF) ----------

// SendDmr encola una trama DMR hacia la radio. slot: 1 o 2; data = 33 bytes de burst.
func (m *MmdvmModem) SendDmr(slot, control int, dmrData []byte) {
	typ := dmrData1
	if slot == 2 {
		typ = dmrData2
	}
	payload := make([]byte, 1+dmrFrameBytes)
	payload[0] = byte(control)
	copy(payload[1:], dmrData) // si viene corto queda relleno con 0, como copyOf
	select {
	case m.txq <- buildFrame(typ, payload):
	default:
		m.logf("cola TX del módem llena, descarto trama")
	}
}

func (m *MmdvmModem) spaceKnown() bool {
	return m.dmrSpace.Load() >= 0 && nowMs()-m.lastStatusMs.Load() < 3000
}

func (m *MmdvmModem) txLoop() {
	defer m.wg.Done()
	for !m.stop.Load() {
		var f []byte
		select {
		case f = <-m.txq:
		case <-time.After(100 * time.Millisecond):
			continue
		}
		isDmr := f[2] == dmrData1 || f[2] == dmrData2
		// Pacing por espacio real del firmware (como MMDVMHost): si el GET_STATUS es
		// reciente y el buffer DMR va justo, espera a que la radio drene antes de
		// escribir; evita desbordar el módem si el master manda a ráfagas. Si el
		// estado es viejo/desconocido no se bloquea.
		if isDmr {
			for waited := 0; !m.stop.Load() && m.spaceKnown() && m.dmrSpace.Load() < 2 && waited < 600; waited += 20 {
				time.Sleep(20 * time.Millisecond)
			}
		}
		m.writeMu.Lock()
		_, err := m.f.Write(f)
		m.writeMu.Unlock()
		if err != nil {
			m.logf("error escribiendo al módem: %v", err)
			m.connected.Store(false)
			return
		}
		if isDmr && m.dmrSpace.Load() > 0 {
			m.dmrSpace.Add(-1)
		}
	}
}

// ---------- recepción (RF -> red) ----------

func (m *MmdvmModem) rxLoop() {
	defer m.wg.Done()
	var buf []byte
	tmp := make([]byte, 256)
	var lastPoll time.Time
	for !m.stop.Load() {
		n, err := m.read(tmp, 50*time.Millisecond)
		if err != nil {
			if !m.stop.Load() {
				m.logf("error leyendo del módem: %v", err)
			}
			m.connected.Store(false)
			return
		}
		if n > 0 {
			buf = append(buf, tmp[:n]...)
			buf = m.consume(buf)
		}
		// poll de estado: cada 1 s en reposo, cada 250 ms si hay cola TX (el pacing
		// del txLoop necesita ver el espacio del buffer fresco, como hace MMDVMHost)
		pollEvery := time.Second
		if len(m.txq) > 0 {
			pollEvery = 250 * time.Millisecond
		}
		if time.Since(lastPoll) > pollEvery {
			lastPoll = time.Now()
			_ = m.frame(getStatus, nil)
		}
	}
}

func (m *MmdvmModem) consume(buf []byte) []byte {
	for len(buf) >= 3 {
		if buf[0] != frameStart {
			buf = buf[1:]
			continue
		}
		ln := int(buf[1])
		if ln < 3 {
			buf = buf[1:]
			continue
		}
		if len(buf) < ln {
			return buf // trama incompleta, espera más bytes
		}
		m.dispatch(int(buf[2]), buf[3:ln])
		buf = buf[ln:]
	}
	return buf
}

func (m *MmdvmModem) dispatch(typ int, payload []byte) {
	switch typ {
	case dmrData1, dmrData2:
		if len(payload) >= 1+dmrFrameBytes {
			slot := 1
			if typ == dmrData2 {
				slot = 2
			}
			data := make([]byte, dmrFrameBytes)
			copy(data, payload[1:1+dmrFrameBytes])
			m.onDmr(slot, int(payload[0]), data)
		}
	case dmrLost1, dmrLost2:
		slot := 1
		if typ == dmrLost2 {
			slot = 2
		}
		m.onDmr(slot, 0xFF, nil) // 0xFF = marcador de fin de TX RF
	case getStatus:
		// Layout MMDVM v1 (MMDVMHost Modem.cpp): [0]=modes [1]=state [2]=flags
		// [3]=espacio D-Star [4]=espacio DMR TS1 [5]=espacio DMR TS2 …
		// En DMO el OpenGD77 transmite por el "TS2" del cable (DMR_DATA2).
		if len(payload) >= 6 {
			m.dmrSpace.Store(int32(payload[5]))
			m.lastStatusMs.Store(nowMs())
		}
	}
}

// findModemDevice localiza el OpenGD77: "auto" prueba /dev/ttyACM0..9, otro valor
// se usa tal cual. Devuelve "" si no hay nada enchufado.
func findModemDevice(dev string) string {
	if dev != "" && dev != "auto" {
		if _, err := os.Stat(dev); err == nil {
			return dev
		}
		return ""
	}
	for i := 0; i < 10; i++ {
		d := fmt.Sprintf("/dev/ttyACM%d", i)
		if _, err := os.Stat(d); err == nil {
			return d
		}
	}
	return ""
}
