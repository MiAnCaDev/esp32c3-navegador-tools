package dev.gpsnav.bridge

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Obtiene las coordenadas de un enlace de Google Maps. Los enlaces largos se leen sin internet; los cortos
 * (maps.app.goo.gl) se siguen por sus redirecciones. Solo se conecta a dominios de Google, solo por HTTPS,
 * y únicamente cuando el usuario le pasa un enlace.
 */
object LinkResolver {
    class Result(val lat: Double, val lon: Double, val name: String?)

    private const val TAG = "Link"
    private const val MAX_HOPS = 6
    private const val MAX_BODY = 300_000
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36"

    private val urlRe = Regex("""https?://[^\s<>"']+""")
    // Solo dominios de Google que existen y son suyos. Antes valía cualquier "google.<2-3 letras>", y algunos
    // (p. ej. .tk o .top) los puede registrar cualquiera: con un enlace así la app se habría conectado a un sitio ajeno.
    private val googleHost = Regex("""(?:[a-z0-9-]+\.)*google\.(?:com|es|fr|de|it|pt|nl|be|ch|at|ie|ca|cl|pe|co\.uk|com\.mx|com\.ar|com\.br|com\.co)""")

    fun extractUrl(text: String): String? = urlRe.find(text)?.value?.trimEnd('.', ',', ')', ';')

    /** Nombre sugerido: primera línea que no sea un enlace ni coordenadas, o el nombre de la ruta /place/NOMBRE/. */
    fun guessName(text: String): String? {
        val line = text.lines().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) && DestParser.parse(it) == null }
        if (line != null) return line
        val url = extractUrl(text) ?: return null
        return nameFromUrl(url)
    }

    private fun nameFromUrl(url: String): String? {
        val m = Regex("""/maps/place/([^/@?]+)""").find(url) ?: return null
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
    }

    private fun hostOf(u: String) = runCatching { URL(u).host.lowercase() }.getOrDefault("?")

    private fun allowed(u: String): Boolean {
        if (!u.startsWith("https://")) return false
        val h = hostOf(u)
        return h == "goo.gl" || h.endsWith(".goo.gl") || h == "g.co" || googleHost.matches(h)
    }

    /** Busca coordenadas en la propia URL (también dentro de "continue=" de la página de consentimiento). */
    private fun coordsIn(url: String): Pair<Double, Double>? {
        DestParser.parse(url)?.let { return it }
        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrNull()
        if (decoded != null) DestParser.parse(decoded)?.let { return it }
        val cont = runCatching { Uri.parse(url).getQueryParameter("continue") }.getOrNull()
        if (cont != null && cont != url) return coordsIn(cont)
        return null
    }

    private fun readLimited(conn: HttpURLConnection): String {
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (out.size() < MAX_BODY) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    /** Bloqueante: llamar desde un hilo secundario. Lanza IOException si no hay red. */
    @Throws(IOException::class)
    fun resolve(text: String): Result? {
        val guess = guessName(text)
        DestParser.parse(text)?.let { return Result(it.first, it.second, guess) }

        var url = extractUrl(text) ?: return null
        for (hop in 0 until MAX_HOPS) {
            coordsIn(url)?.let {
                AppLog.i(TAG, "Coordenadas obtenidas tras $hop redirecciones (${hostOf(url)})")
                return Result(it.first, it.second, guess ?: nameFromUrl(url))
            }
            if (!allowed(url)) {
                AppLog.w(TAG, "Enlace no permitido o sin coordenadas: ${hostOf(url)}")
                return null
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    url = URL(URL(url), loc).toString()
                    continue
                }
                if (code == 200) {
                    DestParser.parse(readLimited(conn))?.let {
                        AppLog.i(TAG, "Coordenadas leídas de la página (${hostOf(url)})")
                        return Result(it.first, it.second, guess ?: nameFromUrl(url))
                    }
                }
                AppLog.w(TAG, "Sin coordenadas (HTTP $code, ${hostOf(url)})")
                return null
            } finally {
                conn.disconnect()
            }
        }
        AppLog.w(TAG, "Demasiadas redirecciones")
        return null
    }
}
