package dev.gpsnav.bridge.radar

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometría ligera (válida para distancias de pocos km). */
object Geo {
    private const val R = 6_371_000.0
    private fun rad(d: Double) = Math.toRadians(d)

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = rad(lat2 - lat1)
        val dx = rad(lon2 - lon1) * cos(rad((lat1 + lat2) / 2))
        return R * sqrt(dx * dx + dy * dy)
    }

    /** Rumbo inicial de 1 a 2, 0..360 (0 = norte). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1); val p2 = rad(lat2); val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Diferencia absoluta entre dos rumbos, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Texto seguro para la fuente de la pantalla del ESP32 (sin acentos ni caracteres raros). */
    fun ascii(s: String, max: Int = 28): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.map { if (it.code in 32..126) it else ' ' }.joinToString("").trim().take(max)
    }
}
