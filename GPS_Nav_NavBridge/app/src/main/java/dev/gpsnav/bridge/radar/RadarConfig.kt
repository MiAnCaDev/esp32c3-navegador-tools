package dev.gpsnav.bridge.radar

import android.content.Context

/**
 * Ajustes de los avisos de radar (editables desde la app).
 *
 * Según la velocidad se elige una "franja":
 *   lenta  : por debajo de [slowBelow]            (por defecto < 40 km/h)
 *   media  : entre [slowBelow] y [fastAbove]      (40 - 90 km/h; el hueco 80-90 de la especificación cae aquí)
 *   rápida : por encima de [fastAbove]            (> 90 km/h)
 *
 * Para cada franja hay dos distancias independientes:
 *   alertX : a qué distancia salta el 1.er aviso (el 2.º salta a la mitad)
 *   showX  : por debajo de qué distancia se escriben los metros en la pantalla de alerta
 *
 * Actualización de la distancia: el móvil solo manda un mensaje nuevo cuando el valor mostrado cambia, y el valor
 * se redondea hacia arriba a múltiplos de [stepFast]/[stepMid]/[stepSlow] metros según la franja (100 / 50 / 20).
 *
 * Referencia de antelación: 1000 m a 120 km/h ≈ 30 s · 400 m a 60 km/h ≈ 24 s · 180 m a 30 km/h ≈ 22 s.
 */
data class RadarConfig(
    val enabled: Boolean = true,
    val slowBelow: Int = 40,
    val fastAbove: Int = 90,
    val alertFast: Int = 1000, val alertMid: Int = 400, val alertSlow: Int = 180,
    val showFast: Int = 1000, val showMid: Int = 400, val showSlow: Int = 180,
    val alertSeconds: Int = 5,
    /** Cada cuántos metros se actualiza la distancia en el ESP32 (menos mensajes = menos carga). */
    val stepFast: Int = 100, val stepMid: Int = 50, val stepSlow: Int = 20,
    /** Margen en km/h sobre el límite del radar antes de pintar el borde rojo (0 = en cuanto lo superes). */
    val overMargin: Int = 0,
    /** Consultar por internet (OpenStreetMap) el límite de la vía en el radar cuando el radar no lo trae. */
    val roadLimits: Boolean = true,
    /** Borde rojo en el ESP32 al superar el límite de la vía en el radar (se puede apagar sin quitar los avisos). */
    val overspeedAlert: Boolean = true,
    /** Tras la alerta roja, mostrar en la pantalla normal los metros que faltan hasta el radar (hasta pasarlo). */
    val idleDistance: Boolean = true,
) {
    fun band(kmh: Float) = if (kmh > fastAbove) 2 else if (kmh >= slowBelow) 1 else 0
    fun alertDist(kmh: Float) = when (band(kmh)) { 2 -> alertFast; 1 -> alertMid; else -> alertSlow }
    fun showDist(kmh: Float) = when (band(kmh)) { 2 -> showFast; 1 -> showMid; else -> showSlow }
    fun step(kmh: Float) = when (band(kmh)) { 2 -> stepFast; 1 -> stepMid; else -> stepSlow }
    fun maxAlertDist() = maxOf(alertFast, alertMid, alertSlow)

    /** Corrige valores absurdos (la app nunca guarda algo que rompa el aviso). */
    fun sanitized(): RadarConfig {
        val lo = slowBelow.coerceIn(5, 120)
        val hi = fastAbove.coerceIn(lo + 1, 200)
        fun d(v: Int) = v.coerceIn(50, MAX_DIST)
        return copy(
            slowBelow = lo, fastAbove = hi,
            alertFast = d(alertFast), alertMid = d(alertMid), alertSlow = d(alertSlow),
            showFast = d(showFast), showMid = d(showMid), showSlow = d(showSlow),
            alertSeconds = alertSeconds.coerceIn(2, 15),
            stepFast = stepFast.coerceIn(10, 1000), stepMid = stepMid.coerceIn(10, 1000),
            stepSlow = stepSlow.coerceIn(10, 1000),
            overMargin = overMargin.coerceIn(0, 30),
        )
    }

    companion object {
        const val MAX_DIST = 4000          // límite del índice espacial (ver RadarDb)
        private const val SP = "espnav"

        @Volatile var current = RadarConfig()
            private set

        fun load(ctx: Context) {
            val p = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            val d = RadarConfig()
            current = RadarConfig(
                enabled = p.getBoolean("radar_on", d.enabled),
                slowBelow = p.getInt("radar_slow", d.slowBelow), fastAbove = p.getInt("radar_fast", d.fastAbove),
                alertFast = p.getInt("radar_af", d.alertFast), alertMid = p.getInt("radar_am", d.alertMid),
                alertSlow = p.getInt("radar_as", d.alertSlow),
                showFast = p.getInt("radar_sf", d.showFast), showMid = p.getInt("radar_sm", d.showMid),
                showSlow = p.getInt("radar_ss", d.showSlow),
                alertSeconds = p.getInt("radar_sec", d.alertSeconds),
                stepFast = p.getInt("radar_stf", d.stepFast), stepMid = p.getInt("radar_stm", d.stepMid),
                stepSlow = p.getInt("radar_sts", d.stepSlow),
                overMargin = p.getInt("radar_ovm", d.overMargin),
                roadLimits = p.getBoolean("radar_rl", d.roadLimits),
                overspeedAlert = p.getBoolean("radar_ovr_on", d.overspeedAlert),
                idleDistance = p.getBoolean("radar_idle", d.idleDistance),
            ).sanitized()
        }

        fun save(ctx: Context, c: RadarConfig) {
            val s = c.sanitized()
            current = s
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putBoolean("radar_on", s.enabled)
                .putInt("radar_slow", s.slowBelow).putInt("radar_fast", s.fastAbove)
                .putInt("radar_af", s.alertFast).putInt("radar_am", s.alertMid).putInt("radar_as", s.alertSlow)
                .putInt("radar_sf", s.showFast).putInt("radar_sm", s.showMid).putInt("radar_ss", s.showSlow)
                .putInt("radar_sec", s.alertSeconds)
                .putInt("radar_stf", s.stepFast).putInt("radar_stm", s.stepMid).putInt("radar_sts", s.stepSlow)
                .putInt("radar_ovm", s.overMargin)
                .putBoolean("radar_rl", s.roadLimits)
                .putBoolean("radar_ovr_on", s.overspeedAlert)
                .putBoolean("radar_idle", s.idleDistance)
                .apply()
        }
    }
}
