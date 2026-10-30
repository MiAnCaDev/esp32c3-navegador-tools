package dev.gpsnav.bridge.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SustainedLeanTest {
    private fun feed(s: SustainedLean, n: Int, lean: Double, valid: Boolean = true): Double {
        var best = 0.0
        repeat(n) { val r = s.update(lean, valid); if (abs(r) > abs(best)) best = r }
        return best
    }

    @Test
    fun shortSpike_isIgnored() {
        val s = SustainedLean(15)
        feed(s, 50, 0.0)
        assertEquals(0.0, feed(s, 3, 55.0), 0.0)        // pico de 0,06 s (bache / ruido del giroscopio)
        assertEquals(0.0, feed(s, 50, 0.0), 0.0)
    }

    @Test
    fun sustainedLean_isCounted_withItsSign() {
        assertEquals(38.0, feed(SustainedLean(15), 20, 38.0), 0.01)
        assertEquals(-44.0, feed(SustainedLean(15), 30, -44.0), 0.01)
    }

    @Test
    fun invalidData_neverCounts() {
        assertEquals(0.0, feed(SustainedLean(15), 40, 30.0, valid = false), 0.0)
    }

    @Test
    fun matchesTheExpectedValueOnRandomData() {
        // Debe devolver exactamente la menor inclinación (en módulo) de la ventana si todas son del mismo lado, y 0 si no.
        val s = SustainedLean(15); val rnd = Random(3); val hist = ArrayList<Double>()
        repeat(5000) {
            val x = 4 + rnd.nextGaussian() * 6; hist.add(x)
            val r = s.update(x, true)
            if (hist.size >= 15) {
                val w = hist.takeLast(15)
                val expected = when { w.all { it > 0 } -> w.minOrNull()!!; w.all { it < 0 } -> w.maxOrNull()!!; else -> 0.0 }
                assertTrue("r=$r esperado=$expected", abs(r - expected) < 1e-9)
            }
        }
    }
}
