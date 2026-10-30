package dev.gpsnav.bridge.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Prueba el estimador de inclinación con una MOTO SIMULADA: dinámica real de una curva (velocidad angular y fuerza específica
 * que mediría un móvil), vibración, ruido del giroscopio y del GPS, sesgo del giroscopio y el móvil colocado con una orientación
 * aleatoria. Si la física o el código estuvieran mal, aquí se vería.
 *
 * Perfil: 10 s en recta (el estimador aprende el sesgo), luego +40° (izquierda) mantenidos, recta, y −30° (derecha) mantenidos.
 */
class LeanEstimatorTest {
    private val g = 9.80665

    private class Noise(val accel: Double = 1.0, val gyro: Double = 1.0, val gps: Double = 1.0, val bias: Double = 1.0)

    private class Result(val worst: Double, val sumSq: Double, val n: Int, val leftMean: Double, val rightMean: Double) {
        val rms get() = sqrt(sumSq / n)
    }

    private fun rot(ax: DoubleArray, ang: Double): Array<DoubleArray> {
        val n = sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2]); val x = ax[0] / n; val y = ax[1] / n; val z = ax[2] / n
        val c = cos(ang); val s = sin(ang); val t = 1 - c
        return arrayOf(
            doubleArrayOf(t * x * x + c, t * x * y - s * z, t * x * z + s * y),
            doubleArrayOf(t * x * y + s * z, t * y * y + c, t * y * z - s * x),
            doubleArrayOf(t * x * z - s * y, t * y * z + s * x, t * z * z + c)
        )
    }

    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { m[it][0] * v[0] + m[it][1] * v[1] + m[it][2] * v[2] }

    private fun ramp(t: Double, t0: Double, t1: Double, a: Double, b: Double) = a + (b - a) * ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)

    /** Inclinación deseada (grados, + izquierda). */
    private fun leanProfile(t0: Double): Double {
        val t = t0 - 10.0
        return when {
            t < 3 -> 0.0
            t < 4 -> ramp(t, 3.0, 4.0, 0.0, 40.0)
            t < 8 -> 40.0
            t < 9 -> ramp(t, 8.0, 9.0, 40.0, 0.0)
            t < 11 -> 0.0
            t < 12 -> ramp(t, 11.0, 12.0, 0.0, -30.0)
            t < 16 -> -30.0
            t < 17 -> ramp(t, 16.0, 17.0, -30.0, 0.0)
            else -> 0.0
        }
    }

    private fun simulate(seed: Long, speed: Double, noise: Noise = Noise(), learnStraights: Boolean = true): Result {
        val rnd = Random(seed)
        // cómo va colocado el móvil: una rotación aleatoria entre los ejes de la moto y los del móvil
        val r = rot(doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()), rnd.nextDouble() * 2 * PI)
        val est = LeanEstimator()
        val bias = doubleArrayOf(0.02, -0.015, 0.01)      // 1,1 °/s: más sesgo que un móvil normal (0,2-0,5 °/s)
        var gps = 0.0; var worst = 0.0; var ss = 0.0; var n = 0
        var leftSum = 0.0; var leftN = 0; var rightSum = 0.0; var rightN = 0
        val dt = 0.02; var t = 0.0; var phiPrev = 0.0
        while (t < 30.0) {
            val phiDeg = leanProfile(t); val phi = Math.toRadians(phiDeg)
            val rollRate = (phi - phiPrev) / dt; phiPrev = phi
            val v = if (t < 1.0) speed * t else speed
            val psiDot = g * tan(phi) / max(v, 1.0)
            val up = doubleArrayOf(0.0, -sin(phi), cos(phi)); val hor = doubleArrayOf(0.0, cos(phi), sin(phi))   // vertical real y horizontal, en ejes moto
            val w = doubleArrayOf(-rollRate, psiDot * up[1], psiDot * up[2])
            val f = doubleArrayOf(if (t < 1.0) speed else 0.0, v * psiDot * hor[1] + g * up[1], v * psiDot * hor[2] + g * up[2])
            val vib = 2.0 * sin(2 * PI * 27.0 * t)
            for (i in 0..2) {
                w[i] += noise.bias * bias[i] + rnd.nextGaussian() * 0.01 * noise.gyro     // giroscopio: 0,6 °/s por muestra
                f[i] += rnd.nextGaussian() * 1.5 * noise.accel + vib * noise.accel          // vibración del motor
            }
            val wp = mul(r, w); val fp = mul(r, f)
            val tNs = (t * 1e9).toLong()
            est.onAccel(tNs, fp[0], fp[1], fp[2])
            if (t % 1.0 < dt) gps = (v + rnd.nextGaussian() * 0.15 * noise.gps).coerceAtLeast(0.0)     // GPS a 1 Hz
            val straight = learnStraights && abs(psiDot) < 0.01 && t > 1.0                              // lo que daría el rumbo del GPS
            val lean = est.onGyro(tNs, wp[0], wp[1], wp[2], gps, straight)
            val leftPlateau = t in 15.5..18.0; val rightPlateau = t in 23.5..26.0
            if (leftPlateau || rightPlateau) {
                val e = lean - phiDeg
                worst = max(worst, abs(e)); ss += e * e; n++
                if (leftPlateau) { leftSum += lean; leftN++ } else { rightSum += lean; rightN++ }
            }
            t += dt
        }
        return Result(worst, ss, n, leftSum / leftN, rightSum / rightN)
    }

    /** Peor caso y error típico sobre 40 colocaciones aleatorias del móvil. */
    private fun sweep(speed: Double, noise: Noise = Noise(), learnStraights: Boolean = true): Pair<Double, Double> {
        var worst = 0.0; var ss = 0.0; var n = 0
        for (s in 1L..40L) { val r = simulate(s * 7919, speed, noise, learnStraights); worst = max(worst, r.worst); ss += r.sumSq; n += r.n }
        return worst to sqrt(ss / n)
    }

    @Test
    fun noiseFree_theMathIsExact() {
        // Sin ruido ni sesgo el estimador debe acertar casi al grado: valida la física y la implementación.
        for (speed in listOf(12.0, 20.0, 30.0)) {
            val (worst, _) = sweep(speed, Noise(0.0, 0.0, 0.0, 0.0))
            assertTrue("a $speed m/s el error sin ruido fue $worst°", worst < 0.3)
        }
    }

    @Test
    fun withRealisticNoise_errorStaysSmall_forAnyPhoneOrientation() {
        // 43, 72 y 108 km/h. Medido: error típico ≈0,75° y peor instante ≈3°.
        for (speed in listOf(12.0, 20.0, 30.0)) {
            val (worst, rms) = sweep(speed)
            assertTrue("a $speed m/s: error típico $rms°", rms < 1.5)
            assertTrue("a $speed m/s: peor instante $worst°", worst < 3.5)
        }
    }

    @Test
    fun leftIsPositive_rightIsNegative() {
        val r = simulate(1, 20.0)
        assertEquals(40.0, r.leftMean, 3.0)
        assertEquals(-30.0, r.rightMean, 3.0)
    }

    @Test
    fun learningTheBiasOnStraightsHelps() {
        val withLearning = sweep(20.0, Noise(gyro = 0.0), learnStraights = true).second
        val without = sweep(20.0, Noise(gyro = 0.0), learnStraights = false).second
        assertTrue("con aprendizaje en rectas ($withLearning°) debe ser mejor que sin él ($without°)", withLearning < without)
    }

    @Test
    fun belowMinimumSpeed_isInvalidAndZero() {
        val est = LeanEstimator()
        var t = 0L; var lean = 99.0
        for (i in 0 until 200) { t += 20_000_000L; est.onAccel(t, 0.0, 0.0, 9.8); lean = est.onGyro(t, 0.0, 0.3, 0.0, 2.0) }   // 7 km/h girando
        assertFalse(est.valid)
        assertEquals(0.0, lean, 1.0)
    }
}
