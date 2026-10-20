package dev.gpsnav.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import dev.gpsnav.bridge.ble.DevicePicker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Pantalla principal: estado, configuración inicial, dispositivo, opciones, logs y datos del desarrollador. */
class MainActivity : Activity() {

    private class Row(val root: LinearLayout, val icon: TextView, val sub: TextView)

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var chip: TextView
    private lateinit var deviceLine: TextView
    private lateinit var bleLine: TextView
    private lateinit var routeValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var preview: ImageView
    private lateinit var mainButton: Button
    private lateinit var deviceValue: TextView
    private lateinit var batteryHint: TextView
    private lateinit var rowPerms: Row
    private lateinit var rowNotif: Row
    private lateinit var rowBattery: Row
    private lateinit var rowBgLoc: Row
    private var lastRunning: Boolean? = null

    // ------------------------------------------------------------ utilidades de estilo
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun col(id: Int) = getColor(id)

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }

    private fun ripple(content: Drawable) =
        RippleDrawable(ColorStateList.valueOf(col(R.color.ripple)), content, null)

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, bottom: Int = 0, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { bottomMargin = dp(bottom); topMargin = dp(top) }

    private fun card(parent: LinearLayout, title: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(col(R.color.surface), 20, col(R.color.stroke))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        parent.addView(c, lp(bottom = 12))
        if (title != null) {
            c.addView(label(title, 12f, col(R.color.text_muted), true).apply {
                letterSpacing = 0.08f; setPadding(0, 0, 0, dp(6))
            })
        }
        return c
    }

    private fun button(t: String, fill: Int, textColor: Int, stroke: Int? = null, onClick: () -> Unit) =
        Button(this).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textColor); setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = ripple(rounded(fill, 14, stroke))
            minHeight = dp(48); minimumHeight = dp(48)
            setOnClickListener { onClick() }
        }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(col(R.color.stroke)) }, lp(h = 1))
    }

    /** Fila tocable: icono redondo + título + subtítulo + flecha. */
    private fun settingsRow(
        parent: LinearLayout, glyph: String, iconColor: Int, title: String, subtitle: String, onClick: () -> Unit
    ): Row {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            background = ripple(ColorDrawable(Color.TRANSPARENT))
            isClickable = true; setOnClickListener { onClick() }
        }
        val icon = label(glyph, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = circle(iconColor)
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        mid.addView(label(title, 15f, col(R.color.text), true))
        val sub = label(subtitle, 13f, col(R.color.text_muted))
        mid.addView(sub)
        root.addView(mid, lp(w = 0, weight = 1f))
        root.addView(label("›", 22f, col(R.color.text_muted)))
        parent.addView(root, lp())
        return Row(root, icon, sub)
    }

    private fun Row.set(ok: Boolean, subtitle: String) {
        icon.text = if (ok) "✓" else "!"
        icon.background = circle(col(if (ok) R.color.ok else R.color.warn))
        sub.text = subtitle
    }

    private fun switchRow(parent: LinearLayout, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        mid.addView(label(title, 15f, col(R.color.text), true))
        mid.addView(label(subtitle, 13f, col(R.color.text_muted)))
        root.addView(mid, lp(w = 0, weight = 1f))
        root.addView(Switch(this).apply {
            isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        parent.addView(root, lp())
    }

    private fun setChip(t: String, textColor: Int, bgColor: Int) {
        chip.text = t; chip.setTextColor(col(textColor)); chip.background = rounded(col(bgColor), 100)
    }

    // ------------------------------------------------------------ estado
    private fun deviceText(): String {
        val a = Prefs.deviceAddress(this) ?: return "Automático (primer ESP32 que encuentre)"
        return "${Prefs.deviceName(this) ?: "ESP32"} ($a)"
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permissionsOk(): Boolean {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            (!granted(Manifest.permission.BLUETOOTH_SCAN) || !granted(Manifest.permission.BLUETOOTH_CONNECT))) return false
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return false
        return true
    }

    private fun hasBackgroundLocation() =
        Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun ignoringBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 500) }
    }

    private fun refresh() {
        val link = BridgeHub.link
        val running = link != null
        val connected = link?.ready == true
        val st = BridgeHub.bleStatus

        when {
            !running -> setChip("Detenido", R.color.text_muted, R.color.chip_neutral)
            connected -> setChip("● Conectado", R.color.ok, R.color.chip_ok)
            st.startsWith("Busc") || st.startsWith("Conect") || st.startsWith("Inici") ->
                setChip("● Conectando…", R.color.warn, R.color.chip_warn)
            else -> setChip("● Sin conexión", R.color.bad, R.color.chip_bad)
        }
        deviceLine.text = deviceText()
        bleLine.text = if (running) st else "El servicio no está en marcha"
        routeValue.text = if (running) BridgeHub.lastNav else "—"
        speedValue.text = if (running) BridgeHub.lastSpeed else "—"
        BridgeHub.lastIcon?.let { preview.setImageBitmap(it) }
        deviceValue.text = deviceText()

        if (lastRunning != running) {
            lastRunning = running
            if (running) {
                mainButton.text = "Parar"; mainButton.setTextColor(col(R.color.bad))
                mainButton.background = ripple(rounded(col(R.color.chip_bad), 14))
            } else {
                mainButton.text = "Iniciar"; mainButton.setTextColor(col(R.color.on_primary))
                mainButton.background = ripple(rounded(col(R.color.primary), 14))
            }
        }

        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val battery = ignoringBattery()
        rowPerms.set(permissionsOk(), if (permissionsOk()) "Concedidos" else "Bluetooth, ubicación y notificaciones")
        rowNotif.set(listener, if (listener) "Activado" else "Necesario para leer la navegación de Google Maps")
        rowBattery.set(
            battery,
            if (battery) "Sin restricciones"
            else "Android puede cerrar la app en segundo plano y cortar la conexión. Toca para permitirlo."
        )
        rowBgLoc.set(
            hasBackgroundLocation(),
            if (hasBackgroundLocation()) "Permitida todo el tiempo"
            else "Para tener velocidad GPS al arrancar el móvil solo"
        )
        batteryHint.visibility = if (battery) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ construcción de la pantalla
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // Cabecera (el nombre del desarrollador va como subtítulo)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(label("Esp Nav", 24f, col(R.color.text), true))
        titles.addView(label("por ${AppInfo.DEVELOPER}", 13f, col(R.color.text_muted)))
        header.addView(titles)
        root.addView(header, lp(bottom = 16))

        // --- Estado
        val status = card(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chip = label("", 13f, col(R.color.text_muted), true).apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
        left.addView(chip, lp(w = WRAP, bottom = 8))
        deviceLine = label("", 15f, col(R.color.text), true)
        left.addView(deviceLine)
        bleLine = label("", 13f, col(R.color.text_muted))
        left.addView(bleLine)
        top.addView(left, lp(w = 0, weight = 1f))
        preview = ImageView(this).apply {
            background = rounded(Color.BLACK, 14); clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        top.addView(preview, LinearLayout.LayoutParams(dp(92), dp(92)).apply { leftMargin = dp(12) })
        status.addView(top, lp())

        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val routeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        routeCol.addView(label("RUTA", 11f, col(R.color.text_muted), true).apply { letterSpacing = 0.08f })
        routeValue = label("—", 14f, col(R.color.text)).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        routeCol.addView(routeValue)
        val speedCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedCol.addView(label("VELOCIDAD", 11f, col(R.color.text_muted), true).apply { letterSpacing = 0.08f })
        speedValue = label("—", 14f, col(R.color.text))
        speedCol.addView(speedValue)
        info.addView(routeCol, lp(w = 0, weight = 2f))
        info.addView(speedCol, lp(w = 0, weight = 1f))
        status.addView(info, lp(top = 14))

        mainButton = button("Iniciar", col(R.color.primary), col(R.color.on_primary)) { toggleBridge() }
        status.addView(mainButton, lp(h = dp(52), top = 14))

        // --- Configuración inicial
        val setup = card(root, "CONFIGURACIÓN INICIAL")
        rowPerms = settingsRow(setup, "✓", col(R.color.warn), "Permisos", "") { askPermissions() }
        divider(setup)
        rowNotif = settingsRow(setup, "✓", col(R.color.warn), "Acceso a notificaciones", "") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        divider(setup)
        rowBattery = settingsRow(setup, "✓", col(R.color.warn), "Optimización de batería", "") { requestBatteryExemption() }
        divider(setup)
        rowBgLoc = settingsRow(setup, "✓", col(R.color.warn), "Ubicación todo el tiempo", "") { requestBackgroundLocation() }
        batteryHint = label(
            "En algunos móviles (Samsung, Xiaomi, Huawei…) además hay que ir a Ajustes > Aplicaciones > " +
                "${AppInfo.APP_NAME} > Batería y poner \"Sin restricciones\", y permitir el inicio automático.",
            12f, col(R.color.text_muted)
        ).apply { setPadding(0, dp(8), 0, 0) }
        setup.addView(batteryHint)

        // --- Dispositivo
        val dev = card(root, "DISPOSITIVO ESP32")
        deviceValue = label("", 15f, col(R.color.text), true)
        dev.addView(deviceValue, lp(bottom = 10))
        dev.addView(button("Elegir ESP32", col(R.color.chip_neutral), col(R.color.text)) { pickDevice() }, lp(h = dp(48)))

        // --- Opciones
        val opts = card(root, "ARRANQUE")
        switchRow(opts, "Iniciar al arrancar el móvil", "Se pone en marcha solo al encender el teléfono.",
            Prefs.autoStartBoot(this)) { Prefs.setAutoStartBoot(this, it) }
        divider(opts)
        switchRow(opts, "Reiniciar si se cierra", "Vuelve a arrancar si se cierra la app (no si pulsas Parar).",
            Prefs.autoRestart(this)) { Prefs.setAutoRestart(this, it) }
        opts.addView(
            label("Si fuerzas la detención desde Ajustes, Android no permite reiniciarla hasta que abras la app.",
                12f, col(R.color.text_muted)).apply { setPadding(0, dp(6), 0, 0) }
        )

        // --- Herramientas
        val tools = card(root, "HERRAMIENTAS")
        settingsRow(tools, "≡", col(R.color.primary), "Logs", "Descargar, compartir o borrar el registro") { logsDialog() }

        // --- Acerca de
        val about = card(root, "ACERCA DE")
        val aboutTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        aboutTop.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val aboutText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        aboutText.addView(label(AppInfo.APP_NAME, 16f, col(R.color.text), true))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        aboutText.addView(label("Versión $version · Desarrollado por ${AppInfo.DEVELOPER}", 13f, col(R.color.text_muted)))
        aboutTop.addView(aboutText)
        about.addView(aboutTop, lp(bottom = 6))
        divider(about)
        settingsRow(about, "↗", col(R.color.primary), "Web del desarrollador", AppInfo.WEB_URL) { openUrl(AppInfo.WEB_URL) }
        divider(about)
        settingsRow(about, "↗", col(R.color.primary), "Repositorio del proyecto", AppInfo.REPO_URL) { openUrl(AppInfo.REPO_URL) }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); lastRunning = null; ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    // ------------------------------------------------------------ acciones
    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        catch (e: Exception) { Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show() }
    }

    private fun toggleBridge() {
        if (BridgeHub.link != null) {
            AppLog.i("UI", "Botón Parar")
            Prefs.setWantRunning(this, false)
            stopService(Intent(this, BridgeService::class.java)); BridgeHub.bleStatus = "Parado"
        } else startBridge()
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

    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestBackgroundLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Primero concede los permisos", Toast.LENGTH_LONG).show(); return
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

    private fun pickDevice() {
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
                   else Manifest.permission.ACCESS_FINE_LOCATION
        if (!granted(perm)) {
            Toast.makeText(this, "Primero concede los permisos", Toast.LENGTH_LONG).show(); return
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

    // ------------------------------------------------------------ logs
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun logText(): String {
        val ctx = this
        val header = buildString {
            appendLine("=== ${AppInfo.APP_NAME} · exportación de logs ===")
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
                putExtra(Intent.EXTRA_SUBJECT, "Logs ${AppInfo.APP_NAME}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Compartir logs"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
