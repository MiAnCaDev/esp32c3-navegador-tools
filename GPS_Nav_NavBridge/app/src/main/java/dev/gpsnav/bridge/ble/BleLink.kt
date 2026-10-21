package dev.gpsnav.bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import dev.gpsnav.bridge.AppLog
import dev.gpsnav.bridge.protocol.Frame
import java.util.UUID

/**
 * Cliente BLE mínimo: se conecta al ESP32 (por dirección elegida o buscando el servicio Nordic UART),
 * negocia MTU y escribe tramas en la característica RX. Reconecta solo si se pierde la conexión.
 * start()/stop()/reconnect() se llaman desde el hilo principal.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    /** Dirección MAC elegida por el usuario; null = buscar el primer ESP32 con el servicio NUS. */
    private val targetAddress: () -> String?,
    private val onStatus: (String) -> Unit,
    private val onReady: () -> Unit,
    /** Se llama UNA vez por pérdida de conexión si no se recupera en GIVE_UP_MS (sigue reintentando). */
    private val onGaveUp: () -> Unit
) {
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Tiempo que se intenta reconectar antes de avisar con la vibración. */
        const val GIVE_UP_MS = 20_000L
        private const val TAG = "BLE"

        fun gattStatus(s: Int): String = when (s) {
            0 -> "0 (OK)"
            8 -> "8 (tiempo agotado: sin señal, ESP32 apagado o lejos)"
            19 -> "19 (el ESP32 cerró la conexión)"
            22 -> "22 (cerrada por el móvil)"
            34 -> "34 (LMP timeout)"
            62 -> "62 (no se pudo establecer la conexión)"
            133 -> "133 (error genérico de Android BLE)"
            257 -> "257 (fallo)"
            else -> "$s"
        }

        private fun stateName(s: Int) = when (s) {
            0 -> "DESCONECTADO"; 1 -> "CONECTANDO"; 2 -> "CONECTADO"; 3 -> "DESCONECTANDO"; else -> "$s"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var lastDevice: BluetoothDevice? = null
    private var running = false
    private var scanning = false
    private var readySince = 0L
    private var lastWriteFailLog = 0L

    /** true si el ESP32 ha avisado de que apaga el Bluetooth a propósito (modo lactancia). */
    @Volatile
    var sleeping = false
        private set

    /** Nombre del dispositivo al que se conecta/está conectado (si se conoce). */
    @Volatile
    var deviceName: String? = null
        private set

    private val giveUp = Runnable {
        if (running && !ready) {
            AppLog.w(TAG, "Sin conexión tras ${GIVE_UP_MS / 1000} s -> aviso con vibración (sigue reintentando)")
            onGaveUp()
        }
    }

    private val queue = ArrayDeque<ByteArray>()
    private var writing = false

    @Volatile
    var ready = false
        private set

    fun start() {
        running = true
        AppLog.i(TAG, "start(): objetivo=${targetAddress() ?: "automático (servicio NUS)"}")
        connectOrScan()
    }

    /** Cierra la conexión actual y vuelve a conectar (p. ej. tras elegir otro dispositivo). */
    fun reconnect() {
        if (!running) return
        AppLog.i(TAG, "reconnect(): nuevo objetivo=${targetAddress() ?: "automático"}")
        main.removeCallbacksAndMessages(null)
        stopScan()
        ready = false
        synchronized(queue) { queue.clear(); writing = false }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; lastDevice = null; deviceName = null; sleeping = false
        connectOrScan()
    }

    fun stop() {
        AppLog.i(TAG, "stop()")
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; ready = false; sleeping = false
        synchronized(queue) { queue.clear(); writing = false }
        onStatus("Parado")
    }

    // ------------------------------------------------------------ envío
    fun send(f: Frame) {
        if (!ready) return
        val data = f.encode()
        val step = (mtu - 3).coerceAtLeast(20)
        synchronized(queue) {
            if (queue.size > 400) {
                AppLog.w(TAG, "Cola de envío saturada (${queue.size} bloques): se descartan datos antiguos")
                queue.clear()
            }
            var i = 0
            while (i < data.size) {
                val e = minOf(i + step, data.size)
                queue.addLast(data.copyOfRange(i, e)); i = e
            }
        }
        pump()
    }

    private fun pump() {
        val chunk = synchronized(queue) {
            if (writing || queue.isEmpty()) return
            writing = true
            queue.removeFirst()
        }
        val g = gatt; val c = rx
        if (g == null || c == null) { synchronized(queue) { writing = false }; return }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = chunk
                g.writeCharacteristic(c)
            }
        }
        if (!ok) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWriteFailLog > 5000) {
                lastWriteFailLog = now
                AppLog.w(TAG, "writeCharacteristic rechazado (ocupado); se reintenta")
            }
            synchronized(queue) { queue.addFirst(chunk); writing = false }
            main.postDelayed({ pump() }, 50)
        }
    }

    // ------------------------------------------------------------ conexión
    private fun retry() {
        if (running) {
            AppLog.d(TAG, "Reintento en 2 s")
            main.postDelayed({ connectOrScan() }, if (sleeping) 5000L else 2000L)
        }
    }

    private fun connectOrScan() {
        if (!running) return
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            AppLog.w(TAG, "Bluetooth apagado o no disponible")
            onStatus("Bluetooth apagado"); retry(); return
        }
        val addr = targetAddress()
        if (addr != null) {
            val chosen = runCatching { bt.getRemoteDevice(addr) }.getOrNull()
            if (chosen != null) connect(chosen) else { AppLog.e(TAG, "Dirección no válida: $addr"); retry() }
            return
        }
        val dev = lastDevice
        if (dev != null) connect(dev) else scan()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan(); lastDevice = result.device
            deviceName = result.scanRecord?.deviceName
            AppLog.i(TAG, "Escaneo: encontrado ${deviceName ?: "?"} ${result.device.address} (${result.rssi} dBm)")
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            AppLog.e(TAG, "Escaneo fallido, código $errorCode")
            onStatus("Error de escaneo ($errorCode)"); retry()
        }
    }

    private fun scan() {
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) { onStatus("Bluetooth apagado"); retry(); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        sc.startScan(listOf(filter), settings, scanCb)
        AppLog.i(TAG, "Escaneo iniciado (filtro servicio NUS)")
        onStatus("Buscando ESP32...")
    }

    private fun stopScan() {
        if (scanning) { runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }; scanning = false }
    }

    private fun connect(dev: BluetoothDevice) {
        deviceName = deviceName ?: runCatching { dev.name }.getOrNull()
        AppLog.i(TAG, "connectGatt -> ${deviceName ?: "?"} ${dev.address}")
        onStatus("Conectando...")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun becomeReady(g: BluetoothGatt) {
        if (ready || gatt !== g) return
        ready = true; readySince = SystemClock.elapsedRealtime()
        sleeping = false
        main.removeCallbacks(giveUp)
        AppLog.i(TAG, "LISTO: conectado a ${deviceName ?: g.device.address}, MTU=$mtu")
        onStatus("Conectado (MTU $mtu)")
        onReady()
    }

    /** Mensajes que manda el ESP32 por la característica TX. */
    private fun handleIncoming(bytes: ByteArray) {
        val s = String(bytes, Charsets.UTF_8)
        AppLog.d(TAG, "ESP32 -> móvil: ${s.trim()}")
        if (s.contains("\"bye\"")) {
            sleeping = true
            main.removeCallbacks(giveUp)
            AppLog.i(TAG, "El ESP32 avisa: pausa voluntaria (modo lactancia). No se dará alarma.")
            onStatus("ESP32 en pausa (modo lactancia)")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            AppLog.i(TAG, "Estado GATT: ${stateName(newState)}, status=${gattStatus(status)}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasReady = ready
                if (wasReady) {
                    val secs = (SystemClock.elapsedRealtime() - readySince) / 1000
                    AppLog.w(TAG, "Conexión PERDIDA tras ${secs} s conectado (status=${gattStatus(status)})")
                } else {
                    AppLog.w(TAG, "No se pudo conectar (status=${gattStatus(status)})")
                }
                ready = false
                synchronized(queue) { queue.clear(); writing = false }
                runCatching { g.close() }
                gatt = null
                if (!wasReady) lastDevice = null   // por si la dirección ya no vale: reescanear
                if (wasReady && !sleeping) {       // conexión perdida: cuenta atrás para avisar
                    main.removeCallbacks(giveUp); main.postDelayed(giveUp, GIVE_UP_MS)
                }
                onStatus(if (sleeping) "ESP32 en pausa (modo lactancia)" else "Desconectado, reintentando...")
                retry()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            AppLog.i(TAG, "MTU=$m status=${gattStatus(status)}")
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX)
            if (c == null) {
                AppLog.e(TAG, "Servicios descubiertos (status=${gattStatus(status)}) pero NO está el servicio NUS: ¿es un ESP32 Nav?")
                onStatus("Servicio NUS no encontrado"); g.disconnect(); return
            }
            rx = c
            // Suscripción a los avisos del ESP32 (p. ej. "bye" al entrar en modo lactancia)
            val tx = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_TX)
            val cccd = tx?.getDescriptor(CCCD)
            if (tx != null && cccd != null) {
                g.setCharacteristicNotification(tx, true)
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
                if (ok) {
                    main.postDelayed({ becomeReady(g) }, 3000)   // por si el descriptor nunca confirma
                    return
                }
                AppLog.w(TAG, "No se pudo suscribir a las notificaciones del ESP32")
            }
            becomeReady(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            AppLog.i(TAG, "Suscripción a avisos del ESP32: status=${gattStatus(status)}")
            becomeReady(g)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { handleIncoming(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) AppLog.w(TAG, "Error al escribir, status=${gattStatus(status)}")
            synchronized(queue) { writing = false }
            pump()
        }
    }
}
