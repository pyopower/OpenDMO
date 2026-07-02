package main

import (
	"flag"
	"fmt"
	"log"
	"math"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// opendmo — gateway DMO OpenGD77 ↔ master HBP (hblink/BM/TGIF/ADN) para OpenWrt/Linux.
//
// Port en Go de la app Android OpenDMO (github.com/pyopower/OpenDMO), pensado para
// routers tipo GL.iNet Mango: la radio OpenGD77 se enchufa al USB del router
// (/dev/ttyACM0, kmod-usb-acm) y el daemon hace de puente DMO ↔ red. La radio pone
// el vocoder AMBE; aquí los bursts de 33 B pasan crudos.

const appVersion = "0.1.0"
const buildDate = "20260702"

// Config del gateway. El ID de login HBP NO es el DMR ID a secas: se forma con el
// DMR ID + un sufijo ESSID (00-99), convención homebrew/BrandMeister, porque casi
// todos los usuarios ya tienen el DMR ID "pelado" registrado en otro dispositivo.
//
//	ej. DMR ID 2130035 + sufijo 01 -> peerId 213003501
type Config struct {
	Host       string
	Port       int
	Passphrase string
	RadioID    int // DMR ID real de la radio (rf_src)
	Suffix     int // ESSID (00-99); junto al DMR ID forma el peerId de login
	Callsign   string
	Talkgroup  int
	Slot       int
	ColorCode  int
	FreqMHz    string
	DynamicTg  bool // TG dinámico (estilo BM/TGIF/ADN): usa el TG que marca la radio
	TxPowerPct int  // potencia TX hacia el OpenGD77 (rfLevel MMDVM 0-100); 100 = VFO/canal de la radio
	Options    string
	Device     string
}

// PeerID: ID de login HBP = DMR ID + sufijo de 2 dígitos.
func (c *Config) PeerID() int {
	s := c.Suffix
	if s < 0 {
		s = 0
	} else if s > 99 {
		s = 99
	}
	return c.RadioID*100 + s
}

func itoa(n int) string { return strconv.Itoa(n) }

func parseMHz(s string) (hz float64, err error) {
	v, err := strconv.ParseFloat(strings.TrimSpace(s), 64)
	if err != nil {
		return 0, err
	}
	return v * 1e6, nil
}

func main() {
	cfg := &Config{}
	flag.StringVar(&cfg.Host, "host", "", "master HBP (host o IP)")
	flag.IntVar(&cfg.Port, "port", 62031, "puerto UDP del master")
	flag.StringVar(&cfg.Passphrase, "pass", "", "passphrase HBP (o env OPENDMO_PASS)")
	flag.IntVar(&cfg.RadioID, "id", 0, "DMR ID de la radio")
	flag.IntVar(&cfg.Suffix, "essid", 1, "sufijo ESSID 00-99 del peer ID")
	flag.StringVar(&cfg.Callsign, "call", "", "indicativo")
	flag.IntVar(&cfg.Talkgroup, "tg", 0, "talkgroup estático (con -dynamic=false)")
	flag.IntVar(&cfg.Slot, "slot", 2, "timeslot de red (1/2)")
	flag.IntVar(&cfg.ColorCode, "cc", 1, "color code DMO")
	flag.StringVar(&cfg.FreqMHz, "freq", "439.025", "frecuencia DMO en MHz")
	flag.BoolVar(&cfg.DynamicTg, "dynamic", true, "TG dinámico: usa el TG que marca la radio")
	flag.IntVar(&cfg.TxPowerPct, "power", 100, "potencia TX 0-100 (100 = canal/VFO de la radio)")
	flag.StringVar(&cfg.Options, "options", "", "RPTO tras login, p.ej. TS2_1=214;TS2_2=91")
	flag.StringVar(&cfg.Device, "device", "auto", "puerto serie del OpenGD77 (auto = /dev/ttyACM*)")
	showVer := flag.Bool("version", false, "muestra la versión y sale")
	flag.Parse()

	if *showVer {
		fmt.Println("opendmo " + appVersion)
		return
	}
	if cfg.Passphrase == "" {
		cfg.Passphrase = os.Getenv("OPENDMO_PASS")
	}
	if cfg.Host == "" || cfg.RadioID == 0 {
		fmt.Fprintln(os.Stderr, "faltan -host y/o -id (ver -help)")
		os.Exit(2)
	}

	lg := log.New(os.Stdout, "", log.LstdFlags)
	logf := func(format string, a ...any) { lg.Printf(format, a...) }
	logf("OpenDMO %s (gateway DMO OpenGD77 ↔ HBP) — peer %d → %s:%d",
		appVersion, cfg.PeerID(), cfg.Host, cfg.Port)

	var peer *HbpPeer
	bridge := newDmoBridge(cfg.RadioID, cfg.Talkgroup, cfg.Slot, cfg.DynamicTg,
		func(seq, src, dst, slot, ft, dv int, sid, burst []byte) {
			pkt := append(dmrdHeader(seq, src, dst, cfg.PeerID(), slot, ft, dv, sid), burst...)
			peer.SendDmrd(pkt)
		}, logf, nil)

	peer = newHbpPeer(cfg,
		func(ft, dv, src, dst, sid int, burst []byte) {
			// En dinámico (BM/TGIF/ADN) el master ya solo envía los TG suscritos -> pasa todo.
			// En estático solo baja a RF el TG configurado.
			if cfg.DynamicTg || dst == cfg.Talkgroup {
				bridge.OnNetDmrd(ft, dv, src, dst, sid, burst)
			}
		},
		func(s string) { logf("estado: %s", s) }, logf)
	peer.Start()

	freqHz := 439025000
	if v, err := parseMHz(cfg.FreqMHz); err == nil {
		freqHz = int(math.Round(v))
	}

	// Watchdog del módem: si la radio no está o el USB murió, reintenta cada 5 s
	// (equivalente al receiver ATTACH/DETACH + watchdog de la app Android).
	var modem *MmdvmModem
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	warned := false

	openModem := func() {
		dev := findModemDevice(cfg.Device)
		if dev == "" {
			if !warned {
				logf("radio no encontrada (%s); esperando a que se enchufe…", cfg.Device)
				warned = true
			}
			return
		}
		m := newMmdvmModem(dev, cfg.ColorCode, freqHz, cfg.TxPowerPct, bridge.OnRfDmr, logf)
		if err := m.Open(); err != nil {
			logf("error abriendo %s: %v", dev, err)
			return
		}
		modem = m
		bridge.SetModem(m)
		warned = false
		logf("OTG open (OpenGD77) %s MHz CC%d", cfg.FreqMHz, cfg.ColorCode)
	}

	openModem()
	for {
		select {
		case <-tick.C:
			if modem != nil && !modem.Connected() {
				modem.Close()
				modem = nil
				bridge.SetModem(nil)
			}
			if modem == nil {
				openModem()
			}
		case sig := <-stop:
			logf("señal %v: parando", sig)
			if modem != nil {
				modem.Close()
			}
			peer.Stop()
			return
		}
	}
}
