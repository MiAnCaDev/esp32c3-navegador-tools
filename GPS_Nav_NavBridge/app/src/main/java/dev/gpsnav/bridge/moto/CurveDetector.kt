package dev.gpsnav.bridge.moto

import kotlin.math.abs

/** Una curva detectada: la inclinación máxima y dónde ocurrió. peakLean: + izquierda, − derecha (grados). */
class Curve(
    val startMs: Long, val durationMs: Long, val peakLean: Double,
    val speedMs: Double, val lat: Double, val lon: Double
)

/**
 * Detecta curvas a partir de la inclinación: empieza al superar [ENTER_DEG] y acaba cuando baja de [EXIT_DEG] durante
 * [EXIT_HOLD_MS] o cambia de lado (una "S" cuenta como dos curvas). Solo se cuentan las de al menos [MIN_PEAK_DEG] y
 * [MIN_DURATION_MS] para no apuntar sacudidas ni cambios de carril.
 */
class CurveDetector(private val onCurve: (Curve) -> Unit) {
    companion object {
        const val ENTER_DEG = 12.0
        const val EXIT_DEG = 8.0
        const val FLIP_DEG = 5.0              // lado contrario a partir de aquí = cambio de curva
        const val EXIT_HOLD_MS = 600L
        const val MIN_PEAK_DEG = 15.0
        const val MIN_DURATION_MS = 1200L
    }

    private var inCurve = false
    private var startMs = 0L
    private var peak = 0.0
    private var peakSpeed = 0.0
    private var apexLat = 0.0
    private var apexLon = 0.0
    private var belowSince = 0L

    fun reset() { inCurve = false; peak = 0.0; belowSince = 0 }

    /** Llamar con cada muestra de inclinación (tMs en milisegundos, cualquier origen pero creciente). */
    fun update(tMs: Long, lean: Double, speedMs: Double, lat: Double, lon: Double) {
        if (!inCurve) {
            if (abs(lean) >= ENTER_DEG) {
                inCurve = true; startMs = tMs; peak = lean; peakSpeed = speedMs
                apexLat = lat; apexLon = lon; belowSince = 0
            }
            return
        }
        if (abs(lean) > abs(peak)) { peak = lean; peakSpeed = speedMs; apexLat = lat; apexLon = lon }

        val flipped = lean * peak < 0 && abs(lean) >= FLIP_DEG
        if (abs(lean) < EXIT_DEG) { if (belowSince == 0L) belowSince = tMs } else belowSince = 0L
        val ended = belowSince != 0L && tMs - belowSince >= EXIT_HOLD_MS

        if (flipped || ended) {
            val end = if (belowSince != 0L) belowSince else tMs      // la curva acaba cuando bajó de EXIT_DEG
            val dur = end - startMs
            if (abs(peak) >= MIN_PEAK_DEG && dur >= MIN_DURATION_MS) onCurve(Curve(startMs, dur, peak, peakSpeed, apexLat, apexLon))
            inCurve = false; peak = 0.0; belowSince = 0
            if (flipped && abs(lean) >= ENTER_DEG) update(tMs, lean, speedMs, lat, lon)   // empieza otra al otro lado
        }
    }
}
