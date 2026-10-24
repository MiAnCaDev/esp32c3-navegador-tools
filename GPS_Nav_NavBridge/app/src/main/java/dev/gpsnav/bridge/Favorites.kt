package dev.gpsnav.bridge

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ubicaciones favoritas para el modo brújula: hasta [MAX], con nombre. Se guardan en el móvil (SharedPreferences)
 * y al tocar una en la app pasa a ser el destino de la brújula (ver MainActivity).
 */
object Favorites {
    const val MAX = 8
    private const val SP = "espnav"
    private const val KEY = "favs"
    private const val SAME_PLACE_M = 15f          // dos favoritas a menos de esto se consideran el mismo sitio

    class Fav(val name: String, val lat: Double, val lon: Double)

    @Volatile private var items: List<Fav> = emptyList()

    /** Sube con cada cambio: la pantalla lo usa para saber cuándo redibujar la lista. */
    @Volatile var version = 0
        private set

    fun list(): List<Fav> = items

    fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val a = JSONArray(raw)
            items = (0 until minOf(a.length(), MAX)).map {
                val o = a.getJSONObject(it)
                Fav(o.getString("n"), o.getDouble("la"), o.getDouble("lo"))
            }
            version++
        }.onFailure { AppLog.e("Fav", "No se pudieron leer las favoritas", it) }
    }

    private fun save(ctx: Context, list: List<Fav>) {
        items = list
        version++
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("la", it.lat).put("lo", it.lon)) }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0]
    }

    /** La favorita que está en ese punto (a menos de 15 m), si la hay. */
    fun at(lat: Double, lon: Double): Fav? = items.firstOrNull { meters(it.lat, it.lon, lat, lon) < SAME_PLACE_M }

    /** null = guardada; si no, el motivo. */
    fun add(ctx: Context, name: String, lat: Double, lon: Double): String? {
        if (items.size >= MAX) return "Ya tienes $MAX favoritas. Elimina alguna para añadir otra."
        at(lat, lon)?.let { return "Ya está guardada como «${it.name}»." }
        val n = Destination.cleanName(name)            // la pantalla del ESP32 solo pinta caracteres sin acentos
        save(ctx, items + Fav(n, lat, lon))
        AppLog.i("Fav", "Favorita añadida: '$n' (${items.size}/$MAX)")
        return null
    }

    /** Sustituye toda la lista (importar configuración). */
    fun replaceAll(ctx: Context, list: List<Fav>) {
        save(ctx, list.take(MAX))
        AppLog.i("Fav", "Favoritas sustituidas por una importación (${items.size})")
    }

    fun rename(ctx: Context, index: Int, name: String) {
        val cur = items.getOrNull(index) ?: return
        val n = Destination.cleanName(name)
        save(ctx, items.mapIndexed { i, f -> if (i == index) Fav(n, cur.lat, cur.lon) else f })
    }

    fun remove(ctx: Context, index: Int) {
        if (index !in items.indices) return
        AppLog.i("Fav", "Favorita eliminada: '${items[index].name}'")
        save(ctx, items.filterIndexed { i, _ -> i != index })
    }
}
