package dev.gpsnav.bridge.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CurveDetectorTest {
    private fun run(profile: List<Triple<Double, Double, (Double) -> Double>>): List<Curve> {
        val found = ArrayList<Curve>()
        val det = CurveDetector { found.add(it) }
        for ((from, to, f) in profile) { var t = from; while (t < to) { det.update((t * 1000).toLong(), f(t), 20.0, 40.0 + t * 1e-4, -3.0); t += 0.02 } }
        return found
    }

    @Test
    fun sCurve_isTwoCurves_wobbleAndLaneChangeAreIgnored() {
        val found = run(listOf(
            Triple(0.0, 2.0) { _ -> 0.0 },
            Triple(2.0, 3.0) { t -> 40.0 * (t - 2.0) },              // sube a +40 (izquierda)
            Triple(3.0, 5.0) { _ -> 40.0 },
            Triple(5.0, 5.4) { t -> 40.0 - 100.0 * (t - 5.0) },      // cruza a 0 y pasa al otro lado en 0,4 s: una "S"
            Triple(5.4, 8.0) { _ -> -35.0 },
            Triple(8.0, 9.0) { t -> -35.0 * (9.0 - t) },
            Triple(9.0, 11.0) { _ -> 0.0 },
            Triple(11.0, 13.0) { t -> 10.0 * sin((t - 11.0) * PI / 2) },   // meneo de 10°: no cuenta
            Triple(13.0, 15.0) { _ -> 0.0 },
            Triple(15.0, 15.5) { _ -> 20.0 },                             // 0,5 s a 20° (cambio de carril): no cuenta
            Triple(15.5, 18.0) { _ -> 0.0 }
        ))
        assertEquals("curvas encontradas", 2, found.size)
        assertEquals(40.0, found[0].peakLean, 0.5)       // izquierda = positivo
        assertEquals(-35.0, found[1].peakLean, 0.5)      // derecha = negativo
        assertTrue(found[0].durationMs > 2500 && found[1].durationMs > 2500)
    }

    @Test
    fun straightRiding_producesNoCurves() {
        assertEquals(0, run(listOf(Triple(0.0, 60.0) { t -> 4.0 * sin(t) })).size)
    }
}
