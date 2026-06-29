package ovh.adan.opendmo

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Driver del módem MMDVM que expone el OpenGD77 en modo hotspot, por USB-OTG.
 *
 * Port directo de `rv-hotspot-agent/mmdvm_modem.py`. El OpenGD77 imita un módem
 * MMDVM_HS por USB-CDC; trama en el cable:
 *
 *     0xE0 | LEN | TYPE | payload...
 *
 * donde LEN = longitud total de la trama (incluye los 3 bytes de cabecera).
 * Para DMR el payload de DMR_DATA1/2 es:  control(1 byte) + 33 bytes de burst DMR.
 *
 * IMPORTANTE: los 33 bytes son el burst DMR YA FORMADO (264 bits, igual que el que
 * viaja en el DMRD del HBP). En hotspot NO se reencoda BPTC ni se transcodifica AMBE:
 * el burst de RF entra/sale tal cual hacia/desde el peer. La radio hace el vocoder.
 *
 * La capa serie usa mik3y/usb-serial-for-android (CDC/ACM autodetectado >= v3.5.0),
 * sin root. La apertura del puerto + permiso USB lo hace la Activity (ver companion).
 *
 * Callback [onDmr]:  (slot, control, data33) por cada burst recibido de la radio (RF -> red).
 *                    control == 0xFF marca FIN de TX de RF (DMR_LOST).
 */
class MmdvmModem(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
    private val baud: Int,
    private val colorCode: Int,
    private val freqHz: Int,        // frecuencia DMO (RX=TX) en Hz; se empuja al OpenGD77 con SET_FREQ
    private val txPowerPct: Int = 100,  // potencia TX (rfLevel MMDVM 0-100); 100 = la radio usa su VFO/canal
    private val onDmr: (slot: Int, control: Int, data: ByteArray) -> Unit,
    private val onStatus: ((String) -> Unit)? = null,
    private val log: (String) -> Unit = {},
) {
    @Volatile var connected = false; private set
    @Volatile var version = ""; private set

    private val txQueue = LinkedBlockingQueue<ByteArray>(200)
    @Volatile private var stop = false
    private var rxThread: Thread? = null
    private var txThread: Thread? = null

    // usb-serial-for-android 3.7.0 hace dentro de read() un "testConnection" = control-transfer USB
    // GET_STATUS estandar. El firmware del OpenGD77 (MMDVM_HS) NO responde a ese request -> el
    // controlTransfer devuelve <0 -> IOException("USB get_status request failed") en cuanto una
    // lectura no trae datos (en DMO casi siempre). El modo DMO web (WebSerial) nunca lo envia, por
    // eso alli funciona. Solucion: usar la sobrecarga read(byte[],int,testConnection=false), que NO
    // dispara ese test (la ruta bulkTransfer con timeout>0 solo testea si el flag es true). Es
    // protected y no hay equivalente publico en 3.7.0, asi que la resolvemos por reflexion.
    private val readNoTest: java.lang.reflect.Method? = runCatching {
        Class.forName("com.hoho.android.usbserial.driver.CommonUsbSerialPort")
            .getDeclaredMethod("read", ByteArray::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .also { it.isAccessible = true }
    }.getOrNull()

    /** Lectura del puerto SIN el testConnection (control-transfer GET_STATUS) de la libreria. */
    private fun readPort(dst: ByteArray, timeoutMs: Int): Int {
        val m = readNoTest ?: return port.read(dst, timeoutMs)
        return try {
            m.invoke(port, dst, timeoutMs, false) as Int
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.cause as? Exception ?: e)
        }
    }

    // ---------- ciclo de vida ----------
    fun open() {
        port.open(connection)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // El OpenGD77 (CDC-ACM) necesita DTR activo para abrir el flujo.
        try { port.dtr = true } catch (_: Exception) {}
        stop = false
        handshake()
        connected = true
        rxThread = thread(name = "mmdvm-rx", isDaemon = true) { rxLoop() }
        txThread = thread(name = "mmdvm-tx", isDaemon = true) { txLoop() }
        log("Módem MMDVM abierto (OpenGD77)")
    }

    fun close() {
        stop = true
        connected = false
        try { frame(SET_MODE, byteArrayOf(MODE_IDLE)) } catch (_: Exception) {}
        try { port.close() } catch (_: Exception) {}
    }

    // ---------- protocolo ----------
    private fun build(typ: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = FRAME_START.toByte()
        out[1] = (payload.size + 3).toByte()
        out[2] = typ.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    private fun frame(typ: Int, payload: ByteArray = ByteArray(0)) {
        port.write(build(typ, payload), WRITE_TIMEOUT_MS)
    }

    /** Frecuencia en Hz -> 4 bytes little-endian (como freqLE de dmo.js). */
    private fun freqLE(hz: Int): ByteArray =
        byteArrayOf((hz and 0xFF).toByte(), ((hz ushr 8) and 0xFF).toByte(),
                    ((hz ushr 16) and 0xFF).toByte(), ((hz ushr 24) and 0xFF).toByte())

    private fun handshake() {
        // Secuencia CALCADA del cliente web validado (static/dmo.js): la radio entra en DMR
        // y queda en la frecuencia DMO seleccionada. GET_VERSION -> SET_FREQ -> SET_CONFIG -> SET_MODE.
        frame(GET_VERSION)
        Thread.sleep(250)
        // SET_FREQ (0x04): [0x00, rxFreq(4 LE), txFreq(4 LE), rfLevel(1), pocsagFreq(4 LE pad)]
        // En DMO rx=tx=freqHz. El padding 0x40,0x0e,0xcf,0x19 (=433 MHz POCSAG) va literal como en dmo.js.
        // rfLevel = potencia TX (0-255). El OpenGD77 lo interpreta como % de su escala (255=100% → usa el
        // VFO/canal de la radio); cualquier otro valor lo redondea al escalón más cercano (50mW…5W).
        val rfLevel = Math.round(txPowerPct.coerceIn(0, 100) * 255.0 / 100.0).toInt().coerceIn(0, 255)
        val f = freqLE(freqHz)
        frame(SET_FREQ, byteArrayOf(0x00) + f + f + byteArrayOf(rfLevel.toByte(), 0x40, 0x0e, 0xcf.toByte(), 0x19))
        Thread.sleep(120)
        // SET_CONFIG (0x02): bloque exacto de dmo.js (cc en el índice 6).
        val cfg = byteArrayOf(
            0x80.toByte(), 0x02, 0x0a, 0x00, 0x80.toByte(), 0x80.toByte(), colorCode.toByte(), 0x00,
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x04, 0x80.toByte(), 0x80.toByte(), 0x05, 0x05, 0x00, 0x00,
        )
        frame(SET_CONFIG, cfg)
        Thread.sleep(120)
        // SET_MODE DMR
        frame(SET_MODE, byteArrayOf(MODE_DMR))
        Thread.sleep(80)
        // leemos lo que haya (version/ack)
        try {
            val buf = ByteArray(256)
            val n = readPort(buf, 300)
            if (n > 0) {
                version = extractVersion(buf, n)
                if (version.isNotEmpty()) log("Radio: $version")
            }
        } catch (_: Exception) {}
    }

    private fun extractVersion(buf: ByteArray, len: Int): String {
        var i = 0
        while (i < len - 2) {
            if ((buf[i].toInt() and 0xFF) == FRAME_START) {
                val ln = buf[i + 1].toInt() and 0xFF
                if ((buf[i + 2].toInt() and 0xFF) == GET_VERSION && i + ln <= len) {
                    return String(buf, i + 4, ln - 4, Charsets.ISO_8859_1).trim()
                }
                i += maxOf(ln, 1)
            } else i++
        }
        return ""
    }

    // ---------- envío (red -> RF) ----------
    /** Encola una trama DMR hacia la radio. slot: 1 o 2; data = 33 bytes de burst. */
    fun sendDmr(slot: Int, control: Int, dmrData: ByteArray) {
        val typ = if (slot == 2) DMR_DATA2 else DMR_DATA1
        val data = if (dmrData.size >= DMR_FRAME_BYTES) dmrData.copyOf(DMR_FRAME_BYTES)
                   else dmrData.copyOf(DMR_FRAME_BYTES) // copyOf rellena con 0 si es más corto
        val payload = ByteArray(1 + DMR_FRAME_BYTES)
        payload[0] = control.toByte()
        System.arraycopy(data, 0, payload, 1, DMR_FRAME_BYTES)
        if (!txQueue.offer(build(typ, payload))) log("Cola TX del módem llena, descarto trama")
    }

    private fun txLoop() {
        while (!stop) {
            val f = try { txQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            try {
                port.write(f, WRITE_TIMEOUT_MS)
            } catch (e: Exception) {
                log("Error escribiendo al módem: ${e.message}")
                connected = false
                return
            }
        }
    }

    // ---------- recepción (RF -> red) ----------
    private fun rxLoop() {
        val buf = ArrayDeque<Byte>()
        val tmp = ByteArray(256)
        var lastPoll = 0L
        while (!stop) {
            val n = try {
                readPort(tmp, 50)
            } catch (e: Exception) {
                log("Error leyendo del módem: ${e.message}")
                connected = false
                return
            }
            if (n > 0) {
                for (k in 0 until n) buf.addLast(tmp[k])
                consume(buf)
            }
            val now = System.nanoTime()
            if (now - lastPoll > 1_000_000_000L) {   // poll de estado cada 1 s
                lastPoll = now
                try { frame(GET_STATUS) } catch (_: Exception) {}
            }
        }
    }

    private fun consume(buf: ArrayDeque<Byte>) {
        while (buf.size >= 3) {
            if ((buf.first().toInt() and 0xFF) != FRAME_START) { buf.removeFirst(); continue }
            val ln = buf.elementAt(1).toInt() and 0xFF
            if (ln < 3) { buf.removeFirst(); continue }
            if (buf.size < ln) return                 // trama incompleta, espera más bytes
            val typ = buf.elementAt(2).toInt() and 0xFF
            val payload = ByteArray(ln - 3)
            // descarta cabecera (3) y extrae payload
            buf.removeFirst(); buf.removeFirst(); buf.removeFirst()
            for (k in payload.indices) payload[k] = buf.removeFirst()
            dispatch(typ, payload)
        }
    }

    private fun dispatch(typ: Int, payload: ByteArray) {
        when (typ) {
            DMR_DATA1, DMR_DATA2 -> if (payload.size >= 1 + DMR_FRAME_BYTES) {
                val slot = if (typ == DMR_DATA2) 2 else 1
                val control = payload[0].toInt() and 0xFF
                onDmr(slot, control, payload.copyOfRange(1, 1 + DMR_FRAME_BYTES))
            }
            DMR_LOST1, DMR_LOST2 -> {
                val slot = if (typ == DMR_LOST2) 2 else 1
                onDmr(slot, 0xFF, ByteArray(0))       // 0xFF = marcador de fin de TX RF
            }
            GET_STATUS -> onStatus?.invoke("status:" + payload.joinToString("") { "%02x".format(it) })
        }
    }

    companion object {
        // --- Protocolo MMDVM (subconjunto DMR) ---
        const val FRAME_START = 0xE0
        const val GET_VERSION = 0x00
        const val GET_STATUS = 0x01
        const val SET_CONFIG = 0x02
        const val SET_MODE = 0x03
        const val SET_FREQ = 0x04
        const val DMR_DATA1 = 0x18
        const val DMR_DATA2 = 0x1A
        const val DMR_LOST1 = 0x19
        const val DMR_LOST2 = 0x1B
        const val DMR_START = 0x1D

        const val MODE_IDLE: Byte = 0x00
        const val MODE_DMR: Byte = 0x02

        // --- Constantes DMR del byte de control (dmrDefines.c del firmware OpenGD77) ---
        const val DMR_SYNC_DATA = 0x40
        const val DMR_SYNC_AUDIO = 0x20
        const val DT_VOICE_SYNC = 0xF0
        const val DT_VOICE = 0xF1

        const val DMR_FRAME_BYTES = 33
        const val WRITE_TIMEOUT_MS = 1000

        const val VID_NXP = 0x1fc9     // OpenGD77 (GD-77/RD-5R/DM-1801) = chip NXP LPC, CDC-ACM

        /** Drivers serie USB disponibles por OTG (CDC/ACM autodetectado). El OpenGD77
         *  aparece como CDC-ACM. La Activity pide permiso y abre la conexión. */
        fun availableDrivers(usbManager: UsbManager): List<UsbSerialDriver> =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        /**
         * Busca el OpenGD77 de forma ROBUSTA en cualquier ROM (incl. LineageOS/OnePlus, donde
         * el prober por defecto NO lo reconoce porque su VID 0x1fc9 no está en la tabla built-in):
         *   1) probe table propio: VIDs/PIDs del OpenGD77 -> CDC-ACM (igual que filtra dmo.js).
         *   2) prober por defecto (FTDI/CP21xx/CH34x/CDC conocidos) — preserva lo que ya funcionaba.
         *   3) último recurso: cualquier dispositivo NXP (VID 0x1fc9) forzado a CDC-ACM (el PID puede
         *      variar según modo/ROM).
         */
        fun findDriver(usbManager: UsbManager): UsbSerialDriver? {
            val table = ProbeTable().apply {
                addProduct(VID_NXP, 0x0094, CdcAcmSerialDriver::class.java)  // OpenGD77 LPC
                addProduct(0x15a2, 0x0073, CdcAcmSerialDriver::class.java)   // variante conocida (config.py)
            }
            UsbSerialProber(table).findAllDrivers(usbManager).firstOrNull()?.let { return it }
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()?.let { return it }
            usbManager.deviceList.values.firstOrNull { it.vendorId == VID_NXP }?.let { return CdcAcmSerialDriver(it) }
            return null
        }

        /** Inventario de dispositivos USB visibles (diagnóstico del OTG en ROMs problemáticas):
         *  si sale "0 dispositivos" el problema es de enumeración/OTG (ajustes/cable), no del driver. */
        fun usbInventory(usbManager: UsbManager): String {
            val devs = usbManager.deviceList.values
            if (devs.isEmpty()) return "USB: 0 dispositivos (¿OTG desactivado en ajustes o cable?)"
            return "USB: " + devs.joinToString(", ") { "%04x:%04x".format(it.vendorId, it.productId) }
        }
    }
}
