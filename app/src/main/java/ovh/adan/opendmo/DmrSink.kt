package ovh.adan.opendmo

/**
 * Destino de tramas DMR hacia RF (lo implementa [MmdvmModem]).
 * Existe como interfaz para poder testear [DmoBridge] en JVM sin USB.
 */
interface DmrSink {
    val connected: Boolean
    /** Encola una trama DMR hacia la radio. slot: 1 o 2; dmrData = 33 bytes de burst. */
    fun sendDmr(slot: Int, control: Int, dmrData: ByteArray)
}
