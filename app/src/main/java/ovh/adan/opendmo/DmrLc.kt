package ovh.adan.opendmo

/**
 * Decodificador de Full Link Control de una cabecera/terminador de voz DMR.
 *
 * Port FIEL de `dmr_utils3.bptc` (N0MJS) — el mismo módulo del que RadioVampiros portó el
 * ENCODE. Aquí solo hacemos el DECODE necesario para el modo DMO dinámico: sacar el TG (dst)
 * y el origen (src) que marca la radio, sin transcodificar nada.
 *
 * Un burst DMR son 264 bits (33 bytes). En un VOICE LC HEADER, los 196 bits de información
 * llevan el Full LC codificado en BPTC(196,96). El layout del burst (idéntico al `dataBurst`
 * de DmrVoice de RV): info[0:98] en bits 0..97 y info[98:196] en bits 166..263; en medio van
 * slot-type (98..107, 156..165) y sync (108..155).
 *
 * `decodeFullLc` lee directamente los 72 bits del LC de los bits ON-AIR (interleaved): los
 * índices [LC_BITS] de `bptc.decode_full_lc` YA incorporan el deinterleave, así que NO hay que
 * deinterleavear aparte (verificado contra el encode de dmr_utils3 en raspi). No necesita
 * corrección FEC: la radio recibida localmente por USB casi no tiene errores.
 */
object DmrLc {

    // Posiciones de los 72 bits del LC en los bits on-air (bptc.decode_full_lc).
    private val LC_BITS = intArrayOf(
        136, 121, 106, 91, 76, 61, 46, 31,
        152, 137, 122, 107, 92, 77, 62, 47, 32, 17, 2,
        123, 108, 93, 78, 63, 48, 33, 18, 3, 184, 169,
        94, 79, 64, 49, 34, 19, 4, 185, 170, 155, 140,
        65, 50, 35, 20, 5, 186, 171, 156, 141, 126, 111,
        36, 21, 6, 187, 172, 157, 142, 127, 112, 97, 82,
        7, 188, 173, 158, 143, 128, 113, 98, 83
    )

    /** FLCO 0 = group call (TG), 3 = unit-to-unit (privada). */
    data class Lc(val flco: Int, val dst: Int, val src: Int) {
        val isGroup: Boolean get() = flco == 0
    }

    /**
     * Decodifica el Full LC de un burst de cabecera de voz (33 B). Devuelve null si el burst
     * es demasiado corto o el LC resultante es claramente inválido (dst/src a cero).
     */
    fun decodeHeaderLc(burst: ByteArray): Lc? {
        if (burst.size < 33) return null

        // 33 bytes -> 264 bits (big-endian, MSB primero, como bitarray de dmr_utils3).
        val bits = IntArray(264)
        for (i in 0 until 33) {
            val v = burst[i].toInt() and 0xFF
            for (b in 0 until 8) bits[i * 8 + b] = (v ushr (7 - b)) and 1
        }

        // info196 (interleaved, "on-air") = bits[0:98] + bits[166:264].
        val info = IntArray(196)
        for (i in 0 until 98) info[i] = bits[i]
        for (i in 0 until 98) info[98 + i] = bits[166 + i]

        // 72 bits del LC -> 9 bytes. LC_BITS se indexa DIRECTO sobre info on-air (sin deinterleave).
        val lc = IntArray(9)
        for (i in 0 until 9) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or info[LC_BITS[i * 8 + b]]
            lc[i] = v
        }

        val flco = lc[0] and 0x3F
        val dst = (lc[3] shl 16) or (lc[4] shl 8) or lc[5]
        val src = (lc[6] shl 16) or (lc[7] shl 8) or lc[8]
        if (dst == 0 || src == 0) return null
        return Lc(flco, dst, src)
    }
}
