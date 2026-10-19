package dev.gpsnav.bridge

import android.content.Context

/** Dispositivo ESP32 elegido por el usuario (null = automático). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("espnav", Context.MODE_PRIVATE)

    fun deviceAddress(ctx: Context): String? = sp(ctx).getString("addr", null)
    fun deviceName(ctx: Context): String? = sp(ctx).getString("name", null)

    fun setDevice(ctx: Context, address: String?, name: String?) {
        sp(ctx).edit().apply {
            if (address == null) { remove("addr"); remove("name") }
            else { putString("addr", address); putString("name", name) }
        }.apply()
    }
}
