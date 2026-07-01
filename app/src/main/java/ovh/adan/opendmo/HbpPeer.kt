package ovh.adan.opendmo

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Cliente HBP (Homebrew Repeater Protocol) autónomo, port de `hbp_peer.py`.
 * Login RPTL→RPTK(sha256(salt+pass))→RPTC(config)→CONNECTED + keepalive RPTPING.
 * Entrega cada DMRD recibido por [onDmrd] y permite enviar paquetes con [sendDmrd].
 *
 * Robustez 24/7:
 *  - RECONEXIÓN automática con backoff exponencial (5s→60s) ante MSTNAK, error de red
 *    o silencio del master. El DNS se re-resuelve en cada intento.
 *  - WATCHDOG de keepalive: si el master deja de contestar (ni MSTPONG ni DMRD) durante
 *    [PEER_TIMEOUT_MS] se da la sesión por muerta y se reconecta (antes `connected`
 *    quedaba en true para siempre).
 *  - Timeout de login: si el handshake no completa en [LOGIN_TIMEOUT_MS] se reintenta.
 *
 * Tras CONNECTED, si la config trae `options` se envía RPTO (suscripción de TGs estilo
 * BrandMeister/TGIF, p.ej. "TS2_1=214;TS2_2=91").
 *
 * Es la única dependencia de red del gateway: cualquier master HBP/hblink vale,
 * basta con configurar host/puerto/passphrase/peerId.
 */
class HbpPeer(
    private val context: Context,
    private val cfg: Config,
    private val onDmrd: (frameType: Int, dtypeVseq: Int, src: Int, dst: Int, streamId: Int, burst: ByteArray) -> Unit,
    private val onState: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile var connected = false; private set
    private var sock: DatagramSocket? = null
    private var addr: InetAddress? = null
    private var rxThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        rxThread = thread(name = "hbp-peer", isDaemon = true) { run() }
    }

    fun stop() {
        running = false
        try { if (connected) send(RPTCL + b4(cfg.peerId)) } catch (_: Exception) {}
        connected = false
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        onState(context.getString(R.string.st_stopped))
    }

    /** Envía un paquete DMRD ya formado (20B cab + 33B burst). */
    fun sendDmrd(pkt: ByteArray): Boolean = try { send(pkt); true } catch (e: Exception) { log("tx red: ${e.message}"); false }

    private fun send(data: ByteArray) {
        val s = sock ?: return
        val a = addr ?: return
        s.send(DatagramPacket(data, data.size, a, cfg.port))
    }

    // ---------- bucle exterior: sesión + reconexión con backoff ----------
    private fun run() {
        var attempt = 0
        while (running) {
            try {
                session { attempt = 0 }          // resetea el backoff al llegar a CONNECTED
            } catch (e: Exception) {
                log("peer: ${e.message}")
                onState(context.getString(R.string.st_error_fmt, e.message ?: ""))
            }
            connected = false
            try { sock?.close() } catch (_: Exception) {}
            sock = null
            if (!running) break
            attempt++
            val delay = min(60, 5 shl min(attempt - 1, 4))   // 5,10,20,40,60,60…
            onState(context.getString(R.string.st_retry_fmt, delay))
            var slept = 0
            while (running && slept < delay * 1000) { Thread.sleep(250); slept += 250 }
        }
    }

    /** Una sesión completa contra el master. Vuelve (o lanza) cuando la sesión muere. */
    private fun session(onConnected: () -> Unit) {
        addr = InetAddress.getByName(cfg.host)       // re-resuelve DNS en cada intento
        val s = DatagramSocket().apply { soTimeout = 1000 }
        sock = s
        var state = "RPTL"
        val t0 = System.currentTimeMillis()
        send(RPTL + b4(cfg.peerId))
        onState(context.getString(R.string.st_login))
        var tPing = 0L
        var lastHeard = System.currentTimeMillis()   // último paquete del master (pong o DMRD)
        val buf = ByteArray(2048)
        while (running) {
            val now = System.currentTimeMillis()
            if (connected && now - tPing > PING_EVERY_MS) {
                send(RPTPING + b4(cfg.peerId)); tPing = now
            }
            if (!connected && now - t0 > LOGIN_TIMEOUT_MS) { log("login timeout in $state"); return }
            if (connected && now - lastHeard > PEER_TIMEOUT_MS) {
                log("master silent ${PEER_TIMEOUT_MS / 1000}s → reconnect"); return
            }
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: Exception) { continue }
            val data = p.data.copyOf(p.length)
            lastHeard = System.currentTimeMillis()
            when {
                starts(data, RPTACK) -> when (state) {
                    "RPTL" -> {
                        val salt = data.copyOfRange(6, 10)
                        val h = sha256(salt + cfg.passphrase.toByteArray(Charsets.US_ASCII))
                        send(RPTK + b4(cfg.peerId) + h); state = "RPTK"
                        log("RPTACK salt=${DmrVoice.hex(salt)} → RPTK")
                    }
                    "RPTK" -> { send(RPTC + configPacket()); state = "RPTC"; log("auth OK → RPTC") }
                    "RPTC" -> {
                        state = "CONNECTED"; connected = true; onConnected()
                        onState(context.getString(R.string.st_connected_fmt, cfg.host)); log("CONNECTED to master")
                        if (cfg.options.isNotBlank()) {
                            send(RPTO + b4(cfg.peerId) + cfg.options.toByteArray(Charsets.US_ASCII))
                            log("RPTO: ${cfg.options}")
                        }
                    }
                    // RPTACK en CONNECTED = ack del RPTO u otro; solo liveness
                }
                starts(data, MSTNAK) -> { log("MSTNAK in $state"); onState(context.getString(R.string.st_reject)); return }
                starts(data, MSTPONG) -> {}
                starts(data, DMRD) && data.size >= 53 -> {
                    val src = u24(data, 5); val dst = u24(data, 8)
                    val bits = data[15].toInt() and 0xFF
                    val ft = (bits ushr 4) and 0x3
                    val dv = bits and 0xF
                    val streamId = ((data[16].toInt() and 0xFF) shl 24) or ((data[17].toInt() and 0xFF) shl 16) or
                                   ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
                    val burst = data.copyOfRange(20, 53)
                    onDmrd(ft, dv, src, dst, streamId, burst)
                }
            }
        }
    }

    private fun configPacket(): ByteArray {
        val fHz = (cfg.freqMHz.trim().toDoubleOrNull() ?: 439.025) * 1_000_000.0
        val fStr = "%09d".format(Math.round(fHz)).take(9)
        return b4(cfg.peerId) +
            fld(cfg.callsign.ifBlank { "NOCALL" }, 8) +
            fld(fStr, 9) +                 // RX
            fld(fStr, 9) +                 // TX
            fld("01", 2) +                 // power
            fld("%02d".format(cfg.colorCode), 2) +
            fld("0000.000", 8) +           // lat
            fld("00000.00", 9) +           // lon
            fld("000", 3) +                // height
            fld("Android", 20) +           // location
            fld("OpenDMO", 19) +           // description
            fld("4", 1) +                  // slots
            fld("https://adan.ovh", 124) + // url
            fld("OpenDMO-Kotlin", 40) +    // software id
            fld("20260627", 40)            // package id
    }

    companion object {
        private const val PING_EVERY_MS = 5_000L
        private const val PEER_TIMEOUT_MS = 30_000L   // ~6 pings sin respuesta → sesión muerta
        private const val LOGIN_TIMEOUT_MS = 10_000L

        private val RPTL = "RPTL".toByteArray(); private val RPTK = "RPTK".toByteArray()
        private val RPTC = "RPTC".toByteArray(); private val RPTCL = "RPTCL".toByteArray()
        private val RPTO = "RPTO".toByteArray()
        private val RPTPING = "RPTPING".toByteArray(); private val RPTACK = "RPTACK".toByteArray()
        private val MSTNAK = "MSTNAK".toByteArray(); private val MSTPONG = "MSTPONG".toByteArray()
        private val DMRD = "DMRD".toByteArray()

        private fun b4(n: Int): ByteArray = byteArrayOf(
            ((n ushr 24) and 0xFF).toByte(), ((n ushr 16) and 0xFF).toByte(),
            ((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte())

        private fun fld(s: String, n: Int): ByteArray {
            val b = s.toByteArray(Charsets.US_ASCII)
            return ByteArray(n) { if (it < b.size) b[it] else ' '.code.toByte() }
        }

        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        private fun u24(d: ByteArray, i: Int): Int =
            ((d[i].toInt() and 0xFF) shl 16) or ((d[i + 1].toInt() and 0xFF) shl 8) or (d[i + 2].toInt() and 0xFF)

        private fun starts(d: ByteArray, pfx: ByteArray): Boolean {
            if (d.size < pfx.size) return false
            for (i in pfx.indices) if (d[i] != pfx[i]) return false
            return true
        }
    }
}
