# OpenDMO

Aplicación Android **independiente y configurable** que convierte un teléfono +
una radio **OpenGD77** (GD-77 / RD-5R / DM-1801, conectada por **USB-OTG**) en un
**gateway DMO** hacia cualquier master DMR Homebrew (HBP/hblink/BrandMeister-style).

La radio hace de módem MMDVM y del **vocoder AMBE**; el teléfono solo traduce y
enruta los bursts DMR entre el aire (DMO simplex) y la red. No necesita root.

> ⚠️ Uso para radioaficionados con licencia. Transmitir en bandas de aficionado
> requiere indicativo y autorización. Úsalo bajo tu responsabilidad.

## Características

- Gateway **DMO** (simplex directo) OpenGD77 ↔ master HBP por OTG, sin red propia.
- **Todo configurable** en la app: host/puerto del master, passphrase, DMR ID,
  sufijo/ESSID, indicativo, talkgroup, timeslot, color code y frecuencia DMO.
- **Peer ID con sufijo obligatorio** (`DMR ID + ESSID`) para no chocar con tu
  hotspot u otros dispositivos ya registrados con tu DMR ID.
- Funciona en **segundo plano / pantalla apagada** (foreground service +
  WakeLock + exención de optimización de batería).
- Se **ofrece al conectar** la OpenGD77 (filtro USB) y puede arrancar sola.
- **Multiidioma** siguiendo el sistema (en/es/fr/de/it/pt; inglés por defecto).

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
