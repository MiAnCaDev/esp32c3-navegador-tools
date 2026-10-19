package dev.gpsnav.bridge.notif

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.gpsnav.bridge.BridgeHub
import dev.gpsnav.bridge.protocol.FrameSink

/**
 * Un handler = una fuente de notificaciones (Google Maps, WhatsApp, calendario...).
 * Recibe las notificaciones de los paquetes que declara y decide qué tramas mandar al ESP32.
 */
interface NotificationHandler {
    val id: String
    val packages: Set<String>
    fun onPosted(ctx: Context, sbn: StatusBarNotification, out: FrameSink)
    fun onRemoved(ctx: Context, sbn: StatusBarNotification, out: FrameSink) {}
    /** Se llama cada vez que el ESP32 (re)conecta: borra cachés para reenviar el estado actual. */
    fun onLinkReady() {}
}

object HandlerRegistry {
    private const val TAG = "NavBridge"

    /**
     * >>> PARA AÑADIR OTRA FUENTE: crea una clase que implemente NotificationHandler y añádela aquí. <<<
     * Ejemplo:  listOf(GoogleMapsHandler(), MensajesHandler())
     */
    val handlers: List<NotificationHandler> = listOf(
        GoogleMapsHandler(),
    )

    fun posted(ctx: Context, sbn: StatusBarNotification) = forPackage(sbn) {
        it.onPosted(ctx, sbn, BridgeHub.sink)
    }

    fun removed(ctx: Context, sbn: StatusBarNotification) = forPackage(sbn) {
        it.onRemoved(ctx, sbn, BridgeHub.sink)
    }

    fun linkReady() = handlers.forEach { runCatching { it.onLinkReady() } }

    private fun forPackage(sbn: StatusBarNotification, block: (NotificationHandler) -> Unit) {
        for (h in handlers) {
            if (sbn.packageName !in h.packages) continue
            runCatching { block(h) }.onFailure { Log.e(TAG, "handler ${h.id} falló", it) }
        }
    }
}
