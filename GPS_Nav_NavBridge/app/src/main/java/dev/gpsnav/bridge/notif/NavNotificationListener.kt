package dev.gpsnav.bridge.notif

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.gpsnav.bridge.AppLog

class NavNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NavNotificationListener? = null
    }

    override fun onListenerConnected() { instance = this; AppLog.i("Notif", "Acceso a notificaciones: conectado") }
    override fun onListenerDisconnected() {
        AppLog.w("Notif", "Acceso a notificaciones: DESCONECTADO por el sistema")
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) =
        HandlerRegistry.posted(applicationContext, sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) =
        HandlerRegistry.removed(applicationContext, sbn)

    /** Reprocesa lo que ya hay en la barra (útil al conectar el ESP32 con una ruta en curso). */
    fun replayActive() {
        runCatching { activeNotifications?.forEach { HandlerRegistry.posted(applicationContext, it) } }
    }
}
