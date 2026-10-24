package dev.gpsnav.bridge.radar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import dev.gpsnav.bridge.AppLog
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Descarga y fusiona las fuentes de radares FIJOS (y de tramo). Solo se conecta cuando el usuario pulsa
 * "Actualizar base de radares"; después todo funciona sin internet con el fichero guardado.
 *
 *  1. DGT (DATEX II, licencia CC-BY, todo el país excepto Cataluña y País Vasco).
 *  2. OpenStreetMap vía Overpass (highway=speed_camera): cubre Cataluña y País Vasco, completa lo que falte
 *     y aporta el límite de velocidad (maxspeed) cuando existe.
 *
 * No hay ninguna fuente oficial de radares MÓVILES en tiempo real, así que no se incluyen.
 */
object RadarUpdater {
    private const val DGT_URL = "https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val OVERPASS_QUERY =
        "[out:json][timeout:120];area[\"ISO3166-1\"=\"ES\"][admin_level=2]->.es;node[\"highway\"=\"speed_camera\"](area.es);out;"
    private const val MERGE_M = 60.0          // dos puntos a menos de esto se consideran el mismo radar
    private const val MAX_BYTES = 30_000_000

    @Volatile var running = false
        private set

    private val main = Handler(Looper.getMainLooper())

    /** Corre en segundo plano; [onDone] se llama en el hilo principal con un resumen para mostrar. */
    fun update(ctx: Context, onDone: (String) -> Unit) {
        if (running) { onDone("Ya hay una descarga en curso"); return }
        running = true
        val app = ctx.applicationContext
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val msg = try { doUpdate(app) } catch (t: Throwable) {
                AppLog.e("Radar", "Fallo inesperado al actualizar", t); "Error: ${t.message}"
            }
            running = false
            main.post { onDone(msg) }
        }, "radar-update").start()
    }

    private fun doUpdate(ctx: Context): String {
        var dgt: List<Radar> = emptyList(); var dgtErr: String? = null
        var osm: List<Radar> = emptyList(); var osmErr: String? = null
        try { dgt = http(DGT_URL).use { parseDgt(it) } } catch (t: Throwable) { dgtErr = t.message ?: t.javaClass.simpleName }
        try { osm = parseOsm(String(http(OVERPASS_URL, OVERPASS_QUERY).use { it.readBytes() }, Charsets.UTF_8)) }
        catch (t: Throwable) { osmErr = t.message ?: t.javaClass.simpleName }
        AppLog.i("Radar", "Descarga: DGT=${dgt.size} (${dgtErr ?: "ok"}) · OSM=${osm.size} (${osmErr ?: "ok"})")

        if (dgt.isEmpty() && osm.isEmpty())
            return "No se pudo descargar ninguna fuente (DGT: ${dgtErr ?: "vacía"}; OSM: ${osmErr ?: "vacía"})"

        val merged = merge(dgt, osm)
        RadarDb.replace(ctx, merged)
        val warn = listOfNotNull(dgtErr?.let { "DGT falló" }, osmErr?.let { "OSM falló" }).joinToString(", ")
        return "Base actualizada: ${merged.size} radares (DGT ${dgt.size} · OSM ${osm.size})" +
            if (warn.isNotEmpty()) " · $warn" else ""
    }

    internal fun http(url: String, postQuery: String? = null, readTimeoutMs: Int = 120_000): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", "GPSNavBridge/1.0 (app personal Android)")
        if (postQuery != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(postQuery, "UTF-8")).toByteArray()) }
        }
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        if (c.contentLengthLong > MAX_BYTES) throw IOException("Respuesta demasiado grande")
        return c.inputStream
    }

    private fun local(n: String) = n.substringAfter(':')
    private fun inSpain(lat: Double, lon: Double) = lat in 27.0..44.5 && lon in -19.0..5.0

    /**
     * DATEX II "PredefinedLocationsPublication". Se lee de forma tolerante: por cada predefinedLocation se toma
     * el primer par latitude/longitude (punto, o inicio del tramo), la carretera (roadNumber) y si es lineal.
     */
    private fun parseDgt(s: InputStream): List<Radar> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(s, null)
        val out = ArrayList<Radar>()
        val seen = HashSet<Long>()
        var inLoc = false; var cur = ""
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        var road = ""; var linear = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    cur = local(p.name)
                    if (cur == "predefinedLocation") { inLoc = true; lats.clear(); lons.clear(); road = ""; linear = false }
                    else if (inLoc && cur == "tpegLinearLocation") linear = true
                }
                XmlPullParser.TEXT -> if (inLoc) {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) when (cur) {
                        "latitude" -> t.replace(',', '.').toDoubleOrNull()?.let { lats.add(it) }
                        "longitude" -> t.replace(',', '.').toDoubleOrNull()?.let { lons.add(it) }
                        "roadNumber" -> if (road.isEmpty()) road = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (local(p.name) == "predefinedLocation" && inLoc) {
                        inLoc = false
                        if (lats.isNotEmpty() && lons.isNotEmpty() && inSpain(lats[0], lons[0])) {
                            val k = Math.round(lats[0] * 1e4) * 100_000L + Math.round(lons[0] * 1e4)
                            if (seen.add(k))                        // la DGT repite el punto por sentido de circulación
                                out.add(Radar(lats[0], lons[0], if (linear || lats.size >= 2) 1 else 0, 0, Geo.ascii(road, 12)))
                        }
                    }
                    cur = ""
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseOsm(json: String): List<Radar> {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Radar>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val lat = e.optDouble("lat", Double.NaN); val lon = e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || !inSpain(lat, lon)) continue
            val tags = e.optJSONObject("tags")
            val limit = tags?.optString("maxspeed", "")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val road = Geo.ascii(tags?.optString("ref", "") ?: "", 12)
            val tramo = tags?.optString("enforcement", "") == "average_speed"
            out.add(Radar(lat, lon, if (tramo) 1 else 0, limit, road))
        }
        return out
    }

    /** DGT como base (oficial); de OSM se añade lo que no esté a menos de [MERGE_M] m y se copia el límite. */
    private fun merge(dgt: List<Radar>, osm: List<Radar>): List<Radar> {
        val out = ArrayList<Radar>(dgt)
        val base = dgt.toTypedArray()
        val grid = RadarDb.buildGrid(base)
        for (o in osm) {
            var hit: Radar? = null; var best = MERGE_M
            for (i in RadarDb.query(grid, o.lat, o.lon)) {
                val d = Geo.dist(o.lat, o.lon, base[i].lat, base[i].lon)
                if (d < best) { best = d; hit = base[i] }
            }
            if (hit == null) out.add(o)
            else if (hit.limit == 0 && o.limit > 0) hit.limit = o.limit
        }
        return out
    }

    fun sizeText(n: Int) = if (n <= 0) "sin descargar" else "$n radares"
}
