package dev.gpsnav.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.gpsnav.bridge.ble.BleLink
import dev.gpsnav.bridge.notif.HandlerRegistry
import dev.gpsnav.bridge.notif.NavNotificationListener
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject

/**
 * Servicio en primer plano: mantiene el BLE, envía la velocidad GPS y muestra una notificación
 * fija con el estado de la conexión. Si se pierde la conexión y no se recupera, vibra 3 veces.
 * Si la app se cierra (y la opción está activada), se programa un reinicio automático.
 */
class BridgeService : Service() {

    private var link: BleLink? = null
    private var lm: LocationManager? = null
    private var locationOk = false      // false si Android no nos deja usar GPS (arranque en segundo plano sin permiso)
    private var startFailed = false

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!loc.hasSpeed()) return
            val kmh = Math.round(loc.speed * 3.6f)
            BridgeHub.lastSpeed = "$kmh km/h"
            BridgeHub.sink.send(Frame.json(JSONObject().put("t", "gps").put("speed", kmh)))
        }
        @Deprecated("API antigua") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("Service", "Parada pedida (botón Parar / notificación)")
            Prefs.setWantRunning(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        Prefs.setWantRunning(this, true)
        BridgeHub.bleStatus = "Iniciando..."
        AppLog.i("Service", if (intent == null) "Inicio: reinicio por el sistema (START_STICKY)" else "Inicio del servicio")
        logSnapshot()
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            startFailed = true
            AppLog.e("Service", "No se pudo iniciar en primer plano", e)
            BridgeHub.bleStatus = "No se pudo iniciar: ${e.message}"
            stopSelf(); return START_NOT_STICKY
        }
        startWork()
        startLocationIfPossible()
        return START_STICKY
    }

    /** El usuario ha quitado la app de recientes: programamos un reinicio. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("Service", "App quitada de recientes (reinicio automático=${Prefs.autoRestart(this)})")
        if (Prefs.autoRestart(this)) scheduleRestart(this, 1500)
        super.onTaskRemoved(rootIntent)
    }

    // ------------------------------------------------------------ notificación fija
    private fun startForegroundCompat() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Estado de la conexión", NotificationManager.IMPORTANCE_LOW))
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT < 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, 0); locationOk = true; return
        }
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or device)
            locationOk = true
            AppLog.i("Service", "Primer plano OK (tipos: ubicación + dispositivo)")
        } catch (e: SecurityException) {
            AppLog.w("Service", "Android no permite el tipo ubicación en segundo plano; sigo solo con Bluetooth. ${e.message}")
            // Arranque en segundo plano (p. ej. al encender el móvil) sin ubicación "todo el tiempo":
            // seguimos solo con Bluetooth; el GPS se activará cuando se abra la app.
            locationOk = false
            ServiceCompat.startForeground(this, NOTIF_ID, notif, device)
        }
    }

    private fun buildNotification(): Notification {
        val l = link
        val connected = l?.ready == true
        val dev = l?.deviceName ?: Prefs.deviceName(this)

        val title = "Esp Nav MiAnCa · " + if (connected) "Conectado" else "Sin conexión"
        val text = if (connected) "Dispositivo: ${dev ?: "ESP32"}"
                   else "${dev ?: "ESP32"} · ${BridgeHub.bleStatus}"

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (connected) android.R.drawable.stat_sys_data_bluetooth else android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Parar", stop
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun refreshNotification() {
        if (link == null) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    // ------------------------------------------------------------ vibración de aviso
    @Suppress("DEPRECATION")
    private fun vibrate3Short() {
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(VibratorManager::class.java).defaultVibrator
            else getSystemService(VIBRATOR_SERVICE) as Vibrator
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 150, 120, 150, 120, 150), -1)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(effect, attrs)
        }
    }

    // ------------------------------------------------------------ trabajo principal
    private fun startWork() {
        if (link != null) return
        val l = BleLink(
            applicationContext,
            targetAddress = { Prefs.deviceAddress(applicationContext) },
            onStatus = { BridgeHub.bleStatus = it; refreshNotification() },
            onReady = {
                HandlerRegistry.linkReady()
                NavNotificationListener.instance?.replayActive()
            },
            onGaveUp = { AppLog.w("Service", "Vibración de aviso (3 pulsos)"); vibrate3Short(); refreshNotification() }
        )
        link = l; BridgeHub.link = l
        l.start()
    }

    private fun startLocationIfPossible() {
        if (lm != null) return
        if (!locationOk) { AppLog.w("Service", "GPS no disponible ahora (falta ubicación en segundo plano)"); return }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("Service", "GPS no activado: falta el permiso de ubicación"); return
        }
        runCatching {
            lm = getSystemService(LocationManager::class.java).also {
                it.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            }
            AppLog.i("Service", "GPS activado")
        }.onFailure { AppLog.e("Service", "Error al activar el GPS", it) }
    }

    /** Foto de la configuración al arrancar: lo primero que hay que mirar cuando algo falla. */
    private fun logSnapshot() {
        val pm = getSystemService(PowerManager::class.java)
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled
        fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        AppLog.i("Service", "Config: dispositivo=${Prefs.deviceName(this) ?: "auto"}/${Prefs.deviceAddress(this) ?: "-"}, " +
            "arranque_auto=${Prefs.autoStartBoot(this)}, reinicio_auto=${Prefs.autoRestart(this)}, " +
            "bateria_exenta=${pm.isIgnoringBatteryOptimizations(packageName)}, bluetooth=$bt, " +
            "gps=${granted(Manifest.permission.ACCESS_FINE_LOCATION)}, " +
            "gps_fondo=${Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}, " +
            "acceso_notif=${NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)}")
    }

    override fun onDestroy() {
        AppLog.i("Service", "onDestroy (wantRunning=${Prefs.wantRunning(this)}, startFailed=$startFailed)")
        link?.stop(); link = null; BridgeHub.link = null
        lm?.removeUpdates(locListener); lm = null
        BridgeHub.bleStatus = "Parado"
        // Si nos paran sin que lo haya pedido el usuario, intentamos volver.
        if (!startFailed && Prefs.wantRunning(this) && Prefs.autoRestart(this)) scheduleRestart(this, 3000)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.gpsnav.bridge.STOP"
        const val ACTION_RESTART = "dev.gpsnav.bridge.RESTART"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1

        fun startSafely(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, BridgeService::class.java)) }
        }

        fun scheduleRestart(ctx: Context, delayMs: Long) {
            AppLog.i("Service", "Reinicio programado en ${delayMs} ms")
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, BootReceiver::class.java).setAction(ACTION_RESTART),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val at = SystemClock.elapsedRealtime() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }
}
