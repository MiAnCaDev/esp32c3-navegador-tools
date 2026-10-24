package dev.gpsnav.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject

/**
 * Ajustes del MODO LACTANCIA que viven en el ESP32 (se guardan en su memoria NVS y sobreviven a reinicios):
 *   - habilitar / deshabilitar el modo (el ESP32 viene deshabilitado de fábrica)
 *   - URL de la API, token, intervalo de consulta y hasta 3 redes WiFi
 *
 * Protocolo (ver ESP32/DeviceConfig.h):  móvil -> ESP32  {"t":"cfg",...}   ·   ESP32 -> móvil  {"t":"cfg","lact":0,...}
 * El ESP32 nunca devuelve claves ni token, solo cuántos hay guardados.
 *
 * Qué manda quién: el valor de "habilitado" que el usuario elige se guarda aquí como PENDIENTE y se envía en cuanto hay
 * conexión (y se repite en cada conexión hasta que el ESP32 lo confirma), así que se puede cambiar sin tener el ESP32 a mano.
 */
object EspConfig {
    private const val SP = "espnav"
    const val MAX_WIFI = 3
    const val MAX_API = 190          // el ESP32 guarda hasta 199 bytes
    const val MAX_TOKEN = 70
    const val MAX_SSID = 32
    const val MAX_PASS = 64

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    // ---------------------------------------------------------------- lo que dice el ESP32
    @Volatile var espLact = -1            // -1 = aún no sabemos · 0 = deshabilitado · 1 = habilitado
        private set
    @Volatile var espApi = false          // ¿tiene una URL guardada desde la app?
        private set
    @Volatile var espTok = false
        private set
    @Volatile var espWifi = 0             // nº de redes guardadas desde la app
        private set
    @Volatile var espPoll = 0             // segundos (0 = el del firmware)
        private set
    @Volatile var lastStatusAt = 0L       // SystemClock-agnostic: System.currentTimeMillis()
        private set

    // ---------------------------------------------------------------- lo que hay guardado en la app
    class Net(val api: String, val tok: String, val pollSec: Int, val wifi: List<Pair<String, String>>)

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private fun sp() = appCtx!!.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** -1 = nada pendiente; 0/1 = valor que falta por confirmar en el ESP32. */
    fun pendingLact(): Int = sp().getInt("lc_pending", -1)

    /** Lo que mostrar en el interruptor: lo pendiente, si no lo último que dijo el ESP32, si no lo último conocido. */
    fun shownLact(): Boolean {
        val p = pendingLact()
        if (p >= 0) return p == 1
        if (espLact >= 0) return espLact == 1
        return sp().getBoolean("lc_lact_known", false)
    }

    fun loadNet(): Net {
        val p = sp()
        val w = (0 until MAX_WIFI).map { p.getString("lc_w${it}s", "").orEmpty() to p.getString("lc_w${it}p", "").orEmpty() }
        return Net(p.getString("lc_api", "").orEmpty(), p.getString("lc_tok", "").orEmpty(), p.getInt("lc_poll", 0), w)
    }

    fun saveNet(n: Net) {
        val e = sp().edit().putString("lc_api", n.api).putString("lc_tok", n.tok).putInt("lc_poll", n.pollSec)
        n.wifi.forEachIndexed { i, (s, p) -> e.putString("lc_w${i}s", s).putString("lc_w${i}p", p) }
        e.apply()
    }

    private fun clearNet() {
        val e = sp().edit().remove("lc_api").remove("lc_tok").remove("lc_poll")
        for (i in 0 until MAX_WIFI) e.remove("lc_w${i}s").remove("lc_w${i}p")
        e.apply()
    }

    private fun ready() = BridgeHub.link?.ready == true
    private fun cfg() = JSONObject().put("t", "cfg")
    private fun send(o: JSONObject) = BridgeHub.sink.send(Frame.json(o))

    // ---------------------------------------------------------------- acciones (hilo principal)
    /** Habilita/deshabilita el modo lactancia. Devuelve un texto para mostrar al usuario. */
    fun requestLact(enable: Boolean): String {
        sp().edit().putInt("lc_pending", if (enable) 1 else 0).putBoolean("lc_lact_known", enable).apply()
        return if (ready()) {
            send(cfg().put("lact", if (enable) 1 else 0)); send(cfg().put("get", 1))
            if (enable) "Enviado. El botón del ESP32 ya reiniciará en modo lactancia." else "Enviado: modo lactancia deshabilitado."
        } else "Guardado. Se enviará cuando el ESP32 se conecte."
    }

    /** Valida y manda servidor + WiFi. null = enviado; si no, el motivo. */
    fun sendNetwork(n: Net): String? {
        if (n.api.toByteArray().size > MAX_API) return "La URL es demasiado larga (máx. $MAX_API)"
        if (n.tok.toByteArray().size > MAX_TOKEN) return "El token es demasiado largo (máx. $MAX_TOKEN)"
        for ((i, w) in n.wifi.withIndex()) {
            if (w.first.toByteArray().size > MAX_SSID) return "El nombre de la red ${i + 1} es demasiado largo (máx. $MAX_SSID)"
            if (w.second.toByteArray().size > MAX_PASS) return "La clave de la red ${i + 1} es demasiado larga (máx. $MAX_PASS)"
        }
        if (!ready()) return "El ESP32 no está conectado. Conéctalo y vuelve a pulsar «Guardar y enviar»."
        send(cfg().put("api", n.api))
        send(cfg().put("tok", n.tok))
        send(cfg().put("poll", n.pollSec.coerceIn(0, 3600)))
        for ((i, w) in n.wifi.withIndex()) send(cfg().put("wi", i).put("s", w.first).put("p", w.second))
        send(cfg().put("get", 1))
        AppLog.i("Lactancia", "Ajustes enviados al ESP32 (api=${n.api.isNotEmpty()}, redes=${n.wifi.count { it.first.isNotEmpty() }})")
        return null
    }

    /** Borra los ajustes del ESP32 y los de la app: vuelven los valores por defecto del firmware. */
    fun sendReset(): String? {
        if (!ready()) return "El ESP32 no está conectado."
        send(cfg().put("reset", 1)); send(cfg().put("get", 1))
        clearNet()
        AppLog.i("Lactancia", "Ajustes del ESP32 restaurados a los valores por defecto")
        return null
    }

    /** El ESP32 se acaba de (re)conectar: pregunta su estado y reenvía lo pendiente. */
    fun onLinkReady() {
        if (appCtx == null) return
        send(cfg().put("get", 1))
        val p = pendingLact()
        if (p >= 0) send(cfg().put("lact", p))
    }

    /** Llamado desde BleLink (hilo de Bluetooth) con lo que notifica el ESP32. */
    fun onEspMessage(raw: String) {
        for (line in raw.split('\n')) {
            if (!line.contains("\"cfg\"")) continue
            val o = runCatching { JSONObject(line.trim()) }.getOrNull() ?: continue
            main.post { apply(o) }
        }
    }

    private fun apply(o: JSONObject) {
        if (appCtx == null) return
        espLact = o.optInt("lact", espLact)
        espApi = o.optInt("api", 0) == 1
        espTok = o.optInt("tok", 0) == 1
        espWifi = o.optInt("wifi", 0)
        espPoll = o.optInt("poll", 0)
        lastStatusAt = System.currentTimeMillis()
        if (espLact >= 0) {
            val p = sp()
            val e = p.edit().putBoolean("lc_lact_known", espLact == 1)
            if (p.getInt("lc_pending", -1) == espLact) e.putInt("lc_pending", -1)     // confirmado
            e.apply()
        }
        AppLog.i("Lactancia", "ESP32: lactancia=${espLact} api=$espApi token=$espTok wifi=$espWifi poll=${espPoll}s")
    }

    // ---------------------------------------------------------------- textos para la pantalla
    fun statusText(): String {
        if (pendingLact() >= 0) return "Pendiente: se aplicará cuando el ESP32 confirme"
        return when (espLact) {
            1 -> "ESP32: habilitado ✓"
            0 -> "ESP32: deshabilitado"
            else -> "ESP32: sin datos (conéctalo para ver el estado)"
        }
    }

    fun netSummary(): String {
        val n = loadNet()
        val saved = ArrayList<String>()
        if (n.api.isNotEmpty()) saved.add("servidor propio")
        val w = n.wifi.count { it.first.isNotEmpty() }
        if (w > 0) saved.add("$w red${if (w > 1) "es" else ""} WiFi")
        if (n.pollSec > 0) saved.add("cada ${n.pollSec} s")
        val a = if (saved.isEmpty()) "Valores por defecto del firmware" else saved.joinToString(" · ")
        return if (lastStatusAt == 0L) a else "$a  (ESP32: ${espWifi} red${if (espWifi == 1) "" else "es"}${if (espApi) ", API propia" else ""})"
    }
}
