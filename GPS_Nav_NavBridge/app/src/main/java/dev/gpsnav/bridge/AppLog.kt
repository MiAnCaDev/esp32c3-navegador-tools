package dev.gpsnav.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de eventos en fichero (persiste entre ejecuciones y reinicios).
 * Guarda dos ficheros rotativos de ~300 KB: previous.log y current.log.
 */
object AppLog {
    private const val MAX_BYTES = 300_000L
    private val lock = Any()
    private var dir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

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
            val d = dir ?: return
            runCatching {
                val f = File(d, "current.log")
                if (f.length() > MAX_BYTES) {
                    val old = File(d, "previous.log")
                    old.delete(); f.renameTo(old)
                }
                val sb = StringBuilder()
                sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag)
                    .append(": ").append(msg).append('\n')
                if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
                f.appendText(sb.toString())
            }
        }
    }

    fun readAll(): String {
        synchronized(lock) {
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
            val d = dir ?: return
            File(d, "current.log").delete(); File(d, "previous.log").delete()
        }
    }
}
