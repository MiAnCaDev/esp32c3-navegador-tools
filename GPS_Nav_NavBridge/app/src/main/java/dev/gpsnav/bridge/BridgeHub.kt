package dev.gpsnav.bridge

import android.graphics.Bitmap
import dev.gpsnav.bridge.ble.BleLink
import dev.gpsnav.bridge.protocol.FrameSink

/** Punto común de todo el proceso: el servicio pone aquí el enlace BLE y los handlers envían por él. */
object BridgeHub {
    @Volatile var link: BleLink? = null
    val sink = FrameSink { f -> link?.send(f) }

    // Estado para la pantalla de depuración
    @Volatile var bleStatus = "Parado"
    @Volatile var lastNav = "-"
    @Volatile var lastSpeed = "-"
    @Volatile var lastIcon: Bitmap? = null
}
