package dev.gpsnav.bridge.moto

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Inclinación de la moto a partir de los sensores del móvil, SIN calibrar y SIN importar cómo vaya colocado
 * (manillar, depósito, bolsillo de la chaqueta...).
 *
 * Física: en una curva estable la aceleración lateral es  a = v·ψ̇  (v = velocidad, ψ̇ = velocidad de giro sobre la
 * vertical real) y la inclinación cumple  tan φ = a/g.  El acelerómetro NO sirve para medir la inclinación por sí solo
 * (en una curva la "gravedad aparente" va siempre por el eje de la moto, así que el móvil no nota que se inclina), pero
 * sí da ese eje: u = dirección del acelerómetro filtrado.  El giroscopio proyectado sobre u vale  m = ψ̇·cos φ,
 * y de ahí:
 *
 *        sen φ = v·m / g           φ = asin( v·m / g )
 *
 * Solo hacen falta v (GPS), el acelerómetro filtrado y el giroscopio: el resultado no cambia al girar el móvil.
 * Signo: positivo = inclinada a la IZQUIERDA (regla de la mano derecha sobre la vertical), negativo = a la derecha.
 *
 * Precisión: modelo de moto ideal (sin grosor de neumático ni pilotos que cuelgan del lateral), así que es una buena
 * estimación para comparar curvas y trayectos entre sí, no una medida de laboratorio. Por debajo de [MIN_SPEED_MS] no
 * se calcula (el ruido manda).
 */
class LeanEstimator {
    companion object {
        const val G = 9.80665
        const val MIN_SPEED_MS = 4.0          // ~14 km/h
        const val MAX_LEAN_DEG = 70.0
        private const val TAU_ACC = 0.40      // s: filtra vibraciones del motor y baches
        private const val TAU_GYRO = 0.10
        private const val TAU_LEAN = 0.15
        private const val TAU_BIAS = 2.0
        private const val TAU_STRAIGHT = 3.0  // s: aprendizaje del error en rectas
    }

    // acelerómetro filtrado (fuerza específica: apunta "hacia arriba" cuando está quieto)
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
    private var accInit = false
    private var lastAccT = 0L
    // giroscopio filtrado y su sesgo (se aprende cuando la moto está parada)
    private var wx = 0.0; private var wy = 0.0; private var wz = 0.0
    private var bx = 0.0; private var by = 0.0; private var bz = 0.0
    private var lastGyroT = 0L
    // error residual del giro sobre el eje "arriba" (rad/s): en una recta el giro real es 0, así que lo medido es error
    private var mBias = 0.0

    /** Inclinación actual filtrada en grados (+ izquierda, − derecha). */
    @Volatile var leanDeg = 0.0
        private set
    /** false si ahora mismo no se puede calcular (poca velocidad, sin GPS...). */
    @Volatile var valid = false
        private set

    private fun alpha(dt: Double, tau: Double) = dt / (tau + dt)

    fun reset() {
        accInit = false; lastAccT = 0; lastGyroT = 0
        wx = 0.0; wy = 0.0; wz = 0.0; leanDeg = 0.0; valid = false; mBias = 0.0
    }

    /** Muestra del acelerómetro (m/s², ejes del móvil). tNs = marca de tiempo del sensor en nanosegundos. */
    fun onAccel(tNs: Long, x: Double, y: Double, z: Double) {
        if (!accInit) { fx = x; fy = y; fz = z; accInit = true; lastAccT = tNs; return }
        val dt = ((tNs - lastAccT) / 1e9).coerceIn(0.001, 0.25); lastAccT = tNs
        val a = alpha(dt, TAU_ACC)
        fx += (x - fx) * a; fy += (y - fy) * a; fz += (z - fz) * a
    }

    /**
     * Muestra del giroscopio (rad/s, ejes del móvil) y velocidad actual en m/s (0 si no hay GPS reciente).
     * [straight] = el GPS indica que el rumbo no está cambiando (recta): se usa para corregir el sesgo residual del giroscopio.
     * Devuelve la inclinación filtrada en grados.
     */
    fun onGyro(tNs: Long, x: Double, y: Double, z: Double, speedMs: Double, straight: Boolean = false): Double {
        if (lastGyroT == 0L) { lastGyroT = tNs; return leanDeg }
        val dt = ((tNs - lastGyroT) / 1e9).coerceIn(0.001, 0.25); lastGyroT = tNs

        // Sesgo del giroscopio: parado y sin girar, lo que mide es error y se aprende despacio
        if (speedMs < 0.5 && abs(x - bx) < 0.08 && abs(y - by) < 0.08 && abs(z - bz) < 0.08) {
            val ab = alpha(dt, TAU_BIAS)
            bx += (x - bx) * ab; by += (y - by) * ab; bz += (z - bz) * ab
        }
        val ag = alpha(dt, TAU_GYRO)
        wx += (x - bx - wx) * ag; wy += (y - by - wy) * ag; wz += (z - bz - wz) * ag

        val norm = sqrt(fx * fx + fy * fy + fz * fz)
        var target = 0.0
        if (accInit && norm in 4.0..30.0 && speedMs >= MIN_SPEED_MS) {
            val raw = (wx * fx + wy * fy + wz * fz) / norm          // giro sobre el eje "arriba" de la moto
            if (straight) mBias += (raw - mBias) * alpha(dt, TAU_STRAIGHT)
            val m = raw - mBias
            val s = (speedMs * m / G).coerceIn(-0.97, 0.97)
            target = Math.toDegrees(asin(s)).coerceIn(-MAX_LEAN_DEG, MAX_LEAN_DEG)
            valid = true
        } else {
            valid = false                                              // vuelve a 0 suavemente
        }
        val tau = if (valid) TAU_LEAN else 0.3
        leanDeg += (target - leanDeg) * alpha(dt, tau)
        return leanDeg
    }
}
