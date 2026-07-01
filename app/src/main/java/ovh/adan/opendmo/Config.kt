package ovh.adan.opendmo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Configuración persistente del gateway DMO. Todo editable desde la UI.
 *
 * El ID de login HBP NO es el DMR ID a secas: se forma con el DMR ID + un sufijo
 * (ESSID, 00-99). Es OBLIGATORIO porque casi todos los usuarios ya tienen el DMR ID
 * "pelado" registrado en otro dispositivo (hotspot, radio de red, app…). Si el DMO
 * intentara loguearse con el mismo ID habría colisión en el master (el segundo tira
 * al primero). Convención homebrew/Brandmeister: peerId = dmrId·100 + sufijo.
 *   ej. DMR ID 2130035 + sufijo 01  ->  peerId 213003501
 *
 * La passphrase se guarda en EncryptedSharedPreferences (Android Keystore); si el
 * keystore del dispositivo falla se cae a SharedPreferences normales para no bloquear.
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
    var dynamicTg: Boolean = true,   // TG dinámico (estilo BM/TGIF/ADN): usa el TG que marca la radio
    var txPowerPct: Int = 100,       // potencia TX que se manda al OpenGD77 (rfLevel MMDVM, 0-100).
                                     // 100 = el firmware lo ignora y usa la potencia del canal/VFO de la radio.
    var options: String = "",        // RPTO tras el login (p.ej. "TS2_1=214;TS2_2=91"); vacío = no enviar
) {
    /** ID de login HBP = DMR ID + sufijo de 2 dígitos. */
    val peerId: Int get() = radioId * 100 + (suffix.coerceIn(0, 99))

    fun save(ctx: Context) {
        val sec = securePrefs(ctx)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            putString("host", host); putInt("port", port)
            if (sec != null) remove("pass") else putString("pass", passphrase)
            putInt("radioId", radioId); putInt("suffix", suffix); putString("call", callsign)
            putInt("tg", talkgroup); putInt("slot", slot); putInt("cc", colorCode)
            putString("freq", freqMHz); putBoolean("dyn", dynamicTg); putInt("txpwr", txPowerPct)
            putString("opts", options)
        }.apply()
        sec?.edit()?.putString("pass", passphrase)?.apply()
    }

    companion object {
        private const val PREF = "opendmo"
        private const val PREF_SECURE = "opendmo_secure"

        fun load(ctx: Context): Config {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val sec = securePrefs(ctx)
            val d = Config()
            // migración: passphrase antigua en claro -> almacén cifrado
            var pass = sec?.getString("pass", null)
            if (pass == null) {
                pass = p.getString("pass", d.passphrase)!!
                if (sec != null && pass.isNotEmpty()) {
                    sec.edit().putString("pass", pass).apply()
                    p.edit().remove("pass").apply()
                }
            }
            return Config(
                host = p.getString("host", d.host)!!,
                port = p.getInt("port", d.port),
                passphrase = pass,
                radioId = p.getInt("radioId", d.radioId),
                suffix = p.getInt("suffix", d.suffix),
                callsign = p.getString("call", d.callsign)!!,
                talkgroup = p.getInt("tg", d.talkgroup),
                slot = p.getInt("slot", d.slot),
                colorCode = p.getInt("cc", d.colorCode),
                freqMHz = p.getString("freq", d.freqMHz)!!,
                dynamicTg = p.getBoolean("dyn", d.dynamicTg),
                txPowerPct = p.getInt("txpwr", d.txPowerPct).coerceIn(0, 100),
                options = p.getString("opts", d.options)!!,
            )
        }

        private fun securePrefs(ctx: Context): SharedPreferences? = try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, PREF_SECURE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) { null }
    }
}
