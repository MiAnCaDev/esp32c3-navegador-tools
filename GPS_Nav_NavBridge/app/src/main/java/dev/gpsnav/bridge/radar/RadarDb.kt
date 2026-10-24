package dev.gpsnav.bridge.radar

import android.content.Context
import dev.gpsnav.bridge.AppLog
import org.json.JSONArray
import java.io.File
import kotlin.math.floor

class Radar(
    val lat: Double,
    val lon: Double,
    /** 0 = radar fijo, 1 = radar de tramo (se avisa al inicio del tramo). */
    val kind: Int,
    /** Límite de velocidad en km/h, 0 = desconocido. */
    var limit: Int,
    /** Texto corto para la pantalla del ESP32, p. ej. "N-340". */
    val road: String,
) {
    /** Clave en la caché de límites de vía (se calcula una vez). */
    @JvmField var rlKey: String? = null
}

/**
 * Base de radares guardada en el móvil (filesDir/radares.json) con un índice por cuadrículas de 0,05°
 * (~4-5 km) para que buscar los radares cercanos sea instantáneo. Todo el trabajo lo hace la app.
 */
object RadarDb {
    private const val FILE = "radares.json"
    private const val CELL = 20.0                      // celdas de 1/20 de grado

    /**
     * Foto inmutable de la base: radares + índice. Quien la use durante un ciclo de cálculo ve siempre datos coherentes
     * aunque otro hilo esté sustituyendo la base (descarga), porque se cambia la referencia entera de golpe.
     */
    class Snapshot internal constructor(private val radars: Array<Radar>, private val grid: Map<Long, IntArray>) {
        val size get() = radars.size
        operator fun get(i: Int) = radars[i]

        // Los radares de las celdas vecinas solo cambian al cruzar de celda (~4 km): se reutiliza el resultado
        private var memoKey = Long.MIN_VALUE
        private var memoVal = IntArray(0)

        /** Índices de los radares en las celdas vecinas (cubre radios de hasta [RadarConfig.MAX_DIST] m). Hilo principal. */
        fun near(lat: Double, lon: Double): IntArray {
            val k = key(floor(lat * CELL).toLong(), floor(lon * CELL).toLong())
            if (k != memoKey) { memoVal = query(grid, lat, lon); memoKey = k }
            return memoVal
        }
    }

    @Volatile private var snap = Snapshot(emptyArray(), emptyMap())
    @Volatile var updatedAt = 0L
        private set
    /** Sube cada vez que se sustituye la base: los índices guardados dejan de valer. */
    @Volatile var generation = 0
        private set

    val count get() = snap.size
    fun snapshot(): Snapshot = snap

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) { AppLog.i("Radar", "Base de radares: aún no descargada"); return }
        runCatching {
            val arr = JSONArray(f.readText())
            val list = ArrayList<Radar>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                list.add(Radar(a.getDouble(0), a.getDouble(1), a.getInt(2), a.getInt(3), a.optString(4, "")))
            }
            install(list)
            updatedAt = f.lastModified()
            AppLog.i("Radar", "Base de radares cargada: ${list.size}")
        }.onFailure { AppLog.e("Radar", "No se pudo leer $FILE", it) }
    }

    /** Sustituye la base (tras una descarga correcta) y la guarda en disco. */
    fun replace(ctx: Context, list: List<Radar>) {
        val arr = JSONArray()
        for (r in list) arr.put(JSONArray().put(r.lat).put(r.lon).put(r.kind).put(r.limit).put(r.road))
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        val dst = File(ctx.filesDir, FILE)
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
        install(list)
        updatedAt = dst.lastModified()
    }

    private fun install(list: List<Radar>) {
        val a = list.toTypedArray()
        snap = Snapshot(a, buildGrid(a))
        generation++
    }

    // ---- utilidades puras (también las usa el actualizador para fusionar fuentes) ----
    private fun key(gy: Long, gx: Long) = gy * 10_000L + (gx + 1_000L)

    fun buildGrid(a: Array<Radar>): Map<Long, IntArray> {
        val tmp = HashMap<Long, ArrayList<Int>>()
        for (i in a.indices) {
            val k = key(floor(a[i].lat * CELL).toLong(), floor(a[i].lon * CELL).toLong())
            tmp.getOrPut(k) { ArrayList() }.add(i)
        }
        return tmp.mapValues { it.value.toIntArray() }
    }

    fun query(g: Map<Long, IntArray>, lat: Double, lon: Double): IntArray {
        val cy = floor(lat * CELL).toLong(); val cx = floor(lon * CELL).toLong()
        var n = 0
        for (dy in -1L..1L) for (dx in -1L..1L) n += g[key(cy + dy, cx + dx)]?.size ?: 0
        val out = IntArray(n)
        var p = 0
        for (dy in -1L..1L) for (dx in -1L..1L) g[key(cy + dy, cx + dx)]?.let { System.arraycopy(it, 0, out, p, it.size); p += it.size }
        return out
    }
}
