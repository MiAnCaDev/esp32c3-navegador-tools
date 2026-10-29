package dev.gpsnav.bridge.moto

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exporta un viaje a GPX (ruta + curvas como puntos de interés) o CSV (todos los datos). */
object TripExport {
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun n(d: Double, dec: Int) = if (d.isNaN()) "" else String.format(Locale.US, "%.${dec}f", d)

    /**
     * GPX 1.1. Cada punto lleva velocidad, inclinación y g como extensión (espacio de nombres "moto"); las apps que no la
     * conocen la ignoran y siguen mostrando la ruta. Cada curva sale como punto de interés ("Curva izq 38°").
     */
    fun writeGpx(store: TripStore, s: TripSummary, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<gpx version=\"1.1\" creator=\"EspNav\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:moto=\"https://espnav.local/moto/1\">\n")
        w.write("  <metadata><name>${x(s.name)}</name><time>${iso(s.startMs)}</time></metadata>\n")
        // 1.ª pasada: las curvas (GPX exige los puntos de interés antes de la ruta)
        store.forEach(s.id) { d ->
            if (d is Curve) {
                val side = if (d.peakLean > 0) "izq" else "der"
                w.write("  <wpt lat=\"${n(d.lat, 6)}\" lon=\"${n(d.lon, 6)}\"><time>${iso(d.startMs)}</time>" +
                    "<name>Curva $side ${abs(d.peakLean).roundToInt()}°</name>" +
                    "<desc>Inclinación ${n(abs(d.peakLean), 0)}° a ${(d.speedMs * 3.6).roundToInt()} km/h · ${n(d.durationMs / 1000.0, 1)} s</desc></wpt>\n")
            }
        }
        // 2.ª pasada: la ruta
        w.write("  <trk>\n    <name>${x(s.name)}</name>\n    <trkseg>\n")
        store.forEach(s.id) { d ->
            if (d is TrackPoint) {
                w.write("      <trkpt lat=\"${n(d.lat, 6)}\" lon=\"${n(d.lon, 6)}\">")
                if (!d.alt.isNaN()) w.write("<ele>${n(d.alt, 1)}</ele>")
                w.write("<time>${iso(d.tMs)}</time><extensions>")
                if (!d.speedMs.isNaN()) w.write("<moto:speed>${n(d.speedMs, 2)}</moto:speed>")
                if (!d.lean.isNaN()) w.write("<moto:lean>${n(d.lean, 1)}</moto:lean>")
                if (!d.latG.isNaN()) w.write("<moto:latg>${n(d.latG, 2)}</moto:latg>")
                if (!d.longG.isNaN()) w.write("<moto:longg>${n(d.longG, 2)}</moto:longg>")
                w.write("</extensions></trkpt>\n")
            }
        }
        w.write("    </trkseg>\n  </trk>\n</gpx>\n")
        w.flush()
    }

    /** CSV con punto decimal y comas. lean_left_deg: positivo = izquierda, negativo = derecha. */
    fun writeCsv(store: TripStore, s: TripSummary, out: OutputStream) {
        val w = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        w.write("time_utc,lat,lon,alt_m,speed_kmh,bearing_deg,accuracy_m,lean_left_deg,lean_peak_left_deg,lateral_g,longitudinal_g\n")
        store.forEach(s.id) { d ->
            if (d is TrackPoint) {
                w.write("${iso(d.tMs)},${n(d.lat, 6)},${n(d.lon, 6)},${n(d.alt, 1)},${n(d.speedMs * 3.6, 1)},${n(d.bearing, 0)}," +
                    "${n(d.acc, 0)},${n(d.lean, 1)},${n(d.leanPeak, 1)},${n(d.latG, 2)},${n(d.longG, 2)}\n")
            }
        }
        w.flush()
    }
}
