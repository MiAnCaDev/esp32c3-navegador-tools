package dev.gpsnav.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Ajustes del MODO LACTANCIA que viven en el ESP32 (se guardan en su memoria NVS y sobreviven a reinicios):
 *   - habilitar / deshabilitar el modo (el ESP32 viene deshabilitado de fábrica)
 *   - URL de la API, token, intervalo de consulta y hasta 3 redes WiFi
 *
 * Protocolo (ver ESP32/DeviceConfig.h):  móvil -> ESP32  {"t":"cfg",...}   ·   ESP32 -> móvil  {"t":"cfg","lact":0,...}
 * El ESP32 nunca devuelve claves ni token, solo cuántos hay guardados.
 *
 * ABIERTO POR DEFECTO: cualquier móvil puede cambiar estos ajustes (así se pueden usar varios móviles sin hacer nada).
 * BLOQUEO OPCIONAL: si el usuario lo activa, el ESP32 solo obedece a móviles EMPAREJADOS (hasta 4). Para emparejar un móvil
 * hay que ver el ESP32: enseña un código de 6 cifras en su pantalla, se escribe aquí y la app genera una clave aleatoria de
 * 128 bits que el ESP32 añade a su lista. Cada móvil se empareja una vez; perderlos todos se arregla con el código de la pantalla.
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
    @Volatile var lastStatusAt = 0L       // System.currentTimeMillis() de la última respuesta
        private set
    @Volatile var espLock = -1            // -1 = no sabemos · 0 = abierto · 1 = bloqueado (solo móviles emparejados)
        private set
    @Volatile var espKeys = 0             // móviles emparejados
        private set
    @Volatile var espMine = -1            // la clave de ESTE móvil vale (solo importa con el bloqueo activo)
        private set

    // ---------------------------------------------------------------- emparejamiento
    private var pendingKey: String? = null        // clave propuesta, a la espera de que el ESP32 acepte el código
    @Volatile private var needPair = false        // la pantalla debe ofrecer emparejar
    @Volatile private var pairMsg: String? = null // resultado para enseñar una vez al usuario

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
    private fun key(): String = sp().getString("lc_key", "").orEmpty()
    fun hasKey() = key().isNotEmpty()

    /** El ESP32 está bloqueado y este móvil no está (o ya no está) en su lista: sin emparejar no aceptará ajustes. */
    fun needsPairing() = espLock == 1 && (!hasKey() || espMine == 0)

    fun lockOn() = espLock == 1

    /** Para restaurar una copia de seguridad en otro móvil (la clave solo se exporta si el usuario incluye las claves). */
    fun exportKey(): String = key()
    fun importKey(k: String): Boolean {
        if (!Regex("[0-9a-fA-F]{32}").matches(k)) return false
        sp().edit().putString("lc_key", k.lowercase()).apply()
        return true
    }

    /** Mensaje de ajustes. Los que cambian algo llevan la clave ("k"); get/pair/code van sin ella. */
    private fun cfg(auth: Boolean = true): JSONObject {
        val o = JSONObject().put("t", "cfg")
        if (auth && hasKey()) o.put("k", key())
        return o
    }
    private fun send(o: JSONObject) = BridgeHub.sink.send(Frame.json(o))

    // ---------------------------------------------------------------- acciones (hilo principal)
    /** Habilita/deshabilita el modo lactancia. Devuelve un texto para mostrar al usuario. */
    fun requestLact(enable: Boolean): String {
        sp().edit().putInt("lc_pending", if (enable) 1 else 0).putBoolean("lc_lact_known", enable).apply()
        if (!ready()) return "Guardado. Se enviará cuando el ESP32 se conecte."
        if (needsPairing()) { needPair = true; return "El ESP32 está bloqueado: primero hay que emparejar este móvil (código en su pantalla)." }
        send(cfg().put("lact", if (enable) 1 else 0)); send(cfg().put("get", 1))
        return if (enable) "Enviado. El botón del ESP32 ya reiniciará en modo lactancia." else "Enviado: modo lactancia deshabilitado."
    }

    /** Valida y manda servidor + WiFi. null = enviado; si no, el motivo. */
    fun sendNetwork(n: Net): String? {
        if (n.api.toByteArray().size > MAX_API) return "La URL es demasiado larga (máx. $MAX_API)"
        if (n.api.isNotEmpty() && !n.api.startsWith("https://", ignoreCase = true)) return "La URL debe empezar por https:// (con http el token viajaría sin cifrar)."
        if (n.tok.toByteArray().size > MAX_TOKEN) return "El token es demasiado largo (máx. $MAX_TOKEN)"
        for ((i, w) in n.wifi.withIndex()) {
            if (w.first.toByteArray().size > MAX_SSID) return "El nombre de la red ${i + 1} es demasiado largo (máx. $MAX_SSID)"
            if (w.second.toByteArray().size > MAX_PASS) return "La clave de la red ${i + 1} es demasiado larga (máx. $MAX_PASS)"
        }
        if (!ready()) return "El ESP32 no está conectado. Conéctalo y vuelve a pulsar «Guardar y enviar»."
        if (needsPairing()) { needPair = true; return "El ESP32 está bloqueado: primero hay que emparejar este móvil (código en su pantalla)." }
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
        if (needsPairing()) { needPair = true; return "El ESP32 está bloqueado: primero hay que emparejar este móvil (código en su pantalla)." }
        send(cfg().put("reset", 1)); send(cfg().put("get", 1))
        clearNet()
        AppLog.i("Lactancia", "Ajustes del ESP32 restaurados a los valores por defecto")
        return null
    }

    /** Quita el bloqueo (el ESP32 vuelve a obedecer a cualquier móvil y olvida las claves). Texto para el usuario. */
    fun requestUnlock(): String {
        if (!ready()) return "Conecta el ESP32 para cambiar esto."
        if (needsPairing()) { needPair = true; return "Primero hay que emparejar este móvil (código en la pantalla del ESP32)." }
        send(cfg().put("lock", 0)); send(cfg().put("get", 1))
        return "Bloqueo desactivado: cualquier móvil puede cambiar los ajustes del ESP32."
    }

    /** Paso 1: el ESP32 enseña un código de 6 cifras en su pantalla. null = pedido; si no, el motivo. */
    fun startPairing(): String? {
        if (!ready()) return "El ESP32 no está conectado."
        send(cfg(false).put("pair", 1))
        return null
    }

    /** Paso 2: se escribe ese código. La app propone una clave aleatoria nueva; el ESP32 la guarda si el código es correcto. */
    fun confirmPairing(code: String): String? {
        if (!ready()) return "El ESP32 no está conectado."
        val c = code.trim()
        if (!Regex("\\d{6}").matches(c)) return "El código tiene 6 cifras."
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val k = bytes.joinToString("") { "%02x".format(it) }
        pendingKey = k
        send(cfg(false).put("code", c).put("nk", k))
        return null
    }

    /** ¿Debe la pantalla ofrecer emparejar? (se consume) */
    fun consumeNeedPair(): Boolean { val v = needPair; needPair = false; return v }

    /** Resultado del último intento de emparejar para enseñárselo al usuario (se consume). */
    fun consumePairMessage(): String? { val m = pairMsg; pairMsg = null; return m }

    /** El ESP32 se acaba de (re)conectar: pregunta su estado y reenvía lo pendiente. */
    fun onLinkReady() {
        if (appCtx == null) return
        send(cfg().put("get", 1))
        val p = pendingLact()
        if (p >= 0 && !needsPairing()) send(cfg().put("lact", p))
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
        espLock = o.optInt("lock", espLock)
        espKeys = o.optInt("keys", espKeys)
        espMine = o.optInt("mine", espMine)
        val note = o.optString("note", "")
        when (note) {
            "wrongcode" -> { pendingKey = null; pairMsg = "Código incorrecto. Si fallas 3 veces hay que esperar un minuto." }
            "expired" -> { pendingKey = null; pairMsg = "El código ha caducado. Vuelve a pulsar «Emparejar»." }
            "wait" -> pairMsg = "Espera unos segundos antes de volver a intentarlo."
            "bad" -> { pendingKey = null; pairMsg = "El ESP32 ha rechazado la clave. Inténtalo de nuevo." }
            "auth" -> { needPair = true; pairMsg = "El ESP32 está bloqueado y no reconoce este móvil: hay que emparejarlo." }
            "paired" -> {                                   // el ESP32 aceptó el código: ahora esta clave es la válida
                val pk = pendingKey
                if (pk != null) {
                    sp().edit().putString("lc_key", pk).apply(); pendingKey = null
                    pairMsg = "Emparejado con el ESP32 ✓"
                    AppLog.i("Lactancia", "Emparejado con el ESP32")
                    val p = pendingLact()
                    if (p >= 0) send(cfg().put("lact", p))       // lo que estaba pendiente ya se puede enviar
                }
            }
        }
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

    fun lockText(): String = when (espLock) {
        1 -> "Solo móviles emparejados (${espKeys}/4) · este móvil: ${if (hasKey() && espMine != 0) "emparejado ✓" else "NO emparejado"}"
        0 -> "Desactivado: cualquier móvil puede cambiar los ajustes del ESP32"
        else -> "Conecta el ESP32 para ver su estado"
    }

    fun pairText(): String = when {
        espLock == 1 && hasKey() && espMine != 0 -> "Este móvil está emparejado ✓ · toca para volver a emparejar"
        espLock == 1 -> "Este móvil NO está emparejado · toca para emparejar (código en la pantalla del ESP32)"
        espLock == 0 -> "Para activar el bloqueo y emparejar este móvil (otros móviles se emparejan igual)"
        else -> "Conecta el ESP32 para emparejar"
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
