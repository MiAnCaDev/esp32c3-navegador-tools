package dev.gpsnav.bridge

import android.content.Context
import android.location.Location
import android.os.SystemClock
import org.json.JSONObject
import java.text.Normalizer

/**
 * Destino de la brújula del ESP32. El móvil calcula el ángulo (respecto a la dirección de marcha) y la
 * distancia en línea recta, y manda el resultado cada segundo en un mensaje {"t":"dest",...}.
 */
object Destination {
    class Dest(val lat: Double, val lon: Double, val name: String)

    @Volatile var current: Dest? = null
        private set

    /** Si no es null, sustituye al cálculo real (lo usa TestSignals para probar la brújula del ESP32). */
    @Volatile var testOverride: JSONObject? = null

    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastLocationAt = 0L
    private var lastCourse: Float? = null          // último rumbo de marcha válido (grados desde el norte)

    private const val MIN_SPEED_MS = 1.4f          // ~5 km/h: por debajo de esto el rumbo del GPS no es fiable
    private const val FIX_STALE_MS = 10_000L       // sin posición nueva en este tiempo = "sin GPS"

    fun load(ctx: Context) { current = Prefs.destination(ctx) }

    fun set(ctx: Context, d: Dest?) {
        current = d
        Prefs.setDestination(ctx, d)
        AppLog.i("Dest", if (d == null) "Destino quitado" else "Destino fijado: '${d.name}'")
    }

    fun onLocation(loc: Location) {
        lastLocation = loc
        lastLocationAt = SystemClock.elapsedRealtime()
    }

    /** Última posición del servicio si es reciente (para "usar mi ubicación actual"). */
    fun freshLocation(maxAgeMs: Long = 15_000L): Location? {
        val l = lastLocation ?: return null
        return if (SystemClock.elapsedRealtime() - lastLocationAt < maxAgeMs) l else null
    }

    fun cleanName(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val t = d.filter { it.code in 32..126 }.replace(Regex("\\s+"), " ").trim().take(24)
        return t.ifBlank { "Destino" }
    }

    /** Mensaje para el ESP32. Se llama una vez por segundo. */
    fun frame(): JSONObject {
        testOverride?.let { return it }
        val o = JSONObject().put("t", "dest")
        val d = current ?: return o.put("set", 0)
        o.put("set", 1).put("name", d.name)

        val loc = lastLocation
        val fresh = loc != null && SystemClock.elapsedRealtime() - lastLocationAt < FIX_STALE_MS
        if (loc == null || !fresh) return o.put("fix", 0).put("ok", 0)

        val target = Location("dest").apply { latitude = d.lat; longitude = d.lon }
        o.put("fix", 1).put("d", Math.round(loc.distanceTo(target)))

        // El rumbo solo se actualiza en movimiento; parado se mantiene el último conocido.
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= MIN_SPEED_MS) lastCourse = loc.bearing
        val course = lastCourse ?: return o.put("ok", 0)   // aún sin rumbo: "Muévete para orientar"

        var rel = loc.bearingTo(target) - course
        while (rel > 180f) rel -= 360f
        while (rel < -180f) rel += 360f
        return o.put("ok", 1).put("rel", Math.round(rel))
    }
}

/** Lee coordenadas de un texto: "40.4168, -3.7038", geo:, o un enlace largo de Google Maps. */
object DestParser {
    private const val N = """-?\d{1,3}(?:\.\d+)?"""
    private val patterns = listOf(
        Regex("""!3d($N)!4d($N)"""),
        Regex("""@($N),($N)"""),
        Regex("""[?&;](?:q|query|ll|destination|daddr|center)=($N)(?:,|%2C)\s*($N)""", RegexOption.IGNORE_CASE),
        Regex("""geo:($N),($N)""", RegexOption.IGNORE_CASE),
        Regex("""^\s*($N)\s*[,;]\s*($N)\s*${'$'}"""),
        Regex("""^\s*($N)\s+($N)\s*${'$'}"""),
    )

    fun parse(text: String): Pair<Double, Double>? {
        val t = text.trim()
        for (r in patterns) {
            val m = r.find(t) ?: continue
            val la = m.groupValues[1].toDoubleOrNull()
            val lo = m.groupValues[2].toDoubleOrNull()
            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) return la to lo
        }
        return null
    }

    /** Los enlaces cortos de Maps necesitan internet para resolverse (la app no lo usa). */
    fun isShortLink(t: String) = t.contains("maps.app.goo.gl") || t.contains("goo.gl/maps") || t.contains("g.co/kgs")
}
