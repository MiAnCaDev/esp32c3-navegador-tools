package dev.gpsnav.bridge.moto

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Un punto del recorrido (1 por fix de GPS).
 * lean = inclinación en ese instante, leanPeak = la mayor del último segundo (para no perder picos entre fixes);
 * ambas en grados, + izquierda / − derecha. latG = aceleración lateral en g, longG = longitudinal (+ acelerando, − frenando).
 */
class TrackPoint(
    val tMs: Long, val lat: Double, val lon: Double, val alt: Double,
    val speedMs: Double, val bearing: Double, val acc: Double,
    val lean: Double, val leanPeak: Double, val latG: Double, val longG: Double
)

/** Resumen de un viaje guardado. */
class TripSummary(
    val id: String, val name: String, val startMs: Long, val endMs: Long,
    val distanceM: Double, val movingMs: Long, val maxSpeedMs: Double,
    val maxLeanLeft: Double, val maxLeanRight: Double,
    val maxAccelG: Double, val maxBrakeG: Double, val maxLatG: Double,
    val curves: Int, val points: Int, val elevGainM: Double, val hasLean: Boolean
) {
    val durationMs get() = endMs - startMs
    val avgMovingSpeedMs get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    fun withName(n: String) = TripSummary(id, n, startMs, endMs, distanceM, movingMs, maxSpeedMs, maxLeanLeft, maxLeanRight,
        maxAccelG, maxBrakeG, maxLatG, curves, points, elevGainM, hasLean)

    fun toMeta(): String = buildString {
        fun kv(k: String, v: Any) = append(k).append('=').append(v).append('\n')
        kv("id", id); kv("name", name.replace('\n', ' ').replace('\r', ' '))
        kv("start", startMs); kv("end", endMs); kv("dist", f1(distanceM)); kv("moving", movingMs)
        kv("maxSpeed", f2(maxSpeedMs)); kv("maxLeft", f1(maxLeanLeft)); kv("maxRight", f1(maxLeanRight))
        kv("maxAccel", f2(maxAccelG)); kv("maxBrake", f2(maxBrakeG)); kv("maxLat", f2(maxLatG))
        kv("curves", curves); kv("points", points); kv("elev", f1(elevGainM)); kv("lean", if (hasLean) 1 else 0)
    }

    companion object {
        private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
        private fun f2(d: Double) = String.format(Locale.US, "%.2f", d)

        fun fromMeta(text: String): TripSummary? {
            val m = HashMap<String, String>()
            for (line in text.lines()) { val i = line.indexOf('='); if (i > 0) m[line.substring(0, i)] = line.substring(i + 1) }
            return try {
                TripSummary(
                    m["id"] ?: return null, m["name"] ?: "", m["start"]!!.toLong(), m["end"]!!.toLong(),
                    m["dist"]!!.toDouble(), m["moving"]!!.toLong(), m["maxSpeed"]!!.toDouble(),
                    m["maxLeft"]!!.toDouble(), m["maxRight"]!!.toDouble(), m["maxAccel"]!!.toDouble(),
                    m["maxBrake"]!!.toDouble(), m["maxLat"]!!.toDouble(), m["curves"]!!.toInt(), m["points"]!!.toInt(),
                    m["elev"]!!.toDouble(), m["lean"] == "1"
                )
            } catch (e: Exception) { null }
        }
    }
}

/** Estadísticas que se van acumulando punto a punto (mientras se graba o al reconstruir un viaje del disco). */
class TripStats {
    var startMs = 0L; var endMs = 0L
    var distanceM = 0.0; var movingMs = 0L; var maxSpeedMs = 0.0
    var maxLeft = 0.0; var maxRight = 0.0
    var maxAccelG = 0.0; var maxBrakeG = 0.0; var maxLatG = 0.0
    var curves = 0; var points = 0; var elevGain = 0.0; var hasLean = false
    private var prev: TrackPoint? = null
    private var altRef = Double.NaN

    fun addPoint(p: TrackPoint) {
        if (points == 0) startMs = p.tMs
        endMs = p.tMs; points++
        if (p.speedMs > maxSpeedMs) maxSpeedMs = p.speedMs
        val pr = prev
        if (pr != null) {
            val dt = (p.tMs - pr.tMs) / 1000.0
            if (dt > 0 && dt <= 15) {
                // parado, el GPS "baila": no se cuenta como distancia
                if ((p.speedMs > 1.5 || pr.speedMs > 1.5) && p.acc <= 50) distanceM += haversine(pr.lat, pr.lon, p.lat, p.lon)
                if (p.speedMs > 1.5) movingMs += (dt * 1000).toLong()
            }
        }
        prev = p
        for (l in doubleArrayOf(p.lean, p.leanPeak)) {
            if (l > maxLeft) maxLeft = l
            if (-l > maxRight) maxRight = -l
            if (abs(l) > 0.5) hasLean = true
        }
        if (p.longG > maxAccelG) maxAccelG = p.longG
        if (-p.longG > maxBrakeG) maxBrakeG = -p.longG
        if (p.latG > maxLatG) maxLatG = p.latG
        if (!p.alt.isNaN()) {                                  // desnivel acumulado con histéresis de 3 m (el GPS vibra en vertical)
            if (altRef.isNaN()) altRef = p.alt
            else if (p.alt - altRef > 3) { elevGain += p.alt - altRef; altRef = p.alt }
            else if (p.alt < altRef - 3) altRef = p.alt
        }
    }

    fun addCurve(c: Curve) {
        curves++
        if (c.peakLean > maxLeft) maxLeft = c.peakLean
        if (-c.peakLean > maxRight) maxRight = -c.peakLean
    }

    fun summary(id: String, name: String) = TripSummary(id, name, startMs, endMs, distanceM, movingMs, maxSpeedMs, maxLeft, maxRight,
        maxAccelG, maxBrakeG, maxLatG, curves, points, elevGain, hasLean)
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Formato de línea del archivo del viaje (una línea por dato; se puede escribir sobre la marcha y sobrevive a cierres):
 *   H,1,<id>,<inicio ms>
 *   P,<t>,<lat>,<lon>,<alt>,<vel m/s>,<rumbo>,<precisión>,<inclinación>,<pico>,<g lateral>,<g longitudinal>
 *   C,<t>,<lat>,<lon>,<pico de inclinación>,<vel m/s>,<duración ms>
 * NaN se escribe vacío.
 */
object TripCodec {
    private fun n(d: Double, dec: Int) = if (d.isNaN()) "" else String.format(Locale.US, "%.${dec}f", d)
    private fun d(s: String) = if (s.isEmpty()) Double.NaN else s.toDouble()

    fun header(id: String, startMs: Long) = "H,1,$id,$startMs"

    fun point(p: TrackPoint) = "P,${p.tMs},${n(p.lat, 6)},${n(p.lon, 6)},${n(p.alt, 1)},${n(p.speedMs, 2)},${n(p.bearing, 0)}," +
        "${n(p.acc, 0)},${n(p.lean, 1)},${n(p.leanPeak, 1)},${n(p.latG, 2)},${n(p.longG, 2)}"

    fun curve(c: Curve) = "C,${c.startMs},${n(c.lat, 6)},${n(c.lon, 6)},${n(c.peakLean, 1)},${n(c.speedMs, 2)},${c.durationMs}"

    /** TrackPoint, Curve o null si la línea no vale (cabecera, línea cortada por un cierre...). */
    fun parse(line: String): Any? {
        val f = line.split(',')
        try {
            when (f[0]) {
                "P" -> if (f.size == 12) return TrackPoint(f[1].toLong(), f[2].toDouble(), f[3].toDouble(), d(f[4]), d(f[5]), d(f[6]),
                    d(f[7]), d(f[8]), d(f[9]), d(f[10]), d(f[11]))
                "C" -> if (f.size == 7) return Curve(f[1].toLong(), f[6].toLong(), f[4].toDouble(), f[5].toDouble(), f[2].toDouble(), f[3].toDouble())
            }
        } catch (e: NumberFormatException) { /* línea a medias */ }
        return null
    }
}
