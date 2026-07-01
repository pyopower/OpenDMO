package ovh.adan.opendmo

import kotlin.random.Random

/**
 * Puente bidireccional MMDVM(OpenGD77) <-> peer HBP (RadioVampiros), modo DMO.
 *
 * Port FIEL del gateway validado en producción `rv-dmo/rv_dmo_gw.py` (DmoSession) — cuyo
 * mapeo se confirmó por captura con radio real el 27-jun-2026 — y de la lógica de jitter
 * del cliente web `static/dmo.js`. Sustituye al port viejo de `bridge.py` (que estaba MAL:
 * confundía el burst A de voz y la voz B-F con datos, y mandaba control != 0 a la radio).
 *
 *  RF -> red (`onRfDmr`):  el byte de control del cable lleva sync bits:
 *      DMR_SYNC_DATA(0x40)|dtype para datos (1=header, 2=terminator), DMR_SYNC_AUDIO(0x20)
 *      para el burst A de voz, 0x00 para voz B-F. De ahí salen (ft,dv) del DMRD.
 *  red -> RF (`onNetDmrd`):  al radio el control es SIEMPRE 0x00 (el tipo va embebido en el
 *      slot-type del burst de 33 B). Se escribe como DMR_DATA2 (slot 2). Jitter buffer de
 *      [PREBUF] tramas (≈240 ms) + half-duplex (mientras hay RF no se mete red).
 *
 * @param sendDmrd  (seqNo, rfSrc, dst, slot, frameType, dtypeVseq, streamId, burst) -> peer HBP.
 */
class DmoBridge(
    private val radioId: Int,
    private val talkgroup: Int,
    private val networkSlot: Int,
    private val dynamicTg: Boolean,    // si true, el dst sale del Full LC que marca la radio (BM/TGIF/ADN)
    private val sendDmrd: (seqNo: Int, rfSrc: Int, dst: Int, slot: Int,
                           frameType: Int, dtypeVseq: Int, streamId: ByteArray, burst: ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
    private val nameOf: (Int) -> String = { it.toString() },   // DMR ID -> "EA1ABC (id)" si se conoce
) {
    var modem: DmrSink? = null

    // --- RF -> red ---
    private var txStream: ByteArray? = null
    private var txSeq = 0
    private var vpos = 0
    private var txDst = talkgroup                  // TG destino de la llamada RF en curso (latcheado en la cabecera)
    @Volatile private var rfUntil = 0L            // ms monotónicos: RF activo hasta aquí (half-duplex)
    var rfFrames = 0; private set

    // --- red -> RF (con jitter buffer) ---
    private var rxStream: Int = 0
    private var rxActive = false
    private var rxLast = 0L
    private val txbuf = ArrayDeque<ByteArray>()
    var netFrames = 0; private set

    var lastRx: String = ""; private set
    private fun now() = System.nanoTime() / 1_000_000L

    // ---------- RF -> red (lo llama el módem en onDmr) ----------
    fun onRfDmr(slot: Int, control: Int, data: ByteArray) {
        if (control == 0xFF) { txStream = null; return }     // DMR_LOST: fin de TX de RF
        if (data.size < 33) return
        val now = now()
        if (txStream != null && now > rfUntil) txStream = null   // hueco >1s: la llamada anterior acabó sin term
        rfUntil = now + 1000                                     // marca RF activo (half-duplex)

        val dataSync = (control and MmdvmModem.DMR_SYNC_DATA) != 0
        val audioSync = (control and MmdvmModem.DMR_SYNC_AUDIO) != 0
        val ft: Int; val dv: Int
        when {
            dataSync -> {
                val dt = control and 0x0F
                when (dt) {
                    1 -> {                                       // VOICE LC HEADER (la radio lo repite ~3x)
                        if (txStream == null) {                  // SOLO inicia stream si no hay llamada activa
                            txStream = Random.nextBytes(4); txSeq = 0; vpos = 0; rfFrames = 0
                            // TG dinámico: el TG real lo marca la radio en el Full LC de esta cabecera.
                            val lc = if (dynamicTg) DmrLc.decodeHeaderLc(data) else null
                            txDst = lc?.dst ?: talkgroup
                            setLastRx("RF ${nameOf(lc?.src ?: radioId)} → TG$txDst")
                        }
                        ft = DmrVoice.HBPF_DATA_SYNC; dv = 1
                    }
                    2 -> { ft = DmrVoice.HBPF_DATA_SYNC; dv = 2 } // TERMINATOR
                    else -> { ft = DmrVoice.HBPF_DATA_SYNC; dv = dt }
                }
            }
            audioSync -> { vpos = 0; ft = DmrVoice.HBPF_VOICE_SYNC; dv = 0 }   // burst A
            else -> { vpos = (vpos + 1) % 6; ft = DmrVoice.HBPF_VOICE; dv = vpos }  // voz B-F
        }
        if (txStream == null) { txStream = Random.nextBytes(4); txSeq = 0 }
        sendDmrd(txSeq and 0xFF, radioId, txDst, networkSlot, ft, dv, txStream!!, data)
        txSeq++; rfFrames++
        if (dataSync && (control and 0x0F) == 2) txStream = null   // fin de llamada
    }

    // ---------- red -> RF (lo llama el DmrService al recibir un DMRD del TG) ----------
    fun onNetDmrd(frameType: Int, dtypeVseq: Int, src: Int, dst: Int, streamId: Int, burst: ByteArray) {
        val m = modem ?: return
        if (!m.connected) return
        val now = now()
        if (now < rfUntil) return                               // half-duplex: en RF no metemos red

        if (rxActive && now - rxLast > 2000) { flush(m); rxActive = false }   // se perdió el terminator
        if (!rxActive) {
            if (frameType == DmrVoice.HBPF_DATA_SYNC && dtypeVseq == 2) return  // cola de llamada ya terminada
            rxStream = streamId; rxActive = true; txbuf.clear()
            setLastRx("NET→RF ${nameOf(src)} → TG$dst")
        } else if (streamId != rxStream) {
            return                                              // otra llamada simultánea: la 1ª gana
        }
        rxLast = now; netFrames++

        // jitter buffer: encola y libera 1 por cada entrante tras prebuffer (ritmo ≈ del master)
        txbuf.addLast(burst)
        if (txbuf.size > PREBUF) m.sendDmr(networkSlot, 0x00, txbuf.removeFirst())

        if (frameType == DmrVoice.HBPF_DATA_SYNC && dtypeVseq == 2) {   // terminator -> fin: vacía a la radio
            flush(m); rxActive = false
        }
    }

    private fun flush(m: DmrSink) { while (txbuf.isNotEmpty()) m.sendDmr(networkSlot, 0x00, txbuf.removeFirst()) }

    private fun setLastRx(text: String) { lastRx = text; log(text) }

    companion object { private const val PREBUF = 4 }   // 4×60 ms ≈ 240 ms de cojín (igual que la web)
}
