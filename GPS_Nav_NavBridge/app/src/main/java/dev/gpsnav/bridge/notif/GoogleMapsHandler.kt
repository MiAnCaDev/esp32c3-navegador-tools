package dev.gpsnav.bridge.notif

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.gpsnav.bridge.AppLog
import dev.gpsnav.bridge.BridgeHub
import dev.gpsnav.bridge.TestSignals
import dev.gpsnav.bridge.protocol.Frame
import dev.gpsnav.bridge.protocol.FrameSink
import org.json.JSONObject
import java.text.Normalizer
import java.util.zip.CRC32

/**
 * Notificación de navegación de Google Maps -> ESP32.
 *  - La flecha se manda COMO IMAGEN (la misma que muestra Google), solo cuando cambia.
 *  - Distancia (en metros) y calle se mandan como JSON {"t":"nav","instr":..,"distance":..,"action":..}.
 *    "action" es un identificador de la imagen: el ESP32 lo usa para reiniciar las alertas de aviso.
 */
class GoogleMapsHandler : NotificationHandler {
    override val id = "gmaps"
    override val packages = setOf("com.google.android.apps.maps")

    private val TAG = "NavBridge"
    private var lastIconCrc = -1L
    private var lastSig = ""
    private var warnedNoIcon = false

    override fun onLinkReady() { lastIconCrc = -1L; lastSig = "" }

    private fun isNav(n: Notification) =
        n.category == Notification.CATEGORY_NAVIGATION ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

    override fun onPosted(ctx: Context, sbn: StatusBarNotification, out: FrameSink) {
        if (TestSignals.gpsActive) return   // prueba GPS en curso: no pisar la pantalla del ESP32
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || !isNav(n)) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        Log.d(TAG, "Maps: title='$title' text='$text' sub='$sub' extras=${ex.keySet()}")

        // 1) Imagen de la maniobra
        var key = "none"
        val bmp = IconExtractor.extract(ctx, n)
        if (bmp != null) {
            val packed = IconProcessor.pack(bmp)
            val crc = CRC32().also { it.update(packed) }.value
            key = "i" + crc.toString(16)
            if (crc != lastIconCrc) {
                lastIconCrc = crc
                out.send(Frame.icon(0, IconProcessor.SIZE, IconProcessor.SIZE, packed))
                BridgeHub.lastIcon = IconProcessor.preview(packed)
                warnedNoIcon = false
                AppLog.i("Maps", "Icono nuevo enviado (${packed.size} B, id=$key)")
            }
        } else {
            Log.w(TAG, "Maps: no se pudo extraer ninguna imagen de la notificación")
            if (!warnedNoIcon) { warnedNoIcon = true; AppLog.w("Maps", "No se pudo extraer la imagen de la notificación") }
        }

        // 2) Texto y distancia
        val (instr, meters) = splitInstruction(title, text)
        val obj = JSONObject().put("t", "nav").put("instr", instr).put("action", key)
        if (meters >= 0) obj.put("distance", meters)
        val sig = obj.toString()
        if (sig != lastSig) {
            lastSig = sig
            out.send(Frame.json(obj))
            BridgeHub.lastNav = "$instr | ${if (meters >= 0) "$meters m" else "?"}"
            AppLog.d("Maps", "Nav: '$instr' ${if (meters >= 0) "$meters m" else "?"}")
        }
    }

    override fun onRemoved(ctx: Context, sbn: StatusBarNotification, out: FrameSink) {
        if (TestSignals.gpsActive) return
        if (!isNav(sbn.notification)) return
        lastSig = ""; lastIconCrc = -1L
        out.send(Frame.json(JSONObject().put("t", "nav")))   // sin instr/action = navegación terminada
        BridgeHub.lastNav = "(sin ruta)"
        AppLog.i("Maps", "Navegación terminada")
    }

    // ------------------------------------------------------------ parseo del texto
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s*(km|mi|m|ft|yd)\b""", RegexOption.IGNORE_CASE)

    private fun toMeters(m: MatchResult): Int {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1
        val f = when (m.groupValues[2].lowercase()) {
            "km" -> 1000.0; "mi" -> 1609.34; "ft" -> 0.3048; "yd" -> 0.9144; else -> 1.0
        }
        return (v * f).toInt()
    }

    /** Devuelve (instrucción, metros). Admite que la distancia venga en el título o en el texto. */
    private fun splitInstruction(title: String, text: String): Pair<String, Int> {
        val tm = DIST.find(title)
        val xm = DIST.find(text)
        return when {
            tm != null -> {
                // Caso habitual: "400 m · En la rotonda, toma la segunda salida" en el título
                val rest = clean(title.removeRange(tm.range))
                (if (rest.isNotBlank()) rest else clean(text)) to toMeters(tm)
            }
            xm != null -> {
                val rest = clean(text.removeRange(xm.range))
                (if (rest.isNotBlank()) rest else clean(title)) to toMeters(xm)
            }
            else -> clean("$title $text") to -1
        }
    }

    /** La fuente de la pantalla solo tiene ASCII: quitamos tildes y símbolos raros. */
    private fun clean(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return d.filter { it.code in 32..126 }.replace(Regex("\\s+"), " ").trim(' ', '-', ',', '.')
            .take(80)
    }
}
