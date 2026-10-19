package dev.gpsnav.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import dev.gpsnav.bridge.ble.BleLink
import dev.gpsnav.bridge.notif.HandlerRegistry
import dev.gpsnav.bridge.notif.NavNotificationListener
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject

/** Servicio en primer plano: mantiene el BLE y envía la velocidad GPS. */
class BridgeService : Service() {

    private var link: BleLink? = null
    private var lm: LocationManager? = null

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
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            BridgeHub.bleStatus = "No se pudo iniciar: ${e.message}"
            stopSelf(); return START_NOT_STICKY
        }
        startWork()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "Esp Nav MiAnCa", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Esp Nav MiAnCa activo")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else 0
        ServiceCompat.startForeground(this, 1, notif, type)
    }

    private fun startWork() {
        if (link != null) return
        val l = BleLink(
            applicationContext,
            targetAddress = { Prefs.deviceAddress(applicationContext) },
            onStatus = { BridgeHub.bleStatus = it },
            onReady = {
                HandlerRegistry.linkReady()
                NavNotificationListener.instance?.replayActive()
            }
        )
        link = l; BridgeHub.link = l
        l.start()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lm = getSystemService(LocationManager::class.java).also {
                it.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            }
        }
    }

    override fun onDestroy() {
        link?.stop(); link = null; BridgeHub.link = null
        lm?.removeUpdates(locListener); lm = null
        super.onDestroy()
    }

    companion object { const val ACTION_STOP = "dev.gpsnav.bridge.STOP" }
}
