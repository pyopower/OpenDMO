package main

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Cliente HBP (Homebrew Repeater Protocol) autónomo, port de HbpPeer.kt de OpenDMO.
// Login RPTL→RPTK(sha256(salt+pass))→RPTC(config)→CONNECTED + keepalive RPTPING.
// Entrega cada DMRD recibido por onDmrd y permite enviar paquetes con SendDmrd.
//
// Robustez 24/7:
//   - RECONEXIÓN automática con backoff exponencial (5s→60s) ante MSTNAK, error de
//     red o silencio del master. El DNS se re-resuelve en cada intento.
//   - WATCHDOG de keepalive: si el master deja de contestar (ni MSTPONG ni DMRD)
//     durante peerTimeout se da la sesión por muerta y se reconecta.
//   - Timeout de login: si el handshake no completa en loginTimeout se reintenta.
//
// Tras CONNECTED, si la config trae options se envía RPTO (suscripción de TGs estilo
// BrandMeister/TGIF, p.ej. "TS2_1=214;TS2_2=91").

const (
	pingEvery    = 5 * time.Second
	peerTimeout  = 30 * time.Second // ~6 pings sin respuesta → sesión muerta
	loginTimeout = 10 * time.Second
)

type HbpPeer struct {
	cfg     *Config
	onDmrd  func(frameType, dtypeVseq, src, dst, streamID int, burst []byte)
	onState func(string)
	logf    func(string, ...any)

	running   atomic.Bool
	connected atomic.Bool
	mu        sync.Mutex // protege conn
	conn      *net.UDPConn
	done      chan struct{}
}

func newHbpPeer(cfg *Config, onDmrd func(int, int, int, int, int, []byte),
	onState func(string), logf func(string, ...any)) *HbpPeer {
	if onState == nil {
		onState = func(string) {}
	}
	return &HbpPeer{cfg: cfg, onDmrd: onDmrd, onState: onState, logf: logf}
}

func (p *HbpPeer) Connected() bool { return p.connected.Load() }

func (p *HbpPeer) Start() {
	if !p.running.CompareAndSwap(false, true) {
		return
	}
	p.done = make(chan struct{})
	go p.run()
}

func (p *HbpPeer) Stop() {
	if !p.running.CompareAndSwap(true, false) {
		return
	}
	if p.connected.Load() {
		_ = p.send(append([]byte("RPTCL"), b4(p.cfg.PeerID())...))
	}
	p.connected.Store(false)
	p.closeConn()
	<-p.done
	p.onState("parado")
}

// SendDmrd envía un paquete DMRD ya formado (20B cab + 33B burst).
func (p *HbpPeer) SendDmrd(pkt []byte) bool {
	if err := p.send(pkt); err != nil {
		p.logf("tx red: %v", err)
		return false
	}
	return true
}

func (p *HbpPeer) send(data []byte) error {
	p.mu.Lock()
	c := p.conn
	p.mu.Unlock()
	if c == nil {
		return fmt.Errorf("sin socket")
	}
	_, err := c.Write(data)
	return err
}

func (p *HbpPeer) closeConn() {
	p.mu.Lock()
	if p.conn != nil {
		p.conn.Close()
		p.conn = nil
	}
	p.mu.Unlock()
}

// ---------- bucle exterior: sesión + reconexión con backoff ----------

func (p *HbpPeer) run() {
	defer close(p.done)
	attempt := 0
	for p.running.Load() {
		if err := p.session(func() { attempt = 0 }); err != nil { // resetea backoff al CONNECTED
			p.logf("peer: %v", err)
			p.onState("error: " + err.Error())
		}
		p.connected.Store(false)
		p.closeConn()
		if !p.running.Load() {
			break
		}
		attempt++
		shift := attempt - 1
		if shift > 4 {
			shift = 4
		}
		delay := 5 << shift // 5,10,20,40,60,60…
		if delay > 60 {
			delay = 60
		}
		p.onState(fmt.Sprintf("reintento en %ds", delay))
		for slept := 0; p.running.Load() && slept < delay*1000; slept += 250 {
			time.Sleep(250 * time.Millisecond)
		}
	}
}

// session ejecuta una sesión completa contra el master. Vuelve cuando la sesión muere.
func (p *HbpPeer) session(onConnected func()) error {
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(p.cfg.Host, itoa(p.cfg.Port))) // re-resuelve DNS
	if err != nil {
		return err
	}
	c, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return err
	}
	p.mu.Lock()
	p.conn = c
	p.mu.Unlock()

	peerID := p.cfg.PeerID()
	state := "RPTL"
	t0 := time.Now()
	if err := p.send(append([]byte("RPTL"), b4(peerID)...)); err != nil {
		return err
	}
	p.onState("login…")
	var tPing time.Time
	lastHeard := time.Now() // último paquete del master (pong o DMRD)
	buf := make([]byte, 2048)

	for p.running.Load() {
		now := time.Now()
		if p.connected.Load() && now.Sub(tPing) > pingEvery {
			_ = p.send(append([]byte("RPTPING"), b4(peerID)...))
			tPing = now
		}
		if !p.connected.Load() && now.Sub(t0) > loginTimeout {
			p.logf("login timeout in %s", state)
			return nil
		}
		if p.connected.Load() && now.Sub(lastHeard) > peerTimeout {
			p.logf("master silent %ds → reconnect", int(peerTimeout.Seconds()))
			return nil
		}
		_ = c.SetReadDeadline(time.Now().Add(time.Second))
		n, err := c.Read(buf)
		if err != nil {
			if !p.running.Load() {
				return nil
			}
			continue // timeout o ICMP transitorio: el watchdog decide
		}
		data := buf[:n]
		lastHeard = time.Now()

		switch {
		case bytes.HasPrefix(data, []byte("RPTACK")):
			switch state {
			case "RPTL":
				if n < 10 {
					continue
				}
				salt := data[6:10]
				h := sha256.Sum256(append(append([]byte{}, salt...), []byte(p.cfg.Passphrase)...))
				pkt := append(append([]byte("RPTK"), b4(peerID)...), h[:]...)
				_ = p.send(pkt)
				state = "RPTK"
				p.logf("RPTACK salt=%x → RPTK", salt)
			case "RPTK":
				_ = p.send(append([]byte("RPTC"), p.configPacket()...))
				state = "RPTC"
				p.logf("auth OK → RPTC")
			case "RPTC":
				state = "CONNECTED"
				p.connected.Store(true)
				onConnected()
				p.onState("conectado a " + p.cfg.Host)
				p.logf("CONNECTED to master")
				if strings.TrimSpace(p.cfg.Options) != "" {
					_ = p.send(append(append([]byte("RPTO"), b4(peerID)...), []byte(p.cfg.Options)...))
					p.logf("RPTO: %s", p.cfg.Options)
				}
				// RPTACK en CONNECTED = ack del RPTO u otro; solo liveness
			}
		case bytes.HasPrefix(data, []byte("MSTNAK")):
			p.logf("MSTNAK in %s", state)
			p.onState("rechazado por el master")
			return nil
		case bytes.HasPrefix(data, []byte("MSTPONG")):
			// solo liveness
		case bytes.HasPrefix(data, []byte("DMRD")) && n >= 53:
			src := u24(data, 5)
			dst := u24(data, 8)
			bits := int(data[15])
			ft := (bits >> 4) & 0x3
			dv := bits & 0xF
			streamID := int(data[16])<<24 | int(data[17])<<16 | int(data[18])<<8 | int(data[19])
			burst := make([]byte, 33)
			copy(burst, data[20:53])
			p.onDmrd(ft, dv, src, dst, streamID, burst)
		}
	}
	return nil
}

func (p *HbpPeer) configPacket() []byte {
	fHz := 439.025e6
	if v, err := parseMHz(p.cfg.FreqMHz); err == nil {
		fHz = v
	}
	fStr := fmt.Sprintf("%09d", int64(fHz+0.5))
	if len(fStr) > 9 {
		fStr = fStr[:9]
	}
	call := p.cfg.Callsign
	if strings.TrimSpace(call) == "" {
		call = "NOCALL"
	}
	var b bytes.Buffer
	b.Write(b4(p.cfg.PeerID()))
	b.Write(fld(call, 8))
	b.Write(fld(fStr, 9))                                 // RX
	b.Write(fld(fStr, 9))                                 // TX
	b.Write(fld("01", 2))                                 // power
	b.Write(fld(fmt.Sprintf("%02d", p.cfg.ColorCode), 2)) // color code
	b.Write(fld("0000.000", 8))                           // lat
	b.Write(fld("00000.00", 9))                           // lon
	b.Write(fld("000", 3))                                // height
	b.Write(fld("OpenWrt", 20))                           // location
	b.Write(fld("OpenDMO", 19))                           // description
	b.Write(fld("4", 1))                                  // slots
	b.Write(fld("https://adan.ovh", 124))                 // url
	b.Write(fld("OpenDMO-Go", 40))                        // software id
	b.Write(fld(buildDate, 40))                           // package id
	return b.Bytes()
}

func b4(n int) []byte {
	return []byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n)}
}

func fld(s string, n int) []byte {
	out := make([]byte, n)
	for i := range out {
		if i < len(s) {
			out[i] = s[i]
		} else {
			out[i] = ' '
		}
	}
	return out
}

func u24(d []byte, i int) int {
	return int(d[i])<<16 | int(d[i+1])<<8 | int(d[i+2])
}
