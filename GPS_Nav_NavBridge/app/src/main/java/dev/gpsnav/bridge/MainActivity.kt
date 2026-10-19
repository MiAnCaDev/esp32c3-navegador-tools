package dev.gpsnav.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import dev.gpsnav.bridge.ble.DevicePicker

/** Pantalla mínima: permisos, elegir ESP32, arrancar/parar y ver qué se está enviando. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private val ui = Handler(Looper.getMainLooper())

    private fun deviceText(): String {
        val a = Prefs.deviceAddress(this) ?: return "Automático (primer ESP32 que encuentre)"
        return "${Prefs.deviceName(this) ?: "ESP32"} ($a)"
    }

    private val tick = object : Runnable {
        override fun run() {
            val listener = NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                .contains(packageName)
            status.text = "ESP32: ${deviceText()}\n" +
                "BLE: ${BridgeHub.bleStatus}\n" +
                "Maps: ${BridgeHub.lastNav}\n" +
                "Velocidad: ${BridgeHub.lastSpeed}\n" +
                "Acceso a notificaciones: ${if (listener) "SI" else "NO"}"
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
        button("4. Iniciar") { startForegroundService(Intent(this, BridgeService::class.java)) }
        button("Parar") { stopService(Intent(this, BridgeService::class.java)); BridgeHub.bleStatus = "Parado" }

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
