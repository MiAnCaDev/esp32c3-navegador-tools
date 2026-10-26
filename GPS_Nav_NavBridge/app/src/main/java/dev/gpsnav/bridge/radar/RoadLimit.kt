package dev.gpsnav.bridge.radar

import android.content.Context
import android.os.SystemClock
import dev.gpsnav.bridge.AppLog
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Velocidad máxima de la vía en el punto donde está el radar (que es la que se multa).
 *
 * Orden de prioridad:
 *   1. El propio radar, si su dato trae límite (OpenStreetMap lo anota a veces en el radar).
 *   2. El límite de la VÍA en ese punto, de OpenStreetMap (etiqueta maxspeed), consultado por internet una sola vez
 *      por radar y guardado en el móvil (filesDir/limites.json). Las siguientes veces funciona sin conexión.
 *
 * Se consulta con antelación (el siguiente radar por delante a menos de 3 km), de una en una y con pausas, para no
 * abusar del servidor Overpass. Solo se envían las coordenadas del RADAR, nunca tu posición exacta.
 * Si no hay dato o no hay conexión, el límite es 0 (desconocido) y simplemente no se pinta el borde rojo.
 *
 * Limitaciones: el dato lo ponen voluntarios (puede faltar o estar desfasado); los límites variables por paneles
 * ("signals") y los condicionales (lluvia, horarios) no se contemplan.
 */
object RoadLimit {
    private const val FILE = "limites.json"
    private const val AROUND_M = 35                 // vías a menos de 35 m del radar
    private const val RETRY_MS = 60_000L            // tras un fallo, no reintentar ese radar en 1 min
    private const val MIN_GAP_MS = 1500L            // pausa mínima entre consultas
    private val SKIP = setOf("footway", "cycleway", "path", "pedestrian", "steps", "bridleway", "corridor", "platform")

    private val lock = Any()
    private val known = HashMap<String, Int>()      // "lat,lon" -> límite (0 = consultado y sin dato)
    private val failedAt = HashMap<String, Long>()
    private val inFlight = HashSet<String>()
    private var lastQueryAt = 0L
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = ctx.filesDir
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            synchronized(lock) { for (k in o.keys()) known[k] = o.getInt(k) }
            AppLog.i("Radar", "Límites de vía en caché: ${known.size}")
        }.onFailure { AppLog.e("Radar", "No se pudo leer $FILE", it) }
    }

    private fun key(r: Radar): String = r.rlKey ?: String.format(Locale.US, "%.5f,%.5f", r.lat, r.lon).also { r.rlKey = it }

    /** Límite en km/h (0 = desconocido). Si todavía no se sabe, lo pide en segundo plano y devuelve 0 por ahora. */
    fun limitFor(r: Radar, heading: Double): Int {
        if (r.limit > 0) return r.limit
        val k = key(r)
        synchronized(lock) { known[k]?.let { return it } }
        if (RadarConfig.current.roadLimits) request(r, k, heading)
        return 0
    }

    private fun request(r: Radar, k: String, heading: Double) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (inFlight.isNotEmpty()) return                       // una consulta cada vez
            val f = failedAt[k]
            if (f != null && now - f < RETRY_MS) return
            if (now - lastQueryAt < MIN_GAP_MS) return
            inFlight.add(k); lastQueryAt = now
        }
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val res: Int? = try { query(r, heading) } catch (t: Throwable) {
                AppLog.w("Radar", "Límite de vía: sin datos (${t.message})"); null
            }
            synchronized(lock) {
                inFlight.remove(k)
                if (res == null) failedAt[k] = SystemClock.elapsedRealtime()
                else { known[k] = res; save() }
            }
            if (res != null) AppLog.i("Radar", "Límite de la vía en radar $k = ${if (res > 0) "$res km/h" else "sin dato"}")
        }, "road-limit").start()
    }

    private fun save() {
        val d = dir ?: return
        runCatching {
            val o = JSONObject(); for ((k, v) in known) o.put(k, v)
            File(d, FILE).writeText(o.toString())
        }
    }

    private class Cand(val score: Double, val limit: Int)

    /** Vías cercanas con su geometría; se elige la más próxima cuyo sentido encaje con el rumbo de marcha. */
    private fun query(r: Radar, heading: Double): Int {
        val q = String.format(Locale.US,
            "[out:json][timeout:15];way(around:%d,%.6f,%.6f)[\"highway\"];out tags geom;", AROUND_M, r.lat, r.lon)
        val text = RadarUpdater.overpass(q, 8_000, 12_000)      // prueba varios espejos; Overpass a veces va saturado
        val els = JSONObject(text).optJSONArray("elements") ?: return 0
        val cands = ArrayList<Cand>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val hw = tags.optString("highway", "")
            if (hw in SKIP) continue
            val g = e.optJSONArray("geometry") ?: continue
            var bestD = Double.MAX_VALUE; var segBearing = 0.0
            for (j in 0 until g.length() - 1) {
                val a = g.getJSONObject(j); val b = g.getJSONObject(j + 1)
                val d = segDist(r.lat, r.lon, a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon"))
                if (d < bestD) {
                    bestD = d
                    segBearing = Geo.bearing(a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon"))
                }
            }
            if (bestD == Double.MAX_VALUE) continue
            val diff = Geo.angleDiff(segBearing, heading)
            val forward = diff <= 90.0
            val across = (if (diff > 90.0) 180.0 - diff else diff) > 45.0       // vía que cruza: poco probable
            var raw = tags.optString(if (forward) "maxspeed:forward" else "maxspeed:backward", "")
            if (raw.isEmpty()) raw = tags.optString("maxspeed", "")
            cands.add(Cand(bestD + if (across) 60.0 else 0.0, parseLimit(raw)))
        }
        if (cands.isEmpty()) return 0
        cands.sortBy { it.score }
        val best = cands[0]
        if (best.limit > 0) return best.limit
        // la misma carretera suele estar partida en varias vías: si otra casi igual de cerca trae límite, se usa
        return cands.firstOrNull { it.score <= best.score + 15.0 && it.limit > 0 }?.limit ?: 0
    }

    private fun parseLimit(raw: String): Int {
        val t = raw.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return 0
        Regex("""^(\d{1,3})(\s*km/h)?$""").find(t)?.let { m ->
            val v = m.groupValues[1].toInt(); return if (v in 5..130) v else 0
        }
        return when (t) {                  // solo los valores simbólicos que son inequívocos en España
            "es:urban" -> 50
            "es:zone30" -> 30
            "es:living_street" -> 20
            "es:motorway" -> 120
            else -> 0
        }
    }

    private fun segDist(plat: Double, plon: Double, alat: Double, alon: Double, blat: Double, blon: Double): Double {
        val kx = cos(Math.toRadians(plat)) * 111_320.0; val ky = 110_540.0
        val ax = (alon - plon) * kx; val ay = (alat - plat) * ky
        val bx = (blon - plon) * kx; val by = (blat - plat) * ky
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}
