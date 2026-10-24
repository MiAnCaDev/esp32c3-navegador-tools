package dev.gpsnav.bridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import dev.gpsnav.bridge.notif.HandlerRegistry
import dev.gpsnav.bridge.notif.IconProcessor
import dev.gpsnav.bridge.notif.NavNotificationListener
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pruebas manuales del ESP32 desde la app (sección "PRUEBAS DEL ESP32").
 *
 *  - GPS:     dibuja una flecha de maniobra, la manda como ICONO y manda el JSON {"t":"nav",...},
 *             exactamente igual que hace GoogleMapsHandler con una notificación real.
 *  - Brújula: manda {"t":"dest",...} con el ángulo elegido (sustituye al cálculo real mientras dura).
 *
 * No hace falta tocar el firmware. Todo se ejecuta en el hilo principal (los botones y los Handler).
 * Al terminar, se vuelve a enviar el estado real (ruta de Maps en curso / destino guardado).
 */
object TestSignals {

    const val GPS_SECONDS = 12
    const val COMPASS_SECONDS = 12

    private val main = Handler(Looper.getMainLooper())
    private const val S = IconProcessor.SIZE

    /** true mientras dura una prueba GPS: GoogleMapsHandler ignora las notificaciones reales. */
    @Volatile var gpsActive = false
        private set

    @Volatile var compassActive = false
        private set

    // ============================================================ GPS
    enum class Maneuver(val label: String, val instr: String) {
        STRAIGHT("Seguir recto", "Continua recto por Calle Mayor"),
        TURN_LEFT("Giro a la izquierda", "Gira a la izquierda en Calle Mayor"),
        TURN_RIGHT("Giro a la derecha", "Gira a la derecha en Calle Mayor"),
        UTURN("Cambio de sentido", "Haz un cambio de sentido"),
        KEEP_LEFT("Mantente a la izquierda", "Mantente a la izquierda para tomar la salida"),
        KEEP_RIGHT("Mantente a la derecha", "Mantente a la derecha para tomar la salida"),
        ROUNDABOUT_1("Rotonda · 1.ª salida", "En la rotonda toma la primera salida"),
        ROUNDABOUT_2("Rotonda · 2.ª salida", "En la rotonda toma la segunda salida hacia Avenida de la Constitucion"),
        ROUNDABOUT_3("Rotonda · 3.ª salida", "En la rotonda toma la tercera salida"),
    }

    private var savedNav = "-"
    private var savedIcon: Bitmap? = null
    private val endGpsRun = Runnable { endGps() }

    /** Devuelve null si todo fue bien, o el motivo del fallo para mostrarlo al usuario. */
    fun startGps(m: Maneuver, meters: Int = 150): String? {
        notReady()?.let { return it }
        if (gpsActive) main.removeCallbacks(endGpsRun)
        else { savedNav = BridgeHub.lastNav; savedIcon = BridgeHub.lastIcon }
        gpsActive = true

        val packed = IconProcessor.pack(drawManeuver(m))
        BridgeHub.sink.send(Frame.icon(0, S, S, packed))
        val nav = JSONObject().put("t", "nav").put("instr", m.instr)
            .put("action", "test_${m.name.lowercase()}").put("distance", meters)
        BridgeHub.sink.send(Frame.json(nav))

        BridgeHub.lastIcon = IconProcessor.preview(packed)
        BridgeHub.lastNav = "PRUEBA: ${m.label} | $meters m"
        AppLog.i("Test", "GPS: ${m.name} ($meters m) durante $GPS_SECONDS s")
        main.postDelayed(endGpsRun, GPS_SECONDS * 1000L)
        return null
    }

    fun endGps() {
        main.removeCallbacks(endGpsRun)
        if (!gpsActive) return
        gpsActive = false
        BridgeHub.lastNav = savedNav
        BridgeHub.lastIcon = savedIcon
        // Limpia la pantalla del ESP32 y, si hay una ruta real en curso, la reenvía.
        BridgeHub.sink.send(Frame.json(JSONObject().put("t", "nav")))
        HandlerRegistry.linkReady()
        NavNotificationListener.instance?.replayActive()
        AppLog.i("Test", "GPS: fin de la prueba")
    }

    // ============================================================ BRÚJULA
    class CompassCase(val label: String, val rel: Int, val dist: Int, val spin: Boolean = false)

    val compassCases = listOf(
        CompassCase("Delante (0°)", 0, 420),
        CompassCase("Derecha (90°)", 90, 420),
        CompassCase("Detrás (180°)", 180, 420),
        CompassCase("Izquierda (−90°)", -90, 420),
        CompassCase("Diagonal derecha (45°)", 45, 1500),
        CompassCase("Diagonal izquierda (−45°)", -45, 1500),
        CompassCase("Has llegado (5 m)", 0, 5),
        CompassCase("Giro completo (animación)", 0, 420, spin = true),
    )

    private var spinStep = 0
    private val endCompassRun = Runnable { endCompass() }
    private val spinTick = object : Runnable {
        override fun run() {
            if (!compassActive) return
            pushDest(-180 + spinStep * 15, 420)
            spinStep++
            if (spinStep > 24) endCompass() else main.postDelayed(this, 400)
        }
    }

    fun startCompass(c: CompassCase): String? {
        notReady()?.let { return it }
        main.removeCallbacks(spinTick); main.removeCallbacks(endCompassRun)
        compassActive = true
        AppLog.i("Test", "Brújula: '${c.label}' rel=${c.rel} d=${c.dist}")
        if (c.spin) {
            spinStep = 0
            spinTick.run()
        } else {
            pushDest(c.rel, c.dist)
            main.postDelayed(endCompassRun, COMPASS_SECONDS * 1000L)
        }
        return null
    }

    fun endCompass() {
        main.removeCallbacks(spinTick); main.removeCallbacks(endCompassRun)
        if (!compassActive) return
        compassActive = false
        Destination.testOverride = null
        // Restablece ya el estado real (si no, lo haría el envío periódico de 1 s).
        BridgeHub.sink.send(Frame.json(Destination.frame()))
        AppLog.i("Test", "Brújula: fin de la prueba")
    }

    private fun pushDest(rel: Int, dist: Int) {
        val o = JSONObject().put("t", "dest").put("set", 1).put("fix", 1).put("ok", 1)
            .put("rel", rel).put("d", dist).put("name", "Prueba")
        Destination.testOverride = o            // el envío periódico de BridgeService lo mantiene vivo
        BridgeHub.sink.send(Frame.json(o))
    }

    // ============================================================ comunes
    private fun notReady(): String? =
        if (BridgeHub.link?.ready == true) null
        else "El ESP32 no está conectado. Pulsa «Iniciar» y espera a ver «Conectado»."

    // ============================================================ dibujo de las flechas
    // Se dibujan en blanco sobre fondo transparente (como las de Maps) y pasan por IconProcessor.pack(),
    // así que el ESP32 recibe exactamente el mismo tipo de imagen que con una ruta real.
    private fun drawManeuver(m: Maneuver): Bitmap {
        val bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 11f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val dim = Paint(bold).apply { strokeWidth = 5f; alpha = 90 }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

        when (m) {
            Maneuver.STRAIGHT -> {
                c.drawPath(Path().apply { moveTo(48f, 90f); lineTo(48f, 34f) }, bold)
                head(c, 48f, 34f, 0f, 24f, 18f, fill)
            }
            Maneuver.TURN_RIGHT, Maneuver.TURN_LEFT -> mirrored(c, m == Maneuver.TURN_LEFT) {
                c.drawPath(Path().apply {
                    moveTo(34f, 90f); lineTo(34f, 60f); quadTo(34f, 36f, 58f, 36f); lineTo(60f, 36f)
                }, bold)
                head(c, 60f, 36f, 90f, 22f, 17f, fill)
            }
            Maneuver.UTURN -> {
                c.drawPath(Path().apply {
                    moveTo(60f, 90f); lineTo(60f, 44f); cubicTo(60f, 12f, 28f, 12f, 28f, 44f); lineTo(28f, 56f)
                }, bold)
                head(c, 28f, 56f, 180f, 22f, 17f, fill)
            }
            Maneuver.KEEP_RIGHT, Maneuver.KEEP_LEFT -> mirrored(c, m == Maneuver.KEEP_LEFT) {
                c.drawPath(Path().apply { moveTo(48f, 62f); lineTo(24f, 30f) }, dim)            // rama que NO se toma
                c.drawPath(Path().apply { moveTo(48f, 90f); lineTo(48f, 62f); lineTo(68f, 30f) }, bold)
                head(c, 68f, 30f, 32f, 20f, 15f, fill)
            }
            Maneuver.ROUNDABOUT_1 -> roundabout(c, 90f, bold, dim, fill)
            Maneuver.ROUNDABOUT_2 -> roundabout(c, 0f, bold, dim, fill)
            Maneuver.ROUNDABOUT_3 -> roundabout(c, -90f, bold, dim, fill)
        }
        return bmp
    }

    /** exitDeg respecto a la entrada: 90 = derecha, 0 = recto, -90 = izquierda (conducción por la derecha). */
    private fun roundabout(c: Canvas, exitDeg: Float, bold: Paint, dim: Paint, fill: Paint) {
        val cx = 48f; val cy = 50f; val r = 18f
        c.drawCircle(cx, cy, r, dim)
        for (a in floatArrayOf(-90f, 0f, 90f)) {                       // salidas no usadas, tenues
            if (a == exitDeg) continue
            val t = Math.toRadians(a.toDouble()); val sx = sin(t).toFloat(); val sy = -cos(t).toFloat()
            c.drawLine(cx + sx * r, cy + sy * r, cx + sx * (r + 10f), cy + sy * (r + 10f), dim)
        }
        c.drawLine(cx, S - 4f, cx, cy + r, bold)                         // entrada por abajo
        // En drawArc 0° = las 3 en punto y el sentido positivo es horario; 90° = abajo. Antihorario = negativo.
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 90f, -(180f - exitDeg), false, bold)
        val t = Math.toRadians(exitDeg.toDouble()); val sx = sin(t).toFloat(); val sy = -cos(t).toFloat()
        val x1 = cx + sx * (r + 8f); val y1 = cy + sy * (r + 8f)
        c.drawLine(cx + sx * r, cy + sy * r, x1, y1, bold)
        head(c, x1, y1, exitDeg, 16f, 13f, fill)
    }

    private fun mirrored(c: Canvas, flip: Boolean, draw: () -> Unit) {
        c.save()
        if (flip) c.scale(-1f, 1f, S / 2f, 0f)
        draw()
        c.restore()
    }

    /** Punta triangular con base en (bx,by) y rumbo headingDeg (0 = arriba, + = derecha). */
    private fun head(c: Canvas, bx: Float, by: Float, headingDeg: Float, len: Float, half: Float, p: Paint) {
        val r = Math.toRadians(headingDeg.toDouble())
        val dx = sin(r).toFloat(); val dy = -cos(r).toFloat()
        val px = cos(r).toFloat(); val py = sin(r).toFloat()
        c.drawPath(Path().apply {
            moveTo(bx + dx * len, by + dy * len)
            lineTo(bx - px * half, by - py * half)
            lineTo(bx + px * half, by + py * half)
            close()
        }, p)
    }
}
