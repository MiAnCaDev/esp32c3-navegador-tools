package dev.gpsnav.bridge

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de eventos en fichero (persiste entre ejecuciones y reinicios).
 * Guarda dos ficheros rotativos de ~300 KB: previous.log y current.log.
 *
 * Las líneas se acumulan en memoria y se escriben en bloque (cada ~1,5 s o a los 8 KB) desde un hilo propio, en vez
 * de abrir y cerrar el fichero en cada línea. Los avisos (W) y errores (E) se escriben al instante, así que ante un
 * fallo no se pierde nada importante.
 */
object AppLog {
    private const val MAX_BYTES = 300_000L
    private val lock = Any()
    private var dir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val buf = StringBuilder()
    private var flushPending = false
    private val flusher: Handler by lazy { Handler(HandlerThread("applog").apply { start() }.looper) }
    private val flushRun = Runnable { synchronized(lock) { flushLocked() } }

    /** Escribe lo acumulado. Hay que llamarlo con [lock] cogido. */
    private fun flushLocked() {
        flushPending = false
        val d = dir ?: return
        if (buf.isEmpty()) return
        runCatching {
            val f = File(d, "current.log")
            if (f.length() > MAX_BYTES) {
                val old = File(d, "previous.log")
                old.delete(); f.renameTo(old)
            }
            f.appendText(buf.toString())
        }
        buf.setLength(0)
    }

    fun init(ctx: Context) {
        synchronized(lock) {
            if (dir == null) dir = File(ctx.applicationContext.filesDir, "logs").also { it.mkdirs() }
        }
    }

    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String) = log("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e("NavBridge", "$tag: $msg", t)
            "W" -> Log.w("NavBridge", "$tag: $msg")
            else -> Log.d("NavBridge", "$tag: $msg")
        }
        synchronized(lock) {
            if (dir == null) return
            buf.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag)
                .append(": ").append(msg).append('\n')
            if (t != null) buf.append(Log.getStackTraceString(t)).append('\n')
            if (level == "E" || level == "W" || buf.length > 8_000) {
                flusher.removeCallbacks(flushRun); flushLocked()
            } else if (!flushPending) {
                flushPending = true; flusher.postDelayed(flushRun, 1_500)
            }
        }
    }

    fun readAll(): String {
        synchronized(lock) {
            flushLocked()
            val d = dir ?: return ""
            val sb = StringBuilder()
            for (name in listOf("previous.log", "current.log")) {
                runCatching {
                    val f = File(d, name)
                    if (f.exists()) sb.append(f.readText())
                }
            }
            return sb.toString()
        }
    }

    fun clear() {
        synchronized(lock) {
            buf.setLength(0)
            val d = dir ?: return
            File(d, "current.log").delete(); File(d, "previous.log").delete()
        }
    }
}
