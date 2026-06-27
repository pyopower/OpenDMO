package ovh.adan.opendmo

import android.content.Context

/**
 * Configuración persistente del gateway DMO. Todo editable desde la UI.
 *
 * El ID de login HBP NO es el DMR ID a secas: se forma con el DMR ID + un sufijo
 * (ESSID, 00-99). Es OBLIGATORIO porque casi todos los usuarios ya tienen el DMR ID
 * "pelado" registrado en otro dispositivo (hotspot, radio de red, app…). Si el DMO
 * intentara loguearse con el mismo ID habría colisión en el master (el segundo tira
 * al primero). Convención homebrew/Brandmeister: peerId = dmrId·100 + sufijo.
 *   ej. DMR ID 2130035 + sufijo 01  ->  peerId 213003501
 */
data class Config(
    var host: String = "",
    var port: Int = 62031,
    var passphrase: String = "",
    var radioId: Int = 0,       // DMR ID real de la radio (rf_src)
    var suffix: Int = 1,        // ESSID (00-99); junto al DMR ID forma el peerId de login
    var callsign: String = "",
    var talkgroup: Int = 0,
    var slot: Int = 2,
    var colorCode: Int = 1,
    var freqMHz: String = "439.025",
) {
    /** ID de login HBP = DMR ID + sufijo de 2 dígitos. */
    val peerId: Int get() = radioId * 100 + (suffix.coerceIn(0, 99))

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putString("host", host); putInt("port", port); putString("pass", passphrase)
            putInt("radioId", radioId); putInt("suffix", suffix); putString("call", callsign)
            putInt("tg", talkgroup); putInt("slot", slot); putInt("cc", colorCode)
            putString("freq", freqMHz)
        }.apply()
    }

    companion object {
        private const val PREF = "opendmo"
        fun load(ctx: Context): Config {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val d = Config()
            return Config(
                host = p.getString("host", d.host)!!,
                port = p.getInt("port", d.port),
                passphrase = p.getString("pass", d.passphrase)!!,
                radioId = p.getInt("radioId", d.radioId),
                suffix = p.getInt("suffix", d.suffix),
                callsign = p.getString("call", d.callsign)!!,
                talkgroup = p.getInt("tg", d.talkgroup),
                slot = p.getInt("slot", d.slot),
                colorCode = p.getInt("cc", d.colorCode),
                freqMHz = p.getString("freq", d.freqMHz)!!,
            )
        }
    }
}
