# OpenDMO

![Android CI](https://github.com/pyopower/OpenDMO/actions/workflows/android.yml/badge.svg)

Aplicación Android **independiente y configurable** que convierte un teléfono +
una radio **OpenGD77** (GD-77 / RD-5R / DM-1801, conectada por **USB-OTG**) en un
**gateway DMO** hacia cualquier master DMR Homebrew (HBP/hblink/BrandMeister-style).

La radio hace de módem MMDVM y del **vocoder AMBE**; el teléfono solo traduce y
enruta los bursts DMR entre el aire (DMO simplex) y la red. No necesita root.

> ⚠️ Uso para radioaficionados con licencia. Transmitir en bandas de aficionado
> requiere indicativo y autorización. Úsalo bajo tu responsabilidad.

> 🛜 **¿Sin móvil? También hay port para routers OpenWrt** (GL.iNet Mango y
> similares): daemon en Go con los mismos protocolos, en la rama
> [`openwrt`](https://github.com/pyopower/OpenDMO/tree/openwrt). Paquetes `.ipk`
> en las releases `openwrt-v*`.

## Características

- Gateway **DMO** (simplex directo) OpenGD77 ↔ master HBP por OTG, sin red propia.
- **Todo configurable** en la app: host/puerto del master, passphrase, DMR ID,
  sufijo/ESSID, indicativo, talkgroup, timeslot, color code y frecuencia DMO.
- **Peer ID con sufijo obligatorio** (`DMR ID + ESSID`) para no chocar con tu
  hotspot u otros dispositivos ya registrados con tu DMR ID.
- Funciona en **segundo plano / pantalla apagada** (foreground service +
  WakeLock + exención de optimización de batería).
- Se **ofrece al conectar** la OpenGD77 (filtro USB) y puede arrancar sola.
- **Reconexión automática** al master (backoff 5→60 s) y watchdog de keepalive:
  si el master calla 30 s o cambia la red (WiFi↔datos), relogin solo.
- **Hot-plug USB**: si desenchufas/enchufas la radio con el gateway en marcha,
  el módem se cierra/reabre solo (sin pasar por la app); watchdog del USB cada 10 s.
- **Options `RPTO`** tras el login (suscripción de TGs estilo BM/TGIF,
  p. ej. `TS2_1=214;TS2_2=91`).
- **Last heard con indicativo**: los DMR ID se resuelven contra radioid.net
  (caché local persistente) y salen en el log como `EA1ABC (2130001)`.
- **Pacing hacia la radio por espacio real del buffer** del firmware (`GET_STATUS`),
  como MMDVMHost: no se desborda el módem si el master manda a ráfagas.
- **Passphrase cifrada** (EncryptedSharedPreferences / Android Keystore), con
  migración automática desde el almacén antiguo.
- Notificación con **botón Parar** y acceso directo a la app.
- **Multiidioma** siguiendo el sistema (en/es/fr/de/it/pt; inglés por defecto).
- **Tests JUnit** del mapeo del bridge (golden test derivado de la captura con
  radio real) + **CI en GitHub Actions** (APK debug por push, Release en tags `v*`).

## Compilar

Requiere Android SDK (compileSdk 34) y JDK 17.

```bash
# crea local.properties con la ruta del SDK
echo "sdk.dir=/ruta/a/Android/Sdk" > local.properties

# debug
./gradlew assembleDebug

# release firmado: crea keystore.properties (no versionado) con
#   storeFile=/ruta/al.keystore
#   storePassword=...
#   keyAlias=...
#   keyPassword=...
./gradlew assembleRelease
```

## Arquitectura

| Componente        | Función |
|-------------------|---------|
| `MmdvmModem`      | Driver del módem MMDVM del OpenGD77 por USB-CDC (handshake `GET_VERSION`/`SET_FREQ`/`SET_CONFIG`/`SET_MODE`). |
| `DmoBridge`       | Traducción bidireccional control↔(frameType,vseq) + jitter buffer + half-duplex. |
| `HbpPeer`         | Cliente Homebrew (RPTL→RPTK→RPTC + keepalive), RX/TX de paquetes `DMRD`. |
| `DmoController`   | Orquesta módem + bridge. |
| `DmoService`      | Foreground service (corre con la pantalla apagada). |
| `MainActivity`    | UI de configuración y control. |

## Licencia

[GPLv3](LICENSE) — software libre. Si distribuyes versiones modificadas deben
seguir siendo libres bajo la misma licencia.

## Créditos

OpenDMO · by **C31AG** · 2026

Basado en el protocolo MMDVM (g4klx/MMDVMHost), el firmware
[OpenGD77](https://github.com/LibreDMR/OpenGD77_firmware) y
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).
