package dev.gpsnav.bridge.moto

import kotlin.math.abs
import kotlin.math.min

/**
 * Inclinación "mantenida": la menor inclinación (en valor absoluto) de las últimas [windowSamples] muestras, y solo si todas
 * son del mismo lado y válidas; si no, 0. Sirve para el máximo del modo moto: un bache o un pico de ruido del giroscopio dura
 * unas décimas de segundo y no debe aparecer como "inclinación máxima". A 50 Hz, 15 muestras = 0,3 s.
 */
class SustainedLean(private val windowSamples: Int = 15) {
    private val ring = DoubleArray(windowSamples)
    private var n = 0
    private var i = 0

    fun reset() { n = 0; i = 0 }

    /** Devuelve la inclinación mantenida con signo (+ izquierda, − derecha), o 0. */
    fun update(lean: Double, valid: Boolean): Double {
        ring[i] = if (valid) lean else 0.0
        i = (i + 1) % windowSamples
        if (n < windowSamples) n++
        if (n < windowSamples) return 0.0
        val sign = if (ring[0] >= 0) 1.0 else -1.0
        var minAbs = Double.MAX_VALUE
        for (x in ring) {
            if (x * sign <= 0.0) return 0.0
            minAbs = min(minAbs, abs(x))
        }
        return sign * minAbs
    }
}
