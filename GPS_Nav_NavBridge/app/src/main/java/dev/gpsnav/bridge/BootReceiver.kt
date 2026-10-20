package dev.gpsnav.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * - Al arrancar el móvil: inicia el servicio (si la opción está activada).
 * - Tras actualizar la app o tras un cierre: lo vuelve a iniciar (si estaba en marcha y la opción está activada).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        AppLog.i("Boot", "Receiver: $a")
        when (a) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (Prefs.autoStartBoot(ctx)) BridgeService.startSafely(ctx)
                else AppLog.i("Boot", "Arranque automático desactivado por el usuario")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED, BridgeService.ACTION_RESTART -> {
                if (Prefs.autoRestart(ctx) && Prefs.wantRunning(ctx)) BridgeService.startSafely(ctx)
                else AppLog.i("Boot", "No se reinicia (opción desactivada o servicio parado por el usuario)")
            }
        }
    }
}
