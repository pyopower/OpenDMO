package ovh.adan.opendmo

import android.content.Context
import android.hardware.usb.UsbManager

/**
 * Orquesta el modo DMO/OTG: abre el OpenGD77 por USB-OTG (módem MMDVM), lo enlaza al
 * peer HBP y traduce en ambos sentidos con [DmoBridge]. La radio hace el vocoder AMBE.
 * Versión autónoma del HotspotController de RadioVampiros (sin AppState).
 *
 * @param sender envío del paquete DMRD de 53B por el peer HBP (true si se envió).
 */
class DmoController(
    private val context: Context,
    private val cfg: Config,
    private val sender: (ByteArray) -> Boolean,
    private val onStatus: (String) -> Unit,
    private val log: (String) -> Unit,
    private val nameOf: (Int) -> String = { it.toString() },
) {
    private var modem: MmdvmModem? = null
    private var bridge: DmoBridge? = null
    @Volatile var active = false; private set

    /** true si el módem sigue vivo (sus hilos rx/tx lo bajan al fallar el USB). */
    fun modemUp(): Boolean = modem?.connected == true

    /** Abre el módem. Devuelve true si quedó operativo. Reentrante: si ya había uno, lo cierra. */
    fun start(): Boolean {
        if (active) stop()
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = MmdvmModem.findDriver(usb)
        if (driver == null) { log(MmdvmModem.usbInventory(usb)); onStatus(context.getString(R.string.otg_no_radio)); return false }
        if (!usb.hasPermission(driver.device)) { onStatus(context.getString(R.string.otg_no_perm)); return false }
        val conn = usb.openDevice(driver.device) ?: run { onStatus(context.getString(R.string.otg_no_open)); return false }

        val br = DmoBridge(
            radioId = cfg.radioId, talkgroup = cfg.talkgroup, networkSlot = cfg.slot,
            dynamicTg = cfg.dynamicTg,
            sendDmrd = { seq, src, dst, sl, ft, vseq, sid, burst ->
                val pkt = DmrVoice.dmrdHeader(seq, src, dst, cfg.peerId, sl, ft, vseq, sid) + burst
                sender(pkt); Unit
            },
            log = log,
            nameOf = nameOf,
        )
        val freqHz = try { Math.round(cfg.freqMHz.trim().toDouble() * 1_000_000.0).toInt() } catch (_: Exception) { 439_025_000 }
        val md = MmdvmModem(
            port = driver.ports[0], connection = conn,
            baud = 115200, colorCode = cfg.colorCode, freqHz = freqHz, txPowerPct = cfg.txPowerPct,
            onDmr = { s, control, data -> br.onRfDmr(s, control, data) },
            onStatus = { /* status hex del módem; ignorado por ahora */ },
            log = log,
        )
        br.modem = md
        bridge = br; modem = md
        return try {
            md.open()
            active = true
            val who = if (md.version.isNotEmpty()) context.getString(R.string.otg_radio_fmt, md.version)
                      else context.getString(R.string.otg_modem_open)
            onStatus(context.getString(R.string.otg_open_fmt, who, cfg.freqMHz, cfg.colorCode))
            log("OTG open (OpenGD77) ${cfg.freqMHz} MHz CC${cfg.colorCode}")
            true
        } catch (e: Exception) {
            onStatus(context.getString(R.string.otg_err_fmt, e.message ?: "")); stop(); false
        }
    }

    /** red -> RF: cada DMRD del TG se escribe en la radio. */
    fun onNetDmrd(frameType: Int, dtypeVseq: Int, src: Int, dst: Int, streamId: Int, burst: ByteArray) {
        bridge?.onNetDmrd(frameType, dtypeVseq, src, dst, streamId, burst)
    }

    fun stop() {
        try { modem?.close() } catch (_: Exception) {}
        modem = null; bridge = null; active = false
    }

    fun rfFrames(): Int = bridge?.rfFrames ?: 0
    fun netFrames(): Int = bridge?.netFrames ?: 0
}
