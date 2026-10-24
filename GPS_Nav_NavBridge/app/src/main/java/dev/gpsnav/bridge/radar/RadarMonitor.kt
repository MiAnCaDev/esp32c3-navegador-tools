package dev.gpsnav.bridge.radar

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.gpsnav.bridge.AppLog
import dev.gpsnav.bridge.BridgeHub
import dev.gpsnav.bridge.protocol.Frame
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cerebro de los avisos de radar. TODO el cálculo ocurre aquí, en el móvil; el ESP32 solo dibuja lo que le llega:
 *
 *   {"t":"radar","on":1,"n":1,"s":1,"d":850,"txt":"Fijo N-340 90"}   -> mostrar alerta roja
 *        n   = número de aviso (1 = a la distancia X, 2 = a la mitad)
 *        s   = 1 solo en el primer mensaje de cada aviso: el ESP32 hace sonar el pitido de radar (si no está en silencio)
 *        d   = metros hasta el radar; SOLO viene si cumple la condición de velocidad/distancia de los ajustes.
 *              Va redondeado hacia arriba a pasos de 100/50/20 m según la velocidad y solo se manda cuando cambia
 *        ov  = 1 si vas por encima del límite de la vía en el radar: línea BLANCA en el borde derecho, sobre la alerta roja
 *   {"t":"radar","on":0,"d":600,"ov":1}                               -> sin alerta roja: en la pantalla normal, pastilla con
 *                                                                        los metros al radar (d) y, si vas pasado de velocidad
 *                                                                        (ov), línea ROJA en el borde derecho; ambas hasta pasar
 *                                                                        el radar
 *        txt = texto corto
 *   {"t":"radar","on":0}                                              -> quitar la alerta
 *
 * Mientras un aviso está activo solo se manda un mensaje cuando cambia algo (la distancia mostrada, el exceso de
 * velocidad o el nº de aviso), más un mensaje de "sigo aquí" cada [KEEPALIVE_MS]. Si el móvil se cae, el ESP32
 * quita la alerta solo (ver el temporizador de seguridad del firmware).
 *
 * Se llama una vez por fix de GPS (≈1 Hz) desde BridgeService, en el hilo principal.
 */
object RadarMonitor {
    private const val MIN_SPEED_KMH = 7f        // por debajo no hay rumbo fiable: no se avisa
    private const val AHEAD_DEG = 50.0          // el radar debe estar dentro de ±50° de la dirección de marcha
    private const val RECEDE_M = 25.0           // si la distancia crece 25 m sobre el mínimo, ya se ha pasado
    private const val PASSED_MS = 5 * 60_000L   // un radar ya avisado/pasado no vuelve a avisar en 5 min
    private const val PREFETCH_M = 3000.0       // a partir de aquí se pide el límite de la vía del siguiente radar
    private const val KEEPALIVE_MS = 4000L      // si no cambia nada, se repite el último mensaje cada 4 s

    /** Distancia que se muestra: redondeada HACIA ARRIBA a múltiplos de [step] ("a menos de X m"). */
    fun quant(d: Double, step: Int): Int = (ceil(d / step) * step).toInt()

    private val main = Handler(Looper.getMainLooper())

    // ---- estado del seguimiento ----
    private var gen = -1                        // generación de la base con la que se calcularon los índices
    private var target = -1
    private var minDist = Double.MAX_VALUE
    private var fired1 = false
    private var fired2 = false
    private val passed = HashMap<Int, Long>()

    // ---- aviso en pantalla ----
    private var windowUntil = 0L
    private var windowStage = 0
    private var soundPending = false
    private var lastD = -2                      // última distancia enviada (-1 = sin metros, -2 = nada enviado aún)
    private var lastLim = 0
    private var lastSentAt = 0L

    // ---- lo que el ESP32 está mostrando según el último mensaje enviado ----
    private var espAlert = false                // pantalla roja de radar
    private var espOv = false                   // línea de exceso de velocidad (blanca sobre la alerta / roja sin ella)
    private var lastIdleD = -1                  // metros que muestra la pastilla de la pantalla normal (-1 = ninguna)

    // ================================================================ servicio
    fun onLocation(loc: Location) {
        val cfg = RadarConfig.current
        if (testActive) return
        val db = RadarDb.snapshot()
        if (!cfg.enabled || db.size == 0 || BridgeHub.link?.ready != true) {
            reset(sendOff = BridgeHub.link?.ready == true)      // desactivado desde ajustes: quita la alerta ya
            return
        }

        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        if (kmh < MIN_SPEED_KMH || !loc.hasBearing()) { reset(sendOff = true); return }
        val now = SystemClock.elapsedRealtime()
        val heading = loc.bearing.toDouble()
        if (gen != RadarDb.generation) {                        // la base se ha actualizado: los índices viejos ya no valen
            gen = RadarDb.generation; passed.clear(); reset(sendOff = true)
        }
        if (passed.isNotEmpty()) passed.entries.removeAll { it.value < now }

        // 1) radar más cercano por delante
        val maxR = cfg.maxAlertDist().toDouble()
        var best = -1; var bestD = Double.MAX_VALUE
        var pre = -1; var preD = Double.MAX_VALUE                // siguiente radar por delante a <3 km (para pedir su límite)
        val kx = cos(Math.toRadians(loc.latitude)) * 111_320.0   // metros por grado de longitud aquí (se calcula una vez)
        for (i in db.near(loc.latitude, loc.longitude)) {
            if (passed.containsKey(i)) continue
            val r = db[i]
            val dx = (r.lon - loc.longitude) * kx; val dy = (r.lat - loc.latitude) * 110_540.0
            val d2 = dx * dx + dy * dy
            if (d2 > PREFETCH_M * PREFETCH_M) continue         // lo normal: casi todos caen aquí sin más cálculo
            val d = sqrt(d2)
            val keepAsTarget = i == target && d < 60.0          // a pocos metros el rumbo es ruido
            if (!keepAsTarget &&
                Geo.angleDiff(Geo.bearing(loc.latitude, loc.longitude, r.lat, r.lon), heading) > AHEAD_DEG) continue
            if (d < preD) { pre = i; preD = d }
            if (d <= maxR && d < bestD) { best = i; bestD = d }
        }
        // El límite de la vía se averigua con antelación (una vez por radar; luego queda guardado en el móvil)
        if (pre >= 0) RoadLimit.limitFor(db[pre], heading)

        if (best != target) {                                   // objetivo nuevo (o ninguno)
            clearEsp(send = true)                               // quita alerta y línea de exceso del radar anterior
            target = best; minDist = Double.MAX_VALUE; fired1 = false; fired2 = false
        }
        if (target < 0) return

        // 2) ¿ya lo hemos pasado?
        val d = bestD
        if (d < minDist) minDist = d
        if (d > minDist + RECEDE_M) {
            passed[target] = now + PASSED_MS
            AppLog.i("Radar", "Radar #$target pasado (${minDist.roundToInt()} m)")
            clearEsp(send = true)                               // al pasar el radar se quita también la línea roja
            target = -1
            return
        }

        // 3) ¿toca avisar? (1.º a X, 2.º a X/2; X depende de la velocidad)
        val x = cfg.alertDist(kmh); val x2 = x / 2
        if (!fired1 && d <= x) {
            fired1 = true
            if (d <= x2) { fired2 = true; startWindow(2, now, cfg, d, kmh) } else startWindow(1, now, cfg, d, kmh)
        } else if (!fired2 && d <= x2) {
            fired2 = true
            startWindow(2, now, cfg, d, kmh)
        }

        // 4) estado hacia el ESP32: alerta roja mientras dura la ventana; después solo la línea roja si vas pasado,
        //    y eso hasta que pases el radar (o dejes de ir por encima del límite)
        val lim = RoadLimit.limitFor(db[target], heading)
        val over = cfg.overspeedAlert && fired1 && lim > 0 && kmh.roundToInt() > lim + cfg.overMargin
        // Distancia en la pantalla normal: misma regla de velocidad/metros que en la alerta, y solo tras el primer aviso
        val idleQ = if (cfg.idleDistance && fired1 && d <= cfg.showDist(kmh)) quant(d, cfg.step(kmh)) else -1
        if (windowStage != 0 && now >= windowUntil) { windowStage = 0; soundPending = false }   // fin de la alerta roja
        if (windowStage != 0) sendFrame(windowStage, d, kmh, cfg, db[target], now, lim, over)
        else sendIdle(over, idleQ, now)
    }

    private fun startWindow(stage: Int, now: Long, cfg: RadarConfig, d: Double, kmh: Float) {
        windowStage = stage
        windowUntil = now + cfg.alertSeconds * 1000L
        soundPending = true
        lastD = -2; lastLim = 0; lastSentAt = 0L             // fuerza el primer mensaje del aviso
        AppLog.i("Radar", "AVISO $stage: radar #$target a ${d.roundToInt()} m, ${kmh.roundToInt()} km/h " +
            "(X=${cfg.alertDist(kmh)} m)")
        BridgeHub.lastNav = "RADAR $stage | ${d.roundToInt()} m"
    }

    /** Quita del ESP32 la alerta y la línea de exceso (solo manda algo si había algo en pantalla). */
    private fun clearEsp(send: Boolean) {
        windowStage = 0; soundPending = false
        if (send && (espAlert || espOv || lastIdleD >= 0))
            BridgeHub.sink.send(Frame.json(JSONObject().put("t", "radar").put("on", 0)))
        espAlert = false; espOv = false; lastIdleD = -1
    }

    private fun reset(sendOff: Boolean) {
        target = -1; minDist = Double.MAX_VALUE; fired1 = false; fired2 = false
        clearEsp(sendOff)
    }

    /** Alerta roja en pantalla. Solo se manda si cambia algo (metros mostrados, exceso, límite) o como "sigo aquí". */
    private fun sendFrame(stage: Int, d: Double, kmh: Float, cfg: RadarConfig, r: Radar, now: Long, lim: Int, over: Boolean) {
        val showD = d <= cfg.showDist(kmh)
        val q = if (showD) quant(d, cfg.step(kmh)) else -1
        val forced = soundPending
        if (!forced && espAlert && q == lastD && over == espOv && lim == lastLim && now - lastSentAt < KEEPALIVE_MS) return

        val o = JSONObject().put("t", "radar").put("on", 1).put("n", stage).put("s", if (forced) 1 else 0)
            .put("txt", label(r, lim))
        if (q >= 0) o.put("d", q)
        if (over) o.put("ov", 1)                                 // línea blanca en el borde derecho
        soundPending = false
        lastD = q; lastLim = lim; lastSentAt = now; espAlert = true; espOv = over; lastIdleD = -1
        BridgeHub.sink.send(Frame.json(o))
    }

    /**
     * Sin alerta roja: pastilla con los metros al radar y/o línea roja de exceso, hasta pasar el radar.
     * Solo se manda si cambia algo (el valor mostrado, el exceso), más un "sigo aquí" cada 4 s.
     */
    private fun sendIdle(over: Boolean, q: Int, now: Long) {
        val has = over || q >= 0
        if (!espAlert && over == espOv && q == lastIdleD && (!has || now - lastSentAt < KEEPALIVE_MS)) return   // nada nuevo
        val o = JSONObject().put("t", "radar").put("on", 0)
        if (q >= 0) o.put("d", q)
        if (over) o.put("ov", 1)
        espAlert = false; espOv = over; lastIdleD = q; lastSentAt = now
        BridgeHub.sink.send(Frame.json(o))
    }

    private fun label(r: Radar, lim: Int): String {
        val kind = if (r.kind == 1) "Tramo" else "Fijo"
        return Geo.ascii(listOf(kind, r.road, if (lim > 0) lim.toString() else "").filter { it.isNotEmpty() }
            .joinToString(" "), 28)
    }

    // ================================================================ pruebas
    enum class TestCase(val label: String) {
        FIRST("Aviso 1 (con distancia)"),
        SECOND("Aviso 2 (mitad de distancia)"),
        SEQUENCE("Secuencia: aviso 1 + aviso 2"),
        NO_DISTANCE("Aviso sin metros"),
        SPEEDING("Exceso de velocidad (línea blanca y luego roja)"),
    }

    private const val IDLE_TEST_MS = 6000L       // en las pruebas: cuánto dura la fase "pantalla normal" tras la alerta

    @Volatile var testActive = false
        private set
    private val testRuns = ArrayList<Runnable>()

    /** null si todo bien, o el motivo del fallo. Manda al ESP32 los mismos mensajes que mandaría un radar real. */
    fun startTest(c: TestCase): String? {
        if (BridgeHub.link?.ready != true)
            return "El ESP32 no está conectado. Pulsa «Iniciar» y espera a ver «Conectado»."
        cancelTest(sendOff = false)
        reset(sendOff = false)
        testActive = true
        val cfg = RadarConfig.current
        val sec = cfg.alertSeconds
        val x = cfg.alertFast

        fun alert(t0Ms: Long, stage: Int, startM: Int, withDist: Boolean, speeding: Boolean = false) {
            for (s in 0 until sec) {
                val o = JSONObject().put("t", "radar").put("on", 1).put("n", stage)
                    .put("s", if (s == 0) 1 else 0).put("txt", "Prueba Fijo 90")
                // simula ~108 km/h acercándose; la distancia va en pasos como en un aviso real
                if (withDist) o.put("d", quant(maxOf(startM - s * 30, 20).toDouble(), cfg.stepFast))
                // exceso: línea blanca en el borde derecho desde el 2.º segundo, mientras dura la alerta roja
                if (withDist && speeding && s >= 1) o.put("ov", 1)
                post(t0Ms + s * 1000L) { BridgeHub.sink.send(Frame.json(o)) }
            }
            val tEnd = t0Ms + sec * 1000L
            if (withDist) {
                // acaba la alerta roja: en la pantalla normal queda la distancia (y la línea roja si sigues pasado)
                // hasta "pasar el radar" (aquí, 6 s) y luego se quita todo
                for (k in 0 until 3) {
                    val dd = quant(maxOf(startM - sec * 30 - k * 60, 20).toDouble(), cfg.stepFast)
                    post(tEnd + k * 2000L) {
                        val o = JSONObject().put("t", "radar").put("on", 0).put("d", dd)
                        if (speeding) o.put("ov", 1)
                        BridgeHub.sink.send(Frame.json(o))
                    }
                }
                post(tEnd + IDLE_TEST_MS) { BridgeHub.sink.send(Frame.json(JSONObject().put("t", "radar").put("on", 0))) }
            } else {
                post(tEnd) { BridgeHub.sink.send(Frame.json(JSONObject().put("t", "radar").put("on", 0))) }
            }
        }

        val total: Long = when (c) {
            TestCase.FIRST -> { alert(0, 1, x, true); sec * 1000L + IDLE_TEST_MS }
            TestCase.SECOND -> { alert(0, 2, x / 2, true); sec * 1000L + IDLE_TEST_MS }
            TestCase.NO_DISTANCE -> { alert(0, 1, x, false); sec * 1000L }
            TestCase.SPEEDING -> { alert(0, 2, x / 2, true, speeding = true); sec * 1000L + IDLE_TEST_MS }
            TestCase.SEQUENCE -> {
                val gap = sec * 1000L + IDLE_TEST_MS + 1000L          // el 2.º aviso llega cuando acaba la fase "pantalla normal"
                alert(0, 1, x, true); alert(gap, 2, x / 2, true); gap + sec * 1000L + IDLE_TEST_MS
            }
        }
        post(total + 500) { testActive = false; BridgeHub.lastNav = "-" }
        AppLog.i("Test", "Radar: ${c.name} (aviso 1 a $x m, ${sec}s)")
        return null
    }

    fun cancelTest(sendOff: Boolean = true) {
        testRuns.forEach { main.removeCallbacks(it) }
        testRuns.clear()
        if (testActive && sendOff) BridgeHub.sink.send(Frame.json(JSONObject().put("t", "radar").put("on", 0)))
        testActive = false
    }

    private fun post(delayMs: Long, block: () -> Unit) {
        val r = Runnable { block() }
        testRuns.add(r)
        main.postDelayed(r, delayMs)
    }
}
