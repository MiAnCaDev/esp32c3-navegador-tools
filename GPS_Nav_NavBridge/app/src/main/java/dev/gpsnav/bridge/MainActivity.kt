package dev.gpsnav.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import dev.gpsnav.bridge.ble.DevicePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla mínima: permisos, elegir ESP32, batería, arrancar/parar y ver qué se está enviando. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var battery: TextView
    private lateinit var preview: ImageView
    private val ui = Handler(Looper.getMainLooper())

    private fun deviceText(): String {
        val a = Prefs.deviceAddress(this) ?: return "Automático (primer ESP32 que encuentre)"
        return "${Prefs.deviceName(this) ?: "ESP32"} ($a)"
    }

    private fun hasBackgroundLocation() = Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private val tick = object : Runnable {
        override fun run() {
            val listener = NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                .contains(packageName)
            status.text = "ESP32: ${deviceText()}\n" +
                "BLE: ${BridgeHub.bleStatus}\n" +
                "Maps: ${BridgeHub.lastNav}\n" +
                "Velocidad: ${BridgeHub.lastSpeed}\n" +
                "Acceso a notificaciones: ${if (listener) "SI" else "NO"}\n" +
                "Ubicación en segundo plano: ${if (hasBackgroundLocation()) "SI" else "NO"}"
            if (ignoringBattery()) {
                battery.text = "✔ Optimización de batería desactivada para esta app."
                battery.setTextColor(Color.rgb(0x2E, 0x7D, 0x32))
            } else {
                battery.text = "⚠ La optimización de batería está ACTIVA. Android puede cerrar la app en " +
                    "segundo plano y se perdería la conexión con el ESP32. Pulsa \"4\" y elige Permitir. " +
                    "En algunos móviles (Samsung, Xiaomi, Huawei...) también hay que ir a Ajustes > " +
                    "Aplicaciones > Esp Nav MiAnCa > Batería y poner \"Sin restricciones\" o permitir " +
                    "el inicio automático."
                battery.setTextColor(Color.rgb(0xC6, 0x28, 0x28))
            }
            BridgeHub.lastIcon?.let { preview.setImageBitmap(it) }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
        }
        TextView(this).apply {
            text = getString(R.string.app_name); textSize = 22f
            setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, pad); root.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }; root.addView(this)
        }
        button("1. Conceder permisos") { askPermissions() }
        button("2. Dar acceso a notificaciones") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("3. Elegir ESP32") { pickDevice() }
        button("4. Evitar que la batería cierre la app") { requestBatteryExemption() }
        battery = TextView(this).apply { textSize = 13f; setPadding(0, pad / 2, 0, pad / 2); root.addView(this) }
        button("5. Ubicación \"todo el tiempo\" (GPS tras arrancar el móvil)") { requestBackgroundLocation() }

        fun check(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
            text = label; isChecked = initial
            setOnCheckedChangeListener { _, v -> onChange(v) }; root.addView(this)
        }
        check("Iniciar automáticamente al arrancar el móvil", Prefs.autoStartBoot(this)) {
            Prefs.setAutoStartBoot(this, it)
        }
        check("Reiniciar automáticamente si se cierra la app", Prefs.autoRestart(this)) {
            Prefs.setAutoRestart(this, it)
        }
        TextView(this).apply {
            text = "Si fuerzas la detención desde Ajustes del sistema, Android no permite reiniciarla " +
                "hasta que abras la app a mano."
            textSize = 12f; setPadding(0, 0, 0, pad / 2); root.addView(this)
        }

        button("6. Iniciar") { startBridge() }
        button("Parar") {
            AppLog.i("UI", "Botón Parar")
            Prefs.setWantRunning(this, false)
            stopService(Intent(this, BridgeService::class.java)); BridgeHub.bleStatus = "Parado"
        }
        button("Logs: descargar / compartir") { logsDialog() }

        status = TextView(this).apply { textSize = 15f; setPadding(0, pad, 0, pad); root.addView(this) }
        TextView(this).apply { text = "Lo que ve el ESP32:"; root.addView(this) }
        val side = (240 * resources.displayMetrics.density).toInt()
        preview = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(side, side).apply { gravity = Gravity.CENTER_HORIZONTAL }
            root.addView(this)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startBridge() {
        if (ignoringBattery()) { doStart(); return }
        AlertDialog.Builder(this)
            .setTitle("Evita que Android cierre la app")
            .setMessage(
                "Para que la conexión con el ESP32 no se corte con la pantalla apagada, hay que " +
                "excluir la app de la optimización de batería. Si no, Android puede cerrarla sin avisar."
            )
            .setPositiveButton("Ajustar ahora") { _, _ -> requestBatteryExemption() }
            .setNegativeButton("Iniciar igualmente") { _, _ -> doStart() }
            .show()
    }

    private fun doStart() {
        AppLog.i("UI", "Botón Iniciar")
        startForegroundService(Intent(this, BridgeService::class.java))
    }

    // ------------------------------------------------------------ logs
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun logText(): String {
        val ctx = this
        val header = buildString {
            appendLine("=== Esp Nav MiAnCa · exportación de logs ===")
            appendLine("Exportado: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Móvil: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ESP32: ${deviceText()} | BLE ahora: ${BridgeHub.bleStatus}")
            appendLine("Batería exenta: ${ignoringBattery()} | Ubicación en segundo plano: ${hasBackgroundLocation()}")
            appendLine("Arranque auto: ${Prefs.autoStartBoot(ctx)} | Reinicio auto: ${Prefs.autoRestart(ctx)}")
            appendLine("Nota: el log puede incluir nombres de calles de tus rutas.")
            appendLine("Cada ejecución empieza con una línea \"NUEVA SESIÓN\".")
            appendLine("============================================")
        }
        return header + AppLog.readAll()
    }

    private fun logsDialog() {
        AlertDialog.Builder(this).setTitle("Logs")
            .setItems(arrayOf("Guardar en Descargas", "Compartir...", "Borrar logs")) { _, which ->
                when (which) {
                    0 -> saveLogs()
                    1 -> shareLogs()
                    2 -> AlertDialog.Builder(this).setMessage("¿Borrar todos los logs?")
                        .setPositiveButton("Borrar") { _, _ ->
                            AppLog.clear(); AppLog.i("UI", "Logs borrados por el usuario")
                            Toast.makeText(this, "Logs borrados", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancelar", null).show()
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun saveLogs() {
        if (Build.VERSION.SDK_INT < 29) { shareLogs(); return }
        val name = "EspNav_logs_${stamp()}.txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(logText().toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Guardado en Descargas: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar (${e.message}). Prueba con Compartir.", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogs() {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "EspNav_logs_${stamp()}.txt")
            f.writeText(logText())
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Logs Esp Nav MiAnCa")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Compartir logs"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBackgroundLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Primero concede los permisos (botón 1)", Toast.LENGTH_LONG).show(); return
        }
        if (hasBackgroundLocation()) {
            Toast.makeText(this, "Ya tiene ubicación todo el tiempo", Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Permisos > Ubicación > \"Permitir todo el tiempo\"", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun pickDevice() {
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
                   else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Primero concede los permisos (botón 1)", Toast.LENGTH_LONG).show()
            return
        }
        DevicePicker(this, Prefs.deviceAddress(this), Prefs.deviceName(this)) { addr, name ->
            Prefs.setDevice(this, addr, name)
            BridgeHub.link?.reconnect()   // si el servicio está en marcha, cambia al instante
        }.show()
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
