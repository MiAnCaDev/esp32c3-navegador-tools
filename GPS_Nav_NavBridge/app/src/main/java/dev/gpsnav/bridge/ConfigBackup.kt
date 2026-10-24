package dev.gpsnav.bridge

import android.bluetooth.BluetoothAdapter
import android.content.Context
import dev.gpsnav.bridge.radar.RadarConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportar / importar TODA la configuración de la app en un archivo .json (por ejemplo, para cambiar de móvil).
 *
 * Incluye: ESP32 elegido, opciones de arranque, destino de la brújula, favoritas, avisos de radar y los ajustes de lactancia
 * (servidor, intervalo y redes WiFi; las claves y el token solo si el usuario lo decide al exportar).
 * No incluye datos que se pueden volver a obtener (base de radares, caché de límites de velocidad, logs) ni estados
 * temporales (servicio en marcha, último error...). El "habilitado" del modo lactancia vive en el propio ESP32.
 *
 * Importar sustituye solo las secciones que vengan en el archivo; las demás se quedan como están.
 */
object ConfigBackup {
    const val FORMAT = 1
    private const val MAX_BYTES = 1_000_000

    class Result(val lines: List<String>, val deviceChanged: Boolean)

    fun fileName(): String = "EspNav_config_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"

    /** ¿Hay algo sensible (token o clave WiFi) que preguntar si se incluye? */
    fun hasSecrets(): Boolean {
        val n = EspConfig.loadNet()
        return n.tok.isNotEmpty() || n.wifi.any { it.second.isNotEmpty() }
    }

    // ------------------------------------------------------------------ exportar
    fun export(ctx: Context, includeSecrets: Boolean): String {
        val o = JSONObject()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        o.put("app", "EspNav").put("format", FORMAT).put("appVersion", version)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .put("includesSecrets", includeSecrets)

        val addr = Prefs.deviceAddress(ctx)
        o.put("device", if (addr == null) JSONObject.NULL
            else JSONObject().put("address", addr).put("name", Prefs.deviceName(ctx) ?: ""))
        o.put("startup", JSONObject().put("autoStartBoot", Prefs.autoStartBoot(ctx)).put("autoRestart", Prefs.autoRestart(ctx)))

        val d = Destination.current
        o.put("destination", if (d == null) JSONObject.NULL
            else JSONObject().put("lat", d.lat).put("lon", d.lon).put("name", d.name))

        val fav = JSONArray()
        Favorites.list().forEach { fav.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        o.put("favorites", fav)

        val c = RadarConfig.current
        o.put("radar", JSONObject()
            .put("enabled", c.enabled).put("overspeedAlert", c.overspeedAlert)
            .put("idleDistance", c.idleDistance).put("roadLimits", c.roadLimits)
            .put("slowBelowKmh", c.slowBelow).put("fastAboveKmh", c.fastAbove)
            .put("alertM", JSONObject().put("fast", c.alertFast).put("mid", c.alertMid).put("slow", c.alertSlow))
            .put("showM", JSONObject().put("fast", c.showFast).put("mid", c.showMid).put("slow", c.showSlow))
            .put("stepM", JSONObject().put("fast", c.stepFast).put("mid", c.stepMid).put("slow", c.stepSlow))
            .put("alertSeconds", c.alertSeconds).put("overMarginKmh", c.overMargin))

        val n = EspConfig.loadNet()
        val wifi = JSONArray()
        n.wifi.forEach { (ssid, pass) ->
            val w = JSONObject().put("ssid", ssid)
            if (includeSecrets) w.put("pass", pass)
            wifi.put(w)
        }
        val lact = JSONObject().put("api", n.api).put("pollSec", n.pollSec).put("wifi", wifi)
        if (includeSecrets) lact.put("token", n.tok)
        o.put("lactancia", lact)
        return o.toString(2)
    }

    // ------------------------------------------------------------------ importar
    /** Lee el archivo con un límite de tamaño (un archivo de configuración pesa unos pocos KB). */
    fun readText(s: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = s.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_BYTES) throw IllegalArgumentException("El archivo es demasiado grande para ser una configuración.")
        }
        return out.toString("UTF-8")
    }

    /** Valida el archivo. Lanza IllegalArgumentException con el motivo si no sirve. */
    fun parse(text: String): JSONObject {
        val o = try { JSONObject(text) } catch (e: JSONException) {
            throw IllegalArgumentException("El archivo no es un JSON válido.")
        }
        if (o.optString("app") != "EspNav") throw IllegalArgumentException("No es un archivo de configuración de Esp Nav.")
        val f = o.optInt("format", 0)
        if (f < 1 || f > FORMAT) throw IllegalArgumentException("Formato no compatible (versión $f). Actualiza la app.")
        return o
    }

    /** Qué contiene el archivo (para confirmar antes de aplicar). */
    fun describe(o: JSONObject): List<String> = process(null, o).lines

    /** Aplica el archivo. Valida cada valor; lo que no cumpla se ignora. */
    fun apply(ctx: Context, o: JSONObject): Result = process(ctx, o)

    private fun process(ctx: Context?, o: JSONObject): Result {
        val lines = ArrayList<String>()
        var deviceChanged = false

        if (o.has("device")) {
            val d = o.optJSONObject("device")
            if (d == null) {
                lines.add("ESP32: automático")
                if (ctx != null) { Prefs.setDevice(ctx, null, null); deviceChanged = true }
            } else {
                val a = d.optString("address", "")
                if (BluetoothAdapter.checkBluetoothAddress(a)) {
                    val name = d.optString("name", "").ifEmpty { null }
                    lines.add("ESP32 elegido: ${name ?: a}")
                    if (ctx != null) { Prefs.setDevice(ctx, a, name); deviceChanged = true }
                }
            }
        }

        o.optJSONObject("startup")?.let { s ->
            lines.add("Opciones de arranque")
            if (ctx != null) {
                if (s.has("autoStartBoot")) Prefs.setAutoStartBoot(ctx, s.optBoolean("autoStartBoot", true))
                if (s.has("autoRestart")) Prefs.setAutoRestart(ctx, s.optBoolean("autoRestart", true))
            }
        }

        if (o.has("destination")) {
            val d = o.optJSONObject("destination")
            if (d == null) {
                lines.add("Destino de la brújula: ninguno")
                if (ctx != null) Destination.set(ctx, null)
            } else {
                val la = d.optDouble("lat", Double.NaN); val lo = d.optDouble("lon", Double.NaN)
                if (!la.isNaN() && !lo.isNaN() && la in -90.0..90.0 && lo in -180.0..180.0) {
                    val name = Destination.cleanName(d.optString("name", "Destino"))
                    lines.add("Destino de la brújula: $name")
                    if (ctx != null) Destination.set(ctx, Destination.Dest(la, lo, name))
                }
            }
        }

        o.optJSONArray("favorites")?.let { arr ->
            val favs = ArrayList<Favorites.Fav>()
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val la = f.optDouble("lat", Double.NaN); val lo = f.optDouble("lon", Double.NaN)
                if (la.isNaN() || lo.isNaN() || la !in -90.0..90.0 || lo !in -180.0..180.0) continue
                favs.add(Favorites.Fav(Destination.cleanName(f.optString("name", "Favorita")), la, lo))
                if (favs.size >= Favorites.MAX) break
            }
            lines.add("Ubicaciones favoritas: ${favs.size}")
            if (ctx != null) Favorites.replaceAll(ctx, favs)
        }

        o.optJSONObject("radar")?.let { r ->
            lines.add("Avisos de radar")
            if (ctx != null) {
                val c = RadarConfig.current
                val a = r.optJSONObject("alertM"); val s = r.optJSONObject("showM"); val st = r.optJSONObject("stepM")
                RadarConfig.save(ctx, c.copy(
                    enabled = r.optBoolean("enabled", c.enabled),
                    overspeedAlert = r.optBoolean("overspeedAlert", c.overspeedAlert),
                    idleDistance = r.optBoolean("idleDistance", c.idleDistance),
                    roadLimits = r.optBoolean("roadLimits", c.roadLimits),
                    slowBelow = r.optInt("slowBelowKmh", c.slowBelow), fastAbove = r.optInt("fastAboveKmh", c.fastAbove),
                    alertFast = a?.optInt("fast", c.alertFast) ?: c.alertFast,
                    alertMid = a?.optInt("mid", c.alertMid) ?: c.alertMid,
                    alertSlow = a?.optInt("slow", c.alertSlow) ?: c.alertSlow,
                    showFast = s?.optInt("fast", c.showFast) ?: c.showFast,
                    showMid = s?.optInt("mid", c.showMid) ?: c.showMid,
                    showSlow = s?.optInt("slow", c.showSlow) ?: c.showSlow,
                    stepFast = st?.optInt("fast", c.stepFast) ?: c.stepFast,
                    stepMid = st?.optInt("mid", c.stepMid) ?: c.stepMid,
                    stepSlow = st?.optInt("slow", c.stepSlow) ?: c.stepSlow,
                    alertSeconds = r.optInt("alertSeconds", c.alertSeconds),
                    overMargin = r.optInt("overMarginKmh", c.overMargin)
                ))                                              // save() corrige valores fuera de rango
            }
        }

        o.optJSONObject("lactancia")?.let { l ->
            val cur = EspConfig.loadNet()
            val arr = l.optJSONArray("wifi")
            val wifi = (0 until EspConfig.MAX_WIFI).map { i ->
                val w = arr?.optJSONObject(i)
                val ssid = (w?.optString("ssid", "") ?: "").take(EspConfig.MAX_SSID)
                // sin "pass" en el archivo (exportado sin claves): se conserva la clave actual si la red es la misma
                val pass = if (w != null && w.has("pass")) w.optString("pass", "").take(EspConfig.MAX_PASS)
                           else if (ssid.isNotEmpty() && cur.wifi.getOrNull(i)?.first == ssid) cur.wifi[i].second else ""
                ssid to pass
            }
            val token = if (l.has("token")) l.optString("token", "").take(EspConfig.MAX_TOKEN) else cur.tok
            val net = EspConfig.Net(
                api = l.optString("api", "").take(EspConfig.MAX_API), tok = token,
                pollSec = l.optInt("pollSec", 0).coerceIn(0, 3600), wifi = wifi
            )
            val nets = wifi.count { it.first.isNotEmpty() }
            lines.add("Lactancia: ${if (net.api.isNotEmpty()) "servidor propio, " else ""}$nets red${if (nets == 1) "" else "es"} WiFi" +
                if (o.optBoolean("includesSecrets", false)) " (con claves)" else "")
            if (ctx != null) EspConfig.saveNet(net)
        }
        return Result(lines, deviceChanged)
    }
}
