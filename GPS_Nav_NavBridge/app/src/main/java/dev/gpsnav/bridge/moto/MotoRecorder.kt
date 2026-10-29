package dev.gpsnav.bridge.moto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dev.gpsnav.bridge.AppLog
import dev.gpsnav.bridge.BridgeHub
import dev.gpsnav.bridge.BridgeService
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Medidas de moto con los sensores del móvil. Hay dos usos independientes que comparten los mismos sensores:
 *
 *  - GRABAR UN VIAJE ([startRecording]): ruta, aceleraciones, inclinación y curvas, guardado en el móvil ([TripStore]).
 *  - MODO MOTO ([setMode]): muestra en el ESP32 la inclinación máxima desde que se activó. Se desactiva solo cuando el ESP32 se
 *    desconecta (lo hace BridgeService) y hay que volver a activarlo a mano. No guarda nada.
 *
 * Los sensores y el wake-lock se encienden mientras haya alguno de los dos usos y se apagan cuando no queda ninguno. Funciona
 * dentro del servicio en primer plano de la app ([BridgeService]), que le pasa cada fix de GPS. El wake-lock parcial es necesario:
 * con la pantalla apagada Android deja de entregar muestras del giroscopio.
 *
 * Hilos: sensores por su propio hilo (50 Hz); GPS, estadísticas y UI en el principal; disco en un hilo aparte de baja prioridad.
 */
object MotoRecorder : SensorEventListener {
    private const val G = 9.80665
    private const val SPEED_STALE_MS = 3000L          // sin GPS más de 3 s: no hay velocidad fiable -> sin inclinación

    class Live(
        val recording: Boolean, val modeOn: Boolean,
        val elapsedMs: Long, val speedKmh: Double, val leanDeg: Double, val leanValid: Boolean,
        val maxLeft: Double, val maxRight: Double, val modeLeft: Double, val modeRight: Double,
        val latG: Double, val longG: Double, val distanceM: Double, val curves: Int, val hasLean: Boolean, val gpsAgeMs: Long
    )

    private val main = Handler(Looper.getMainLooper())

    /** Se está grabando un viaje. */
    @Volatile var recording = false
        private set
    /** Modo moto activo (inclinación máxima en el ESP32). */
    @Volatile var modeOn = false
        private set
    /** Hay algo en marcha que necesita sensores y GPS. */
    val active get() = recording || modeOn
    private var running = false                         // sensores y wake-lock encendidos

    private var store: TripStore? = null
    private var tripId = ""
    private var writer: BufferedWriter? = null
    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() } }
    }

    private var sensorThread: HandlerThread? = null
    private var sensors: SensorManager? = null
    private var wake: PowerManager.WakeLock? = null
    private val est = LeanEstimator()
    private val detector = CurveDetector { c -> main.post { onCurve(c) } }
    private val sustained = SustainedLean()
    private var stats = TripStats()
    @Volatile private var hasLean = false

    // inclinación máxima del modo moto desde que se activó (grados, positivos)
    @Volatile private var modeMaxLeft = 0.0
    @Volatile private var modeMaxRight = 0.0

    // compartido entre el hilo de sensores y el principal
    @Volatile private var speedMs = 0.0
    @Volatile private var speedAt = 0L
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0
    @Volatile private var straight = false
    private val peakLock = Any()
    private var peakSigned = 0.0

    // solo hilo principal
    private var startElapsed = 0L
    private var prevSpeed = Double.NaN
    private var prevT = 0L
    private var longGf = 0.0
    private val bearings = ArrayDeque<Pair<Long, Double>>()

    // ------------------------------------------------------------------ sensores y wake-lock (comunes)
    private fun startRunning(app: Context) {
        if (running) return
        est.reset(); detector.reset(); sustained.reset()
        speedMs = 0.0; speedAt = 0L; straight = false; peakSigned = 0.0
        prevSpeed = Double.NaN; prevT = 0L; longGf = 0.0; bearings.clear()
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors = sm
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); val gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasLean = acc != null && gyr != null
        if (hasLean) {
            val th = HandlerThread("moto-sensors", Process.THREAD_PRIORITY_MORE_FAVORABLE).also { it.start() }
            sensorThread = th
            val h = Handler(th.looper)
            sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME, h)
            sm.registerListener(this, gyr, SensorManager.SENSOR_DELAY_GAME, h)
        }
        wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EspNav:moto").apply { setReferenceCounted(false); acquire(12 * 3600 * 1000L) }
        running = true
    }

    private fun stopRunningIfIdle() {
        if (!running || active) return
        runCatching { sensors?.unregisterListener(this) }
        sensorThread?.quitSafely(); sensorThread = null
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        running = false
    }

    // ------------------------------------------------------------------ modo moto
    /** Activa o desactiva el modo moto. Al activarlo el máximo vuelve a empezar de cero. */
    fun setMode(ctx: Context, on: Boolean) {
        if (on == modeOn) return
        if (on) {
            modeMaxLeft = 0.0; modeMaxRight = 0.0; sustained.reset()
            startRunning(ctx.applicationContext)
            modeOn = true
            AppLog.i("Moto", "Modo moto activado")
        } else {
            modeOn = false
            AppLog.i("Moto", "Modo moto desactivado")
            stopRunningIfIdle()
            if (BridgeHub.link?.ready == true) BridgeHub.sink.send(Frame.json(JSONObject().put("t", "moto").put("on", 0)))   // el ESP32 lo quita
        }
    }

    /** Texto para el ESP32 (solo ASCII: su fuente no tiene acentos ni el símbolo de grado). */
    fun modeText(): String {
        val l = modeMaxLeft; val r = modeMaxRight
        if (l < 1.0 && r < 1.0) return "MOTO --"
        return if (l >= r) "MOTO ${l.roundToInt()} IZQ" else "MOTO ${r.roundToInt()} DER"
    }

    // ------------------------------------------------------------------ grabar un viaje
    /**
     * Empieza a grabar. null = bien; si no, un aviso para el usuario. Con aviso puede haber empezado igualmente
     * (comprobar [recording]): es el caso de un móvil sin giroscopio.
     */
    fun startRecording(ctx: Context): String? {
        if (recording) return null
        val app = ctx.applicationContext
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "Falta el permiso de ubicación (Ajustes > Configuración inicial)."
        val st = TripStore(File(app.filesDir, "trips")); store = st
        val startMs = System.currentTimeMillis()
        tripId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMs))
        writer = st.open(tripId, startMs)
        stats = TripStats()
        startElapsed = SystemClock.elapsedRealtime()
        longGf = 0.0; prevSpeed = Double.NaN
        startRunning(app)
        recording = true
        BridgeService.startSafely(app)           // el servicio da el GPS y mantiene la app viva en segundo plano
        AppLog.i("Moto", "Grabación iniciada (inclinación: $hasLean)")
        return if (hasLean) null else "Este móvil no tiene giroscopio: se graba la ruta pero no la inclinación."
    }

    /** Para y guarda. [onDone] recibe el resumen (o null si el viaje era demasiado corto) en el hilo principal. */
    fun stopRecording(onDone: (TripSummary?) -> Unit = {}) {
        if (!recording) { onDone(null); return }
        recording = false
        val id = tripId; val st = store; val w = writer
        val summary = stats.summary(id, st?.defaultName(stats.startMs) ?: id)
        AppLog.i("Moto", "Grabación parada: ${summary.points} puntos, ${summary.curves} curvas")
        stopRunningIfIdle()
        io.execute {
            runCatching { w?.flush(); w?.close() }
            val ok = summary.points >= 2
            if (ok) runCatching { st?.writeMeta(summary) } else runCatching { st?.delete(id) }     // un viaje de segundos no se guarda
            main.post { onDone(if (ok) summary else null) }
        }
    }

    /** Para todo (al detenerse el servicio): cierra y guarda el viaje y desactiva el modo moto. */
    fun stopAll() {
        stopRecording()
        modeOn = false
        stopRunningIfIdle()
    }

    /** Id del viaje que se está grabando (la lista de viajes no lo toca). */
    fun activeId(): String? = if (recording) tripId else null

    // ------------------------------------------------------------------ GPS (hilo principal; lo llama BridgeService)
    fun onLocation(loc: Location) {
        if (!active) return
        if (loc.hasAccuracy() && loc.accuracy > 60f) return            // fix malo: no se apunta
        val v = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        speedMs = v; speedAt = SystemClock.elapsedRealtime(); lastLat = loc.latitude; lastLon = loc.longitude
        straight = updateStraight(loc, v)

        val t = if (loc.time > 0) loc.time else System.currentTimeMillis()
        if (!prevSpeed.isNaN() && t > prevT) {                          // aceleración longitudinal desde el GPS, suavizada
            val dt = (t - prevT) / 1000.0
            if (dt in 0.3..5.0) longGf += ((v - prevSpeed) / dt / G - longGf) * 0.6
        }
        prevSpeed = v; prevT = t

        val lean: Double; val peak: Double
        synchronized(peakLock) { lean = est.leanDeg; peak = if (abs(peakSigned) > abs(lean)) peakSigned else lean; peakSigned = lean }
        if (!recording) return                                          // solo modo moto: nada que apuntar

        val shown = if (est.valid || abs(peak) > 0.5) peak else 0.0
        val p = TrackPoint(
            t, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else Double.NaN, v,
            if (loc.hasBearing()) loc.bearing.toDouble() else Double.NaN, if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN,
            if (est.valid) lean else 0.0, if (est.valid) shown else 0.0, tan(Math.toRadians(abs(shown))), longGf
        )
        stats.addPoint(p)
        val w = writer; val n = stats.points
        io.execute { runCatching { w?.write(TripCodec.point(p)); w?.newLine(); if (n % 5 == 0) w?.flush() } }
    }

    /** Recta = el rumbo casi no ha cambiado en los últimos 4 s (sirve para corregir el sesgo del giroscopio). */
    private fun updateStraight(loc: Location, v: Double): Boolean {
        if (!loc.hasBearing() || v < 6.0) { bearings.clear(); return false }
        val now = SystemClock.elapsedRealtime()
        bearings.addLast(now to loc.bearing.toDouble())
        while (bearings.isNotEmpty() && now - bearings.first.first > 4500) bearings.removeFirst()
        if (bearings.size < 4) return false
        var maxStep = 0.0
        var prev = bearings.first.second
        for (b in bearings) { var d = abs(b.second - prev) % 360.0; if (d > 180) d = 360 - d; if (d > maxStep) maxStep = d; prev = b.second }
        var net = abs(bearings.last.second - bearings.first.second) % 360.0; if (net > 180) net = 360 - net
        return net < 2.0 && maxStep < 3.0
    }

    private fun onCurve(c: Curve) {
        if (!recording) return
        stats.addCurve(c)
        val w = writer
        io.execute { runCatching { w?.write(TripCodec.curve(c)); w?.newLine(); w?.flush() } }
    }

    // ------------------------------------------------------------------ sensores (hilo propio)
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> est.onAccel(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            Sensor.TYPE_GYROSCOPE -> {
                val fresh = SystemClock.elapsedRealtime() - speedAt < SPEED_STALE_MS
                val v = if (fresh) speedMs else 0.0
                val lean = est.onGyro(e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(), v, straight && fresh)
                synchronized(peakLock) { if (abs(lean) > abs(peakSigned)) peakSigned = lean }
                detector.update(e.timestamp / 1_000_000L, if (est.valid) lean else 0.0, v, lastLat, lastLon)
                if (modeOn) {                                           // máximo del modo moto: solo inclinaciones mantenidas 0,3 s
                    val s = sustained.update(lean, est.valid)
                    if (s > modeMaxLeft) modeMaxLeft = s
                    if (-s > modeMaxRight) modeMaxRight = -s
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ para la pantalla (hilo principal)
    fun live(): Live {
        val lean = est.leanDeg; val valid = active && est.valid
        val age = if (speedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - speedAt
        val rec = recording
        return Live(
            recording = rec, modeOn = modeOn,
            elapsedMs = if (rec) SystemClock.elapsedRealtime() - startElapsed else 0L,
            speedKmh = if (active && age < SPEED_STALE_MS) speedMs * 3.6 else 0.0,
            leanDeg = if (valid) lean else 0.0, leanValid = valid,
            maxLeft = if (rec) maxOf(stats.maxLeft, if (valid && lean > 0) lean else 0.0) else modeMaxLeft,
            maxRight = if (rec) maxOf(stats.maxRight, if (valid && lean < 0) -lean else 0.0) else modeMaxRight,
            modeLeft = modeMaxLeft, modeRight = modeMaxRight,
            latG = if (valid) tan(Math.toRadians(abs(lean))) else 0.0, longG = longGf,
            distanceM = if (rec) stats.distanceM else 0.0, curves = if (rec) stats.curves else 0, hasLean = hasLean, gpsAgeMs = age
        )
    }
}
