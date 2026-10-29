package dev.gpsnav.bridge.moto

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Viajes guardados en `dir`: <id>.trip (los datos, se escribe sobre la marcha) y <id>.meta (el resumen, se escribe al
 * acabar). Si la app se cierra a mitad de un viaje queda el .trip sin .meta y se reconstruye al listar: no se pierde nada.
 */
class TripStore(private val dir: File) {
    private fun tripFile(id: String) = File(dir, "$id.trip")
    private fun metaFile(id: String) = File(dir, "$id.meta")

    fun defaultName(startMs: Long) = "Viaje " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(startMs))

    /** Crea el archivo del viaje y escribe la cabecera. */
    fun open(id: String, startMs: Long): BufferedWriter {
        dir.mkdirs()
        val w = BufferedWriter(FileWriter(tripFile(id), true))
        w.write(TripCodec.header(id, startMs)); w.newLine(); w.flush()
        return w
    }

    fun writeMeta(s: TripSummary) { dir.mkdirs(); metaFile(s.id).writeText(s.toMeta()) }

    fun delete(id: String) { tripFile(id).delete(); metaFile(id).delete() }

    fun rename(id: String, name: String) {
        val s = read(id) ?: return
        writeMeta(s.withName(name.trim().ifEmpty { defaultName(s.startMs) }.take(60)))
    }

    /** Resumen de un viaje (o null si no existe). Reconstruye el .meta si falta. */
    fun read(id: String): TripSummary? {
        val m = metaFile(id)
        if (m.exists()) TripSummary.fromMeta(m.readText())?.let { return it }
        return rebuild(id)
    }

    /** Todos los viajes, el más reciente primero. [activeId] = el que se está grabando ahora (no se toca). */
    fun list(activeId: String? = null): List<TripSummary> {
        val files = dir.listFiles { f -> f.name.endsWith(".trip") } ?: return emptyList()
        return files.map { it.name.removeSuffix(".trip") }
            .filter { it != activeId }
            .mapNotNull { read(it) }
            .sortedByDescending { it.startMs }
    }

    /** Recorre los datos del viaje en orden: cada TrackPoint y cada Curve. */
    fun forEach(id: String, cb: (Any) -> Unit) {
        val f = tripFile(id)
        if (!f.exists()) return
        f.bufferedReader().useLines { lines -> lines.forEach { l -> TripCodec.parse(l)?.let(cb) } }
    }

    private fun rebuild(id: String): TripSummary? {
        if (!tripFile(id).exists()) return null
        val st = TripStats()
        forEach(id) { x -> if (x is TrackPoint) st.addPoint(x) else if (x is Curve) st.addCurve(x) }
        if (st.points < 2) { delete(id); return null }                  // viaje vacío: se descarta
        val s = st.summary(id, defaultName(st.startMs))
        writeMeta(s)
        return s
    }
}
