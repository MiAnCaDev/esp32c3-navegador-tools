package dev.gpsnav.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
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
import android.content.ClipboardManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import dev.gpsnav.bridge.radar.RadarConfig
import dev.gpsnav.bridge.radar.RadarDb
import dev.gpsnav.bridge.radar.RadarMonitor
import dev.gpsnav.bridge.radar.RadarUpdater
import androidx.core.content.FileProvider
import dev.gpsnav.bridge.ble.DevicePicker
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Pestañas de la barra inferior
private const val TAB_HOME = 0
private const val TAB_NAV = 1
private const val TAB_LACT = 2
private const val TAB_SETTINGS = 3

// Códigos de las pantallas de archivos (exportar / importar configuración)
private const val REQ_EXPORT = 4101
private const val REQ_IMPORT = 4102

/** Pantalla principal: estado, configuración inicial, dispositivo, opciones, logs y datos del desarrollador. */
class MainActivity : Activity() {

    private class Row(val root: LinearLayout, val icon: TextView, val sub: TextView)

    private lateinit var favCard: LinearLayout
    private lateinit var favList: LinearLayout
    private var favKey = ""
    private var lactSwitch: SwitchRef? = null
    private var lactUpdating = false
    private var rowLactNet: Row? = null
    private var rowPair: Row? = null
    private var lockSwitch: SwitchRef? = null
    private var lockUpdating = false
    private var pairDialogOpen = false
    private var rowRadarCfg: Row? = null
    private var rowRadarDb: Row? = null

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var chip: TextView
    private lateinit var deviceLine: TextView
    private lateinit var bleLine: TextView
    private lateinit var routeValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var preview: ImageView
    private lateinit var mainButton: Button
    private lateinit var deviceValue: TextView
    private lateinit var destValue: TextView
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

    private class SwitchRef(val sw: Switch, val sub: TextView)

    private fun switchRow(parent: LinearLayout, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): SwitchRef {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(12), 0) }
        mid.addView(label(title, 15f, col(R.color.text), true))
        val subView = label(subtitle, 13f, col(R.color.text_muted))
        mid.addView(subView)
        root.addView(mid, lp(w = 0, weight = 1f))
        val sw = Switch(this).apply {
            isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        root.addView(sw)
        parent.addView(root, lp())
        return SwitchRef(sw, subView)
    }

    private fun setChip(t: String, textColor: Int, bgColor: Int) {
        if (lastChip == t) return
        lastChip = t
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
        override fun run() { refreshStatus(); ui.postDelayed(this, 1000) }
    }

    /** Estado de la conexión: se repite cada segundo mientras la pantalla está visible. Solo toca las vistas que cambian. */
    private fun refreshStatus() {
        val link = BridgeHub.link
        val running = link != null
        val connected = link?.ready == true
        val st = BridgeHub.bleStatus

        when {
            !running -> setChip("Detenido", R.color.text_muted, R.color.chip_neutral)
            connected -> setChip("● Conectado", R.color.ok, R.color.chip_ok)
            link?.sleeping == true -> setChip("● En pausa", R.color.text_muted, R.color.chip_neutral)
            st.startsWith("Busc") || st.startsWith("Conect") || st.startsWith("Inici") ->
                setChip("● Conectando…", R.color.warn, R.color.chip_warn)
            else -> setChip("● Sin conexión", R.color.bad, R.color.chip_bad)
        }
        val dev = deviceText()
        deviceLine.setIfChanged(dev)
        bleLine.setIfChanged(if (running) st else "El servicio no está en marcha")
        routeValue.setIfChanged(if (running) BridgeHub.lastNav else "—")
        speedValue.setIfChanged(if (running) BridgeHub.lastSpeed else "—")
        BridgeHub.lastIcon?.let { if (it !== shownIcon) { shownIcon = it; preview.setImageBitmap(it) } }
        deviceValue.setIfChanged(dev)
        destValue.setIfChanged(destText())
        if (favKey != currentFavKey()) renderFavorites()      // la favorita "activa" sigue al destino actual
        refreshLactancia()

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

    }

    /** Permisos y ajustes del sistema: solo cambian cuando el usuario sale a Ajustes, así que basta con mirarlos al volver. */
    private fun refreshPerms() {
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
        // Imprescindible: permisos y acceso a notificaciones (batería y ubicación permanente son recomendados)
        val critical = !(permissionsOk() && listener)
        setupBanner.visibility = if (critical) View.VISIBLE else View.GONE
        tabRefs.getOrNull(TAB_SETTINGS)?.dot?.visibility = if (critical) View.VISIBLE else View.GONE
    }

    private fun refresh() { refreshStatus(); refreshPerms() }

    private var shownIcon: android.graphics.Bitmap? = null
    private var lastChip: String? = null
    private fun TextView.setIfChanged(t: String) { if (text.toString() != t) text = t }

    // ------------------------------------------------------------ construcción de la pantalla
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cuatro páginas (una por pestaña de la barra inferior), cada una con su propio scroll
        val home = page()
        val nav = page()
        val lactPage = page()
        val settings = page()
        pageTitle(nav, "Navegación", "Brújula, avisos de radar y pruebas de mensajes al ESP32")
        pageTitle(lactPage, "Modo lactancia", "App de lactancia del ESP32")
        pageTitle(settings, "Ajustes", "Arranque, configuración inicial y herramientas")

        // Cabecera de Inicio (el nombre del desarrollador va como subtítulo)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(label("Esp Nav", 24f, col(R.color.text), true))
        titles.addView(label("por ${AppInfo.DEVELOPER}", 13f, col(R.color.text_muted)))
        header.addView(titles)
        home.addView(header, lp(bottom = 16))

        // Aviso si falta algo imprescindible (la configuración inicial vive en Ajustes, al final)
        setupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(rounded(col(R.color.chip_warn), 16, col(R.color.warn)))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            visibility = View.GONE
        }
        setupBanner.addView(label("Falta completar la configuración", 15f, col(R.color.text), true))
        setupBanner.addView(label("Sin los permisos y el acceso a notificaciones la app no puede funcionar. Toca para revisarlo.",
            13f, col(R.color.text_muted)))
        home.addView(setupBanner, lp(bottom = 12))

        // --- Estado
        val status = card(home)
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
        val setup = card(settings, "CONFIGURACIÓN INICIAL")
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
        val dev = card(home, "DISPOSITIVO ESP32")
        deviceValue = label("", 15f, col(R.color.text), true)
        dev.addView(deviceValue, lp(bottom = 10))
        dev.addView(button("Elegir ESP32", col(R.color.chip_neutral), col(R.color.text)) { pickDevice() }, lp(h = dp(48)))

        // --- Destino de la brújula
        val dst = card(nav, "DESTINO DE LA BRÚJULA")
        destValue = label("", 15f, col(R.color.text), true)
        dst.addView(destValue, lp(bottom = 4))
        dst.addView(
            label("La flecha del ESP32 (botón de brújula) apunta a este destino en línea recta, " +
                "usando el rumbo del GPS. Funciona en movimiento. También puedes compartir un lugar " +
                "desde Google Maps hacia esta app.", 12f, col(R.color.text_muted)),
            lp(bottom = 10)
        )
        dst.addView(button("Usar mi ubicación actual", col(R.color.chip_neutral), col(R.color.text)) { useCurrentLocation() }, lp(h = dp(48), bottom = 8))
        dst.addView(button("Pegar coordenadas o enlace", col(R.color.chip_neutral), col(R.color.text)) { pasteDestDialog() }, lp(h = dp(48), bottom = 8))
        dst.addView(button("Quitar destino", col(R.color.chip_bad), col(R.color.bad)) {
            Destination.set(this, null)
            Toast.makeText(this, "Destino quitado", Toast.LENGTH_SHORT).show()
        }, lp(h = dp(48)))

        // --- Opciones
        val opts = card(settings, "ARRANQUE")
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
        val tools = card(settings, "HERRAMIENTAS")
        settingsRow(tools, "≡", col(R.color.primary), "Logs", "Descargar, compartir o borrar el registro") { logsDialog() }
        divider(tools)
        settingsRow(tools, "↑", col(R.color.primary), "Exportar configuración",
            "Guarda ajustes, favoritas y destino en un archivo .json") { exportConfigDialog() }
        divider(tools)
        settingsRow(tools, "↓", col(R.color.primary), "Importar configuración",
            "Restaura los ajustes desde un archivo .json (por ejemplo, en otro móvil)") { startImport() }

        // --- Avisos de radar
        // --- Ubicaciones favoritas (hasta 8): al tocar una se fija como destino de la brújula
        Favorites.load(this)
        favCard = card(nav, "UBICACIONES FAVORITAS")
        favList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        favCard.addView(favList, lp(bottom = 8))
        favCard.addView(button("Añadir (enlace o coordenadas)", col(R.color.chip_neutral), col(R.color.text)) {
            if (Favorites.list().size >= Favorites.MAX)
                Toast.makeText(this, "Ya tienes ${Favorites.MAX} favoritas. Elimina alguna para añadir otra.", Toast.LENGTH_LONG).show()
            else pasteDestDialog("Nueva favorita", "Guardar") { la, lo, n -> saveFavorite(la, lo, n) }
        }, lp(h = dp(48), bottom = 8))
        favCard.addView(button("Guardar mi ubicación actual", col(R.color.chip_neutral), col(R.color.text)) {
            if (Favorites.list().size >= Favorites.MAX)
                Toast.makeText(this, "Ya tienes ${Favorites.MAX} favoritas. Elimina alguna para añadir otra.", Toast.LENGTH_LONG).show()
            else useCurrentLocation(asFavorite = true)
        }, lp(h = dp(48)))
        renderFavorites()

        val rad = card(nav, "AVISOS DE RADAR")
        switchRow(rad, "Avisar de radares", "Alerta roja en el ESP32 al acercarte a un radar fijo o de tramo (con el ESP32 conectado).",
            RadarConfig.current.enabled) { RadarConfig.save(this, RadarConfig.current.copy(enabled = it)) }
        divider(rad)
        switchRow(rad, "Alerta por exceso de velocidad", "Línea en el borde derecho del ESP32 si superas el límite de la vía en el radar: blanca sobre la alerta, roja sin ella hasta pasar el radar.",
            RadarConfig.current.overspeedAlert) { RadarConfig.save(this, RadarConfig.current.copy(overspeedAlert = it)) }
        divider(rad)
        switchRow(rad, "Distancia en la pantalla normal", "Tras la alerta roja, muestra en una esquina los metros que faltan hasta el radar, hasta pasarlo.",
            RadarConfig.current.idleDistance) { RadarConfig.save(this, RadarConfig.current.copy(idleDistance = it)) }
        divider(rad)
        rowRadarCfg = settingsRow(rad, "⚙", col(R.color.primary), "Configurar avisos", radarCfgSummary()) { radarConfigDialog() }
        divider(rad)
        rowRadarDb = settingsRow(rad, "⟳", col(R.color.primary), "Base de radares", radarDbSummary()) { updateRadarDb() }
        rad.addView(
            label("⚠ Cataluña: faltan los radares oficiales del Servei Català de Trànsit (aún no están integrados). " +
                "Allí solo se usan los de OpenStreetMap, que pueden estar incompletos.", 12f, col(R.color.warn)),
            lp(top = 8)
        )
        rad.addView(
            label("Datos: DGT (CC-BY) y © OpenStreetMap contributors (ODbL; también los límites de velocidad de las vías). " +
                "Solo radares fijos y de tramo publicados; " +
                "no existen datos oficiales de radares móviles en tiempo real.", 12f, col(R.color.text_muted)),
            lp(top = 6)
        )

        // --- Modo lactancia (ajustes que viven en el ESP32)
        val lact = card(lactPage, "MODO LACTANCIA")
        lactSwitch = switchRow(lact, "Habilitar modo lactancia en el ESP32",
            EspConfig.statusText(), EspConfig.shownLact()) { on ->
            if (!lactUpdating) {
                Toast.makeText(this, EspConfig.requestLact(on), Toast.LENGTH_LONG).show()
                if (EspConfig.consumeNeedPair()) startPairFlow()
            }
        }
        divider(lact)
        // Bloqueo OPCIONAL: abierto por defecto. Activado, el ESP32 solo obedece a móviles emparejados (hasta 4)
        lockSwitch = switchRow(lact, "Bloquear ajustes a móviles emparejados", EspConfig.lockText(), EspConfig.lockOn()) { on ->
            if (!lockUpdating) {
                lockSwitch?.let { lockUpdating = true; it.sw.isChecked = !on; lockUpdating = false }   // el interruptor refleja el estado REAL del ESP32
                if (on) startPairFlow() else Toast.makeText(this, EspConfig.requestUnlock(), Toast.LENGTH_LONG).show()
                if (!on && EspConfig.consumeNeedPair()) startPairFlow()
            }
        }
        divider(lact)
        rowPair = settingsRow(lact, "🔑", col(R.color.primary), "Emparejar este móvil", EspConfig.pairText()) { startPairFlow() }
        divider(lact)
        rowLactNet = settingsRow(lact, "⚙", col(R.color.primary), "Servidor y WiFi del ESP32", EspConfig.netSummary()) { lactNetDialog() }
        lact.addView(
            label("Viene deshabilitado de fábrica: mientras no lo actives aquí, el botón del ESP32 no cambia de modo. " +
                "Al pulsarlo, el ESP32 se reinicia en modo lactancia (Bluetooth apagado, unos 2 s) y vuelve a modo GPS " +
                "del mismo modo. Los ajustes de servidor y WiFi se envían con el ESP32 conectado y se aplican la próxima " +
                "vez que entre en lactancia. Por defecto cualquier móvil puede cambiar estos ajustes (puedes usar varios). Si quieres que solo " +
                "lo hagan móviles emparejados, activa el bloqueo.",
                12f, col(R.color.text_muted)),
            lp(top = 8)
        )

        // --- Pruebas del ESP32
        val tests = card(nav, "PRUEBAS DEL ESP32")
        tests.addView(
            label("Mandan datos de prueba al ESP32 para ver cómo se dibujan. Necesitas tener la app iniciada y el " +
                "ESP32 conectado. Duran unos ${TestSignals.GPS_SECONDS} s y después se restaura lo real.",
                12f, col(R.color.text_muted)),
            lp(bottom = 10)
        )
        tests.addView(button("Probar indicación GPS", col(R.color.chip_neutral), col(R.color.text)) { testGpsDialog() }, lp(h = dp(48), bottom = 8))
        tests.addView(button("Probar dirección de brújula", col(R.color.chip_neutral), col(R.color.text)) { testCompassDialog() }, lp(h = dp(48), bottom = 8))
        tests.addView(button("Probar aviso de radar", col(R.color.chip_neutral), col(R.color.text)) { testRadarDialog() }, lp(h = dp(48)))

        // --- Acerca de
        val about = card(settings, "ACERCA DE")
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

        // Orden de Ajustes: Arranque, Configuración inicial, Herramientas, Acerca de
        run { val lpS = setup.layoutParams; settings.removeView(setup); settings.addView(setup, settings.indexOfChild(tools), lpS) }
        setupBanner.setOnClickListener { showTab(TAB_SETTINGS, scrollTo = setup) }

        // Contenedor: páginas arriba (solo una visible) y barra de pestañas abajo
        scrolls = listOf(home, nav, lactPage, settings).map { pg -> ScrollView(this).apply { addView(pg); visibility = View.GONE } }
        val frame = FrameLayout(this)
        scrolls.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shell.addView(frame, lp(h = 0, weight = 1f))
        shell.addView(buildTabBar())
        setContentView(shell)
        showTab(savedInstanceState?.getInt("tab") ?: TAB_HOME)

        if (savedInstanceState == null) handleShareIntent(intent)   // lugar compartido desde Maps
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() { super.onResume(); lastRunning = null; refreshPerms(); ui.post(tick) }
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

    // ------------------------------------------------------------ destino de la brújula
    private fun fixDestination(lat: Double, lon: Double, name: String) {
        Destination.set(this, Destination.Dest(lat, lon, Destination.cleanName(name)))
        Toast.makeText(this, "Destino fijado", Toast.LENGTH_SHORT).show()
    }

    private fun saveFavorite(lat: Double, lon: Double, name: String) {
        val err = Favorites.add(this, name.ifBlank { "Favorita" }, lat, lon)
        Toast.makeText(this, err ?: "Guardada en favoritas (${Favorites.list().size}/${Favorites.MAX})", Toast.LENGTH_LONG).show()
        renderFavorites()
    }

    private fun destText(): String {
        val d = Destination.current ?: return "Ningún destino fijado"
        return "${d.name}\n${"%.5f".format(Locale.US, d.lat)}, ${"%.5f".format(Locale.US, d.lon)}"
    }

    private fun askDestName(default: String, message: String? = null, okLabel: String = "Fijar", onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(default); setSelectAllOnFocus(true) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            if (message != null) addView(label(message, 13f, col(R.color.text_muted)), lp(bottom = 8))
            addView(et, lp())
        }
        AlertDialog.Builder(this).setTitle("Nombre del destino").setView(box)
            .setPositiveButton(okLabel) { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun useCurrentLocation(asFavorite: Boolean = false) {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Primero concede los permisos", Toast.LENGTH_LONG).show(); return
        }
        var loc: Location? = Destination.freshLocation()
        if (loc == null) {
            val lm = getSystemService(LocationManager::class.java)
            loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 120_000L }
                .maxByOrNull { it.time }
        }
        if (loc == null) {
            Toast.makeText(this, "Sin ubicación reciente. Pulsa Iniciar y espera a que haya GPS.", Toast.LENGTH_LONG).show()
            return
        }
        val lat = loc.latitude; val lon = loc.longitude
        if (asFavorite) {
            askDestName("Mi ubicacion", okLabel = "Guardar") { name -> saveFavorite(lat, lon, name) }
        } else {
            askDestName("Mi ubicacion") { name ->
                Destination.set(this, Destination.Dest(lat, lon, Destination.cleanName(name)))
                Toast.makeText(this, "Destino fijado en tu ubicación", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Pegar coordenadas o un enlace de Google Maps + nombre. Por defecto fija el destino; para las favoritas se pasa
     * otro título/botón y otra acción ([onPicked] recibe latitud, longitud y el nombre escrito o adivinado).
     */
    private fun pasteDestDialog(
        title: String = "Fijar destino", okLabel: String = "Fijar",
        onPicked: (Double, Double, String) -> Unit = { la, lo, n -> fixDestination(la, lo, n) }
    ) {
        val coords = EditText(this).apply {
            hint = "40.4168, -3.7038  o enlace de Google Maps"; minLines = 2
        }
        val name = EditText(this).apply { hint = "Nombre (opcional)"; setSingleLine(true) }
        runCatching {   // si el portapapeles ya tiene coordenadas, se rellenan solas
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clip != null && DestParser.parse(clip) != null) coords.setText(clip)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(coords, lp()); addView(name, lp())
        }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(okLabel) { _, _ ->
                val text = coords.text.toString()
                val c = DestParser.parse(text)
                when {
                    c != null -> onPicked(c.first, c.second, name.text.toString().ifBlank { LinkResolver.guessName(text) ?: "" })
                    text.contains("http", ignoreCase = true) -> resolveAndAsk(text, okLabel, onPicked)
                    else -> Toast.makeText(this, "No se reconocen coordenadas", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Lee un enlace de Google Maps (puede necesitar internet) y pide confirmar el destino. */
    private fun resolveAndAsk(
        text: String, okLabel: String = "Fijar",
        onPicked: (Double, Double, String) -> Unit = { la, lo, n -> fixDestination(la, lo, n) }
    ) {
        var cancelled = false
        val wait = AlertDialog.Builder(this)
            .setMessage("Leyendo la ubicación del enlace…")
            .setNegativeButton("Cancelar") { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }
            .show()
        Thread {
            var result: LinkResolver.Result? = null
            var error: String? = null
            try {
                result = LinkResolver.resolve(text)
            } catch (e: IOException) {
                error = "No hay conexión a internet (o Google no responde)."
            } catch (e: Exception) {
                error = "No se pudo leer el enlace."
            }
            runOnUiThread {
                wait.dismiss()
                if (cancelled) return@runOnUiThread
                val r = result
                if (r == null) {
                    Toast.makeText(this, error ?: "No se encontraron coordenadas en ese enlace.", Toast.LENGTH_LONG).show()
                } else {
                    val msg = "%.5f, %.5f".format(Locale.US, r.lat, r.lon)
                    askDestName(Destination.cleanName(r.name ?: ""), msg, okLabel) { n -> onPicked(r.lat, r.lon, n) }
                }
            }
        }.start()
    }

    /** Un lugar compartido desde Google Maps (o cualquier texto con coordenadas/enlace) llega aquí. */
    private fun handleShareIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        // Cualquier app puede "compartir" texto con esta: se acota para que uno enorme no bloquee la pantalla
        val text = (i.getStringExtra(Intent.EXTRA_TEXT) ?: return).take(4_000)
        AppLog.i("UI", "Texto compartido con la app (${text.length} caracteres)")
        showTab(TAB_NAV)                       // el destino de la brújula está en Navegación
        val c = DestParser.parse(text)
        if (c != null) {
            askDestName(
                Destination.cleanName(LinkResolver.guessName(text) ?: ""),
                "%.5f, %.5f".format(Locale.US, c.first, c.second)
            ) { n ->
                Destination.set(this, Destination.Dest(c.first, c.second, Destination.cleanName(n)))
                Toast.makeText(this, "Destino fijado", Toast.LENGTH_SHORT).show()
            }
        } else {
            resolveAndAsk(text)
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

    // ------------------------------------------------------------ exportar / importar configuración
    private var exportSecrets = false

    private fun exportConfigDialog() {
        if (!ConfigBackup.hasSecrets()) { startExport(false); return }
        AlertDialog.Builder(this).setTitle("Exportar configuración")
            .setMessage("Hay una clave de WiFi o un token guardados. ¿Los incluyo en el archivo? Se escribirían en texto " +
                "plano: guárdalo en un sitio de confianza. Sin claves tendrás que volver a escribirlas en el otro móvil.")
            .setPositiveButton("Incluir claves") { _, _ -> startExport(true) }
            .setNeutralButton("Sin claves") { _, _ -> startExport(false) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun startExport(secrets: Boolean) {
        exportSecrets = secrets
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, ConfigBackup.fileName())
        }
        try { startActivityForResult(i, REQ_EXPORT) }
        catch (e: Exception) { Toast.makeText(this, "No hay ninguna app para guardar archivos.", Toast.LENGTH_LONG).show() }
    }

    private fun startImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, REQ_IMPORT) }
        catch (e: Exception) { Toast.makeText(this, "No hay ninguna app para elegir archivos.", Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> writeConfig(uri)
            REQ_IMPORT -> readConfig(uri)
        }
    }

    private fun writeConfig(uri: Uri) {
        try {
            val text = ConfigBackup.export(this, exportSecrets)
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            AppLog.i("UI", "Configuración exportada (claves: $exportSecrets)")
            Toast.makeText(this, "Configuración exportada" + if (exportSecrets) " (con claves)" else "", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.e("UI", "No se pudo exportar la configuración", e)
            Toast.makeText(this, "No se pudo guardar el archivo (${e.message})", Toast.LENGTH_LONG).show()
        }
    }

    private fun readConfig(uri: Uri) {
        val json = try {
            ConfigBackup.parse(contentResolver.openInputStream(uri)!!.use { ConfigBackup.readText(it) })
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo leer el archivo (${e.message})", Toast.LENGTH_LONG).show(); return
        }
        val lines = ConfigBackup.describe(json)
        if (lines.isEmpty()) { Toast.makeText(this, "El archivo no contiene ajustes que importar.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Importar configuración")
            .setMessage("Se sustituirá lo siguiente por lo que hay en el archivo:\n\n• " + lines.joinToString("\n• ") +
                "\n\nLo demás no cambia. No se envía nada al ESP32: los ajustes de lactancia se mandan desde su pantalla.")
            .setPositiveButton("Importar") { _, _ ->
                val r = ConfigBackup.apply(this, json)
                if (r.deviceChanged) BridgeHub.link?.reconnect()      // cambia de ESP32 al instante si el servicio está en marcha
                AppLog.i("UI", "Configuración importada: ${r.lines.joinToString("; ")}")
                Toast.makeText(this, "Configuración importada", Toast.LENGTH_LONG).show()
                recreate()                                           // reconstruye la pantalla con los valores nuevos
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ ubicaciones favoritas
    /** Lo que identifica el estado visible de la lista: favoritas guardadas + destino actual (para marcar la activa). */
    private fun currentFavKey(): String {
        val d = Destination.current
        return "${Favorites.version}|${d?.lat}|${d?.lon}"
    }

    private fun renderFavorites() {
        favKey = currentFavKey()
        val n = Favorites.list().size
        (favCard.getChildAt(0) as? TextView)?.text = "UBICACIONES FAVORITAS ($n/${Favorites.MAX})"
        favList.removeAllViews()
        if (n == 0) {
            favList.addView(label("Aún no hay favoritas. Añade una pegando un enlace de Google Maps o unas coordenadas, " +
                "ponle un nombre y, al tocarla aquí, se fija como destino de la brújula.", 13f, col(R.color.text_muted)))
            return
        }
        val dest = Destination.current
        Favorites.list().forEachIndexed { i, f ->
            val active = dest != null && Favorites.at(dest.lat, dest.lon) === f
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                background = ripple(ColorDrawable(Color.TRANSPARENT))
                isClickable = true
                setOnClickListener {
                    Destination.set(this@MainActivity, Destination.Dest(f.lat, f.lon, f.name))
                    Toast.makeText(this@MainActivity, "Destino: ${f.name}", Toast.LENGTH_SHORT).show()
                    renderFavorites()
                }
                setOnLongClickListener { favMenu(i, f); true }
            }
            val star = label(if (active) "✓" else "★", 14f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = circle(col(if (active) R.color.ok else R.color.primary))
            }
            row.addView(star, LinearLayout.LayoutParams(dp(30), dp(30)))
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            mid.addView(label(f.name, 15f, col(R.color.text), true))
            mid.addView(label(
                if (active) "Destino activo" else "${"%.5f".format(Locale.US, f.lat)}, ${"%.5f".format(Locale.US, f.lon)}",
                13f, col(if (active) R.color.ok else R.color.text_muted)))
            row.addView(mid, lp(w = 0, weight = 1f))
            row.addView(label("⋮", 22f, col(R.color.text_muted)).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(6), 0)
                setOnClickListener { favMenu(i, f) }
            }, LinearLayout.LayoutParams(WRAP, dp(40)))
            favList.addView(row, lp())
            if (i < n - 1) divider(favList)
        }
    }

    private fun favMenu(index: Int, f: Favorites.Fav) {
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(arrayOf("Renombrar", "Eliminar")) { _, which ->
                if (which == 0) {
                    val d = Destination.current
                    val wasActive = d != null && Favorites.at(d.lat, d.lon) === f      // ¿es el destino en uso?
                    askDestName(f.name, null, "Guardar") { nn ->
                        Favorites.rename(this, index, nn)
                        if (wasActive) Destination.set(this, Destination.Dest(f.lat, f.lon, Destination.cleanName(nn)))   // el ESP32 ve el nombre nuevo
                        renderFavorites()
                    }
                } else {
                    AlertDialog.Builder(this).setMessage("¿Eliminar «${f.name}» de favoritas?")
                        .setPositiveButton("Eliminar") { _, _ -> Favorites.remove(this, index); renderFavorites() }
                        .setNegativeButton("Cancelar", null).show()
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ pestañas (barra inferior)
    private class TabRef(val root: LinearLayout, val pill: FrameLayout, val glyph: TextView, val label: TextView, val dot: View)

    private val tabNames = listOf("Inicio", "Navegación", "Lactancia", "Ajustes")
    private val tabGlyphs = listOf("🏠", "🧭", "🍼", "⚙️")
    private lateinit var scrolls: List<ScrollView>
    private lateinit var setupBanner: LinearLayout
    private val tabRefs = ArrayList<TabRef>()
    private var currentTab = TAB_HOME

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16))
    }

    private fun pageTitle(p: LinearLayout, title: String, subtitle: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 26f, col(R.color.text), true))
        box.addView(label(subtitle, 13f, col(R.color.text_muted)))
        p.addView(box, lp(bottom = 16))
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(col(R.color.surface))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        tabRefs.clear()
        for (i in tabNames.indices) {
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = ripple(ColorDrawable(Color.TRANSPARENT)); isClickable = true
                setOnClickListener { showTab(i) }
            }
            val pill = FrameLayout(this)
            val glyph = TextView(this).apply { text = tabGlyphs[i]; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); gravity = Gravity.CENTER }
            pill.addView(glyph, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            val dot = View(this).apply { background = circle(col(R.color.bad)); visibility = View.GONE }
            pill.addView(dot, FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(3); rightMargin = dp(12)
            })
            tab.addView(pill, LinearLayout.LayoutParams(dp(60), dp(32)))
            val lbl = label(tabNames[i], 11f, col(R.color.text_muted)).apply { gravity = Gravity.CENTER }
            tab.addView(lbl, lp(top = 2))
            bar.addView(tab, lp(w = 0, weight = 1f))
            tabRefs.add(TabRef(tab, pill, glyph, lbl, dot))
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply { setBackgroundColor(col(R.color.stroke)) }, lp(h = 1))
        wrap.addView(bar)
        return wrap
    }

    private fun showTab(i: Int, scrollTo: View? = null) {
        currentTab = i
        scrolls.forEachIndexed { k, sv -> sv.visibility = if (k == i) View.VISIBLE else View.GONE }
        val tint = (col(R.color.primary) and 0x00FFFFFF) or (0x26 shl 24)
        tabRefs.forEachIndexed { k, t ->
            val sel = k == i
            t.pill.background = if (sel) rounded(tint, 16) else null     // "píldora" detrás del icono de la pestaña activa
            t.glyph.alpha = if (sel) 1f else 0.55f
            t.label.setTextColor(col(if (sel) R.color.primary else R.color.text_muted))
            t.label.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        if (scrollTo != null) scrolls[i].post { scrolls[i].smoothScrollTo(0, scrollTo.top) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentTab != TAB_HOME) showTab(TAB_HOME) else super.onBackPressed()   // atrás vuelve primero a Inicio
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
    }

    // ------------------------------------------------------------ modo lactancia (ajustes del ESP32)
    private fun refreshLactancia() {
        val ref = lactSwitch ?: return
        val shown = EspConfig.shownLact()
        if (ref.sw.isChecked != shown) { lactUpdating = true; ref.sw.isChecked = shown; lactUpdating = false }
        ref.sub.setIfChanged(EspConfig.statusText())
        rowLactNet?.sub?.setIfChanged(EspConfig.netSummary())
        rowPair?.sub?.setIfChanged(EspConfig.pairText())
        lockSwitch?.let { ls ->
            if (ls.sw.isChecked != EspConfig.lockOn()) { lockUpdating = true; ls.sw.isChecked = EspConfig.lockOn(); lockUpdating = false }
            ls.sub.setIfChanged(EspConfig.lockText())
        }
        EspConfig.consumePairMessage()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        if (EspConfig.consumeNeedPair()) startPairFlow()
    }

    /** Emparejar: el ESP32 enseña un código de 6 cifras en SU pantalla y aquí se escribe. Solo así acepta ajustes. */
    private fun startPairFlow() {
        if (pairDialogOpen) return
        val err = EspConfig.startPairing()
        if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return }
        pairDialogOpen = true
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "000000"; setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(4)) }
        box.addView(et)
        AlertDialog.Builder(this).setTitle("Emparejar con el ESP32")
            .setMessage("Mira la pantalla del ESP32: debe enseñar un código de 6 cifras (dura 90 s; tocarlo lo cierra). " +
                "Escríbelo aquí. Al emparejar se activa el bloqueo: solo los móviles emparejados (hasta 4) podrán cambiar " +
                "los ajustes del ESP32. Cada móvil se empareja una vez.")
            .setView(box)
            .setPositiveButton("Emparejar") { _, _ ->
                EspConfig.confirmPairing(et.text.toString())?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { pairDialogOpen = false }
            .show()
    }

    private fun lactNetDialog() {
        val n = EspConfig.loadNet()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(label(
            "Déjalo vacío para usar el valor por defecto del firmware (LactanciaConfig.h). Si rellenas alguna red, " +
                "sustituyen a las del firmware. Los datos viajan por Bluetooth (corto alcance) y el ESP32 nunca los devuelve.",
            12f, col(R.color.text_muted)), lp())

        fun field(title: String, value: String, secret: Boolean = false, number: Boolean = false): EditText {
            box.addView(label(title, 13f, col(R.color.text_muted)), lp(top = 10))
            val e = EditText(this).apply {
                setSingleLine()
                inputType = when {
                    number -> InputType.TYPE_CLASS_NUMBER
                    secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                setText(value)
            }
            box.addView(e, lp())
            return e
        }
        val api = field("URL de la API", n.api)
        val tok = field("Token de la API", n.tok, secret = true)
        val poll = field("Consultar el estado cada (segundos, 10-3600; vacío = por defecto)", if (n.pollSec > 0) n.pollSec.toString() else "", number = true)
        val ssid = ArrayList<EditText>(); val pass = ArrayList<EditText>()
        for (i in 0 until EspConfig.MAX_WIFI) {
            val w = n.wifi.getOrNull(i) ?: ("" to "")
            ssid.add(field("WiFi ${i + 1}: nombre de la red (solo 2,4 GHz)", w.first))
            pass.add(field("WiFi ${i + 1}: clave (vacío = red abierta)", w.second, secret = true))
        }

        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Servidor y WiFi del ESP32").setView(sv)
            .setPositiveButton("Guardar y enviar") { _, _ ->
                val net = EspConfig.Net(
                    api = api.text.toString().trim(), tok = tok.text.toString().trim(),
                    pollSec = poll.text.toString().trim().toIntOrNull() ?: 0,
                    wifi = (0 until EspConfig.MAX_WIFI).map { ssid[it].text.toString().trim() to pass[it].text.toString() }
                )
                EspConfig.saveNet(net)
                val err = EspConfig.sendNetwork(net)
                Toast.makeText(this,
                    err ?: "Enviado al ESP32 ✓. Se aplica la próxima vez que entre en modo lactancia.",
                    Toast.LENGTH_LONG).show()
                rowLactNet?.sub?.text = EspConfig.netSummary()
                if (EspConfig.consumeNeedPair()) startPairFlow()
            }
            .setNeutralButton("Valores por defecto") { _, _ ->
                val err = EspConfig.sendReset()
                Toast.makeText(this, err ?: "Restaurados los valores por defecto del firmware", Toast.LENGTH_LONG).show()
                rowLactNet?.sub?.text = EspConfig.netSummary()
                if (EspConfig.consumeNeedPair()) startPairFlow()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ avisos de radar
    private fun radarCfgSummary(): String {
        val c = RadarConfig.current
        return "1.er aviso a ${c.alertFast} / ${c.alertMid} / ${c.alertSlow} m (rápido / medio / lento)"
    }

    private fun radarDbSummary(): String {
        if (RadarDb.count == 0) return "Sin descargar · toca para descargar (necesita internet)"
        val d = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(RadarDb.updatedAt))
        return "${RadarDb.count} radares · actualizada el $d · toca para actualizar"
    }

    private fun updateRadarDb() {
        if (RadarUpdater.running) { Toast.makeText(this, "Ya se está descargando", Toast.LENGTH_SHORT).show(); return }
        rowRadarDb?.sub?.text = "Descargando…"
        RadarUpdater.update(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            rowRadarDb?.sub?.text = radarDbSummary()
        }
    }

    private fun numField(parent: LinearLayout, title: String, value: Int): EditText {
        parent.addView(label(title, 13f, col(R.color.text_muted)), lp(top = 10))
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText(value.toString())
        }
        parent.addView(e, lp())
        return e
    }

    private fun radarConfigDialog() {
        val c = RadarConfig.current
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(label(
            "El 2.º aviso salta siempre a la mitad de la distancia del 1.º. Los metros solo se escriben en la " +
                "pantalla si estás a menos de la distancia indicada para tu velocidad. " +
                "Referencia: 1000 m a 120 km/h son unos 30 s de antelación. " +
                "El aviso de exceso usa el límite de la vía en el radar; solo sale si ese límite se conoce.", 12f, col(R.color.text_muted)), lp())
        val slow = numField(box, "Velocidad «lenta» por debajo de (km/h)", c.slowBelow)
        val fast = numField(box, "Velocidad «rápida» por encima de (km/h)", c.fastAbove)
        val af = numField(box, "1.er aviso a (m) · rápido", c.alertFast)
        val am = numField(box, "1.er aviso a (m) · medio", c.alertMid)
        val asl = numField(box, "1.er aviso a (m) · lento", c.alertSlow)
        val sf = numField(box, "Mostrar metros a menos de (m) · rápido", c.showFast)
        val sm = numField(box, "Mostrar metros a menos de (m) · medio", c.showMid)
        val ss = numField(box, "Mostrar metros a menos de (m) · lento", c.showSlow)
        val sec = numField(box, "Duración de cada alerta roja (s)", c.alertSeconds)
        val stf = numField(box, "Actualizar metros cada (m) · rápido", c.stepFast)
        val stm = numField(box, "Actualizar metros cada (m) · medio", c.stepMid)
        val sts = numField(box, "Actualizar metros cada (m) · lento", c.stepSlow)
        val ovm = numField(box, "Avisar de exceso si superas el límite de la vía en más de (km/h)", c.overMargin)
        val rl = CheckBox(this).apply {
            text = "Consultar el límite de la vía por internet (OpenStreetMap) y guardarlo"
            isChecked = c.roadLimits
        }
        box.addView(rl, lp(top = 12))
        fun n(e: EditText, d: Int) = e.text.toString().trim().toIntOrNull() ?: d
        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Avisos de radar").setView(sv)
            .setPositiveButton("Guardar") { _, _ ->
                RadarConfig.save(this, c.copy(
                    slowBelow = n(slow, c.slowBelow), fastAbove = n(fast, c.fastAbove),
                    alertFast = n(af, c.alertFast), alertMid = n(am, c.alertMid), alertSlow = n(asl, c.alertSlow),
                    showFast = n(sf, c.showFast), showMid = n(sm, c.showMid), showSlow = n(ss, c.showSlow),
                    alertSeconds = n(sec, c.alertSeconds),
                    stepFast = n(stf, c.stepFast), stepMid = n(stm, c.stepMid), stepSlow = n(sts, c.stepSlow),
                    overMargin = n(ovm, c.overMargin), roadLimits = rl.isChecked))
                rowRadarCfg?.sub?.text = radarCfgSummary()
                Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Valores por defecto") { _, _ ->
                RadarConfig.save(this, RadarConfig(enabled = c.enabled))
                rowRadarCfg?.sub?.text = radarCfgSummary()
                Toast.makeText(this, "Restaurados los valores por defecto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun testRadarDialog() {
        val items = RadarMonitor.TestCase.values()
        AlertDialog.Builder(this).setTitle("Probar aviso de radar")
            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                val err = RadarMonitor.startTest(items[which])
                Toast.makeText(
                    this,
                    err ?: "Enviado «${items[which].label}». Se ve en modo GPS o brújula; suena si el sonido del ESP32 está activado.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ pruebas del ESP32
    private fun testGpsDialog() {
        val items = TestSignals.Maneuver.values()
        AlertDialog.Builder(this).setTitle("Probar indicación GPS")
            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                val err = TestSignals.startGps(items[which])
                Toast.makeText(
                    this,
                    err ?: "Enviado «${items[which].label}». El ESP32 debe estar en modo GPS (no brújula).",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun testCompassDialog() {
        val items = TestSignals.compassCases
        AlertDialog.Builder(this).setTitle("Probar dirección de brújula")
            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                val err = TestSignals.startCompass(items[which])
                Toast.makeText(
                    this,
                    err ?: "Enviado «${items[which].label}». En el ESP32 activa la brújula (botón de la aguja).",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancelar", null).show()
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
