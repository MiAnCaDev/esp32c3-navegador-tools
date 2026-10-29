package dev.gpsnav.bridge

import android.content.Context

/** Ajustes guardados de la app. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("espnav", Context.MODE_PRIVATE)

    // --- ESP32 elegido (null = automático) ---
    fun deviceAddress(ctx: Context): String? = sp(ctx).getString("addr", null)
    fun deviceName(ctx: Context): String? = sp(ctx).getString("name", null)

    fun setDevice(ctx: Context, address: String?, name: String?) {
        sp(ctx).edit().apply {
            if (address == null) { remove("addr"); remove("name") }
            else { putString("addr", address); putString("name", name) }
        }.apply()
    }

    // --- Opciones de arranque (activadas por defecto) ---
    fun autoStartBoot(ctx: Context) = sp(ctx).getBoolean("boot", true)
    fun setAutoStartBoot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("boot", v).apply()

    fun autoRestart(ctx: Context) = sp(ctx).getBoolean("restart", true)
    fun setAutoRestart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("restart", v).apply()

    /** Marca de tiempo de la última salida de proceso ya registrada en el log. */
    fun lastExitTs(ctx: Context) = sp(ctx).getLong("exit_ts", 0L)
    fun setLastExitTs(ctx: Context, v: Long) = sp(ctx).edit().putLong("exit_ts", v).apply()

    // --- Destino de la brújula ---
    fun destination(ctx: Context): Destination.Dest? {
        val p = sp(ctx)
        val lat = p.getString("dest_lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("dest_lon", null)?.toDoubleOrNull() ?: return null
        return Destination.Dest(lat, lon, p.getString("dest_name", null) ?: "Destino")
    }

    fun setDestination(ctx: Context, d: Destination.Dest?) {
        sp(ctx).edit().apply {
            if (d == null) { remove("dest_lat"); remove("dest_lon"); remove("dest_name") }
            else { putString("dest_lat", d.lat.toString()); putString("dest_lon", d.lon.toString()); putString("dest_name", d.name) }
        }.apply()
    }

    /** true mientras el usuario quiere el servicio en marcha (false tras pulsar "Parar"). */
    fun wantRunning(ctx: Context) = sp(ctx).getBoolean("want", false)
    fun setWantRunning(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("want", v).apply()

    // Pestañas opcionales de la app (Ajustes > Pestañas). Moto: visible por defecto; Lactancia: oculta (solo interesa a quien usa el bot)
    fun tabMoto(ctx: Context) = sp(ctx).getBoolean("tab_moto", true)
    fun setTabMoto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tab_moto", v).apply()
    fun tabLact(ctx: Context) = sp(ctx).getBoolean("tab_lact", false)
    fun setTabLact(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("tab_lact", v).apply()
}
