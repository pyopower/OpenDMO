package ovh.adan.opendmo

import kotlin.random.Random

/**
 * Subconjunto DMR/HBP necesario para el gateway DMO (sin vocoder).
 * En modo gateway los bursts de 33 B pasan crudos entre RF y red; solo construimos
 * la cabecera DMRD (20 B) del Homebrew Protocol. Constantes y layout tomados del
 * DmrVoice.kt de RadioVampiros (ya validado en aire).
 */
object DmrVoice {
    const val HBPF_VOICE = 0x0
    const val HBPF_VOICE_SYNC = 0x1
    const val HBPF_DATA_SYNC = 0x2

    /** Cabecera HBP DMRD (20B) sin el burst. slot 1/2, group call. */
    fun dmrdHeader(seqNo: Int, rfSrc: Int, dst: Int, peer: Int, slot: Int,
                   frameType: Int, dtypeVseq: Int, streamId: ByteArray): ByteArray {
        var bits = 0
        if (slot == 2) bits = bits or 0x80
        bits = bits or ((frameType and 0x3) shl 4)
        bits = bits or (dtypeVseq and 0xF)
        val h = ByteArray(20)
        h[0] = 'D'.code.toByte(); h[1] = 'M'.code.toByte(); h[2] = 'R'.code.toByte(); h[3] = 'D'.code.toByte()
        h[4] = (seqNo and 0xFF).toByte()
        h[5] = ((rfSrc ushr 16) and 0xFF).toByte(); h[6] = ((rfSrc ushr 8) and 0xFF).toByte(); h[7] = (rfSrc and 0xFF).toByte()
        h[8] = ((dst ushr 16) and 0xFF).toByte(); h[9] = ((dst ushr 8) and 0xFF).toByte(); h[10] = (dst and 0xFF).toByte()
        h[11] = ((peer ushr 24) and 0xFF).toByte(); h[12] = ((peer ushr 16) and 0xFF).toByte()
        h[13] = ((peer ushr 8) and 0xFF).toByte(); h[14] = (peer and 0xFF).toByte()
        h[15] = bits.toByte()
        System.arraycopy(streamId, 0, h, 16, 4)
        return h
    }

    fun newStreamId(): ByteArray = Random.nextBytes(4)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
