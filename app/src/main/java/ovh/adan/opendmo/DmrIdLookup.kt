package ovh.adan.opendmo

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Resuelve DMR ID -> indicativo para el "last heard" del log.
 *
 * [name] es SÍNCRONO y nunca bloquea: devuelve "EA1ABC (id)" si el indicativo ya está
 * en la caché (SharedPreferences, sobrevive reinicios) o "id" a secas mientras dispara
 * la consulta a radioid.net en un hilo aparte; la siguiente llamada con el mismo ID ya
 * sale resuelta, y al resolverse se anota una línea en el log. Los IDs sin registro se
 * cachean en negativo ("") para no repetir la consulta en cada llamada.
 */
class DmrIdLookup(context: Context, private val log: (String) -> Unit = {}) {
    private val prefs = context.getSharedPreferences("opendmo_ids", Context.MODE_PRIVATE)
    private val pending = Collections.synchronizedSet(mutableSetOf<Int>())

    fun name(id: Int): String {
        if (id <= 0) return id.toString()
        val cached = prefs.getString(id.toString(), null)
        if (cached != null) return if (cached.isEmpty()) id.toString() else "$cached ($id)"
        fetch(id)
        return id.toString()
    }

    private fun fetch(id: Int) {
        if (!pending.add(id)) return
        thread(name = "dmrid-$id", isDaemon = true) {
            try {
                val conn = URL("https://radioid.net/api/dmr/user/?id=$id").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val callsign = JSONObject(body).optJSONArray("results")
                    ?.optJSONObject(0)?.optString("callsign").orEmpty()
                prefs.edit().putString(id.toString(), callsign).apply()
                if (callsign.isNotEmpty()) log("$id = $callsign")
            } catch (_: Exception) {
                // sin red o API caída: no se cachea, se reintentará en la próxima llamada
            } finally {
                pending.remove(id)
            }
        }
    }
}
