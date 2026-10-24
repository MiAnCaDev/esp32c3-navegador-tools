package dev.gpsnav.bridge

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import dev.gpsnav.bridge.radar.RadarConfig
import dev.gpsnav.bridge.radar.RadarDb
import dev.gpsnav.bridge.radar.RoadLimit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Se ejecuta cada vez que arranca el proceso de la app (por la UI, el servicio, el arranque del móvil...). */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Destination.load(this)
        RadarConfig.load(this)
        EspConfig.init(this)
        // Leer la base de radares (JSON de cientos de KB) no debe retrasar el arranque: va en segundo plano
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            RadarDb.load(this); RoadLimit.init(this)
        }, "radar-init").start()
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        AppLog.i("App", "===== NUEVA SESIÓN (proceso iniciado) · v$v · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        logPreviousExits()

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("CRASH", "Excepción no controlada en el hilo ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    /** Android 11+: pregunta por qué terminó el proceso anterior (cierre del sistema, batería, fallo...). */
    private fun logPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val last = Prefs.lastExitTs(this)
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 10)
                .filter { it.timestamp > last }.sortedBy { it.timestamp }
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (i in list) {
                AppLog.i("ExitInfo", "El proceso anterior terminó (${df.format(Date(i.timestamp))}): " +
                    "motivo=${reasonName(i.reason)}, detalle='${i.description}', importancia=${i.importance}")
            }
            list.lastOrNull()?.let { Prefs.setLastExitTs(this, it.timestamp) }
        }
    }

    private fun reasonName(r: Int) = when (r) {
        0 -> "DESCONOCIDO"; 1 -> "SALIDA_VOLUNTARIA"; 2 -> "SEÑAL (kill)"; 3 -> "POCA_MEMORIA"
        4 -> "FALLO_JAVA (crash)"; 5 -> "FALLO_NATIVO"; 6 -> "ANR (no responde)"; 7 -> "FALLO_INICIALIZACION"
        8 -> "CAMBIO_PERMISOS"; 9 -> "USO_EXCESIVO_RECURSOS"; 10 -> "PEDIDO_POR_USUARIO"
        11 -> "DETENIDA_POR_USUARIO (forzar detención)"; 12 -> "DEPENDENCIA_MUERTA"; 13 -> "OTRO"
        14 -> "CONGELADA_POR_SISTEMA (freezer/batería)"; 15 -> "CAMBIO_ESTADO_PAQUETE"; 16 -> "PAQUETE_ACTUALIZADO"
        else -> "CODIGO_$r"
    }
}
