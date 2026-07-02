# OpenDMO para OpenWrt (rama `openwrt`)

Gateway **DMO OpenGD77 ↔ master DMR Homebrew** (hblink3, BrandMeister, TGIF, ADN…)
como daemon en Go para routers OpenWrt — pensado para el GL.iNet **Mango**
(GL-MT300N-V2) y similares. Port de la app Android
[OpenDMO](https://github.com/pyopower/OpenDMO): la radio OpenGD77 en modo hotspot
se enchufa al USB del router y el daemon puentea sus bursts DMR con el master. La
radio pone el vocoder AMBE; los 33 bytes de burst pasan crudos en ambos sentidos
(sin transcodificar).

- Binario estático único (~2 MB), sin dependencias. Solo necesita `kmod-usb-acm`.
- Mismo comportamiento validado en aire que la app: handshake MMDVM
  (GET_VERSION → SET_FREQ → SET_CONFIG → SET_MODE), jitter buffer de 240 ms,
  half-duplex, TG dinámico por Full LC, pacing por GET_STATUS, reconexión HBP
  con backoff y RPTO.
- Tests golden del mapeo MMDVM↔DMRD portados 1:1 de la app (`go test`).

## Instalación (router OpenWrt)

Directo desde el router (el .ipk se baja a `/tmp`, en RAM):

```sh
cd /tmp
wget https://github.com/pyopower/OpenDMO/releases/download/openwrt-v0.1.0/opendmo_0.1.0_mipsel_24kc.ipk
opkg update
opkg install kmod-usb-acm /tmp/opendmo_0.1.0_mipsel_24kc.ipk
```

(En un OpenWrt vanilla sin certificados CA: `opkg install ca-bundle` primero, o
`wget --no-check-certificate`. El firmware de fábrica GL.iNet ya los trae.)

Arquitecturas publicadas: `mipsel_24kc` (Mango/ramips), `mips_24kc` (ath79),
`aarch64_cortex-a53` (GL.iNet aarch64).

Configurar y arrancar:

```sh
uci set opendmo.main.host='master.ejemplo.org'
uci set opendmo.main.port='62031'
uci set opendmo.main.passphrase='s3cr3t'
uci set opendmo.main.dmr_id='2130035'
uci set opendmo.main.callsign='EA1ABC'
uci set opendmo.main.freq_mhz='439.025'
uci set opendmo.main.enabled='1'
uci commit opendmo
/etc/init.d/opendmo restart
logread -f -e opendmo
```

Opciones (mismas que la app Android): `essid` (sufijo 00-99 del peer ID),
`talkgroup` + `dynamic_tg '0'` para TG fijo, `slot`, `color_code`, `tx_power`
(0-100; 100 = potencia del canal/VFO de la radio), `options` (RPTO, p.ej.
`TS2_1=214;TS2_2=91`), `device` (`auto` busca `/dev/ttyACM*`).

## Compilación

Con Go ≥ 1.19 en cualquier máquina:

```sh
make test      # gofmt + vet + tests golden
make ipk       # binarios mipsle/mips/aarch64 + .ipk en dist/
```

También corre en cualquier Linux sin OpenWrt (`make host`), útil para probar en
una Raspberry Pi con la radio en USB:

```sh
./dist/opendmo -host master.ejemplo.org -port 62031 -id 2130035 -call EA1ABC \
    -freq 439.025 -pass s3cr3t
```

## Notas

- El GL.iNet **Opal** (GL-SFT1200, SoC Siflower) no tiene soporte OpenWrt
  oficial; su firmware de fábrica es OpenWrt-based pero con SDK propio. El
  binario `mipsel_24kc` puede no ser compatible — sin probar.
- La passphrase se pasa al daemon por entorno (no aparece en `ps`) y
  `/etc/config/opendmo` queda con permisos `600`.
- El daemon reabre solo el módem si la radio se desenchufa/reenchufa, y
  procd lo respawnea si muere.
