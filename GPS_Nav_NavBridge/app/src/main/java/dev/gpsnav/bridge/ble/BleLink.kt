package dev.gpsnav.bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.gpsnav.bridge.protocol.Frame
import java.util.UUID

/**
 * Cliente BLE mínimo: busca el ESP32 por el servicio Nordic UART, conecta, negocia MTU
 * y escribe tramas en la característica RX. Reconecta solo si se pierde la conexión.
 * start()/stop() se llaman desde el hilo principal.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    /** Dirección MAC elegida por el usuario; null = buscar el primer ESP32 con el servicio NUS. */
    private val targetAddress: () -> String?,
    private val onStatus: (String) -> Unit,
    private val onReady: () -> Unit
) {
    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
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

    private val queue = ArrayDeque<ByteArray>()
    private var writing = false

    @Volatile
    var ready = false
        private set

    fun start() { running = true; connectOrScan() }

    /** Cierra la conexión actual y vuelve a conectar (p. ej. tras elegir otro dispositivo). */
    fun reconnect() {
        if (!running) return
        main.removeCallbacksAndMessages(null)
        stopScan()
        ready = false
        synchronized(queue) { queue.clear(); writing = false }
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; lastDevice = null
        connectOrScan()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; ready = false
        synchronized(queue) { queue.clear(); writing = false }
        onStatus("Parado")
    }

    // ------------------------------------------------------------ envío
    fun send(f: Frame) {
        if (!ready) return
        val data = f.encode()
        val step = (mtu - 3).coerceAtLeast(20)
        synchronized(queue) {
            if (queue.size > 400) queue.clear()   // atasco: mejor perder datos viejos
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
            synchronized(queue) { queue.addFirst(chunk); writing = false }
            main.postDelayed({ pump() }, 50)
        }
    }

    // ------------------------------------------------------------ conexión
    private fun retry() { if (running) main.postDelayed({ connectOrScan() }, 2000) }

    private fun connectOrScan() {
        if (!running) return
        val addr = targetAddress()
        if (addr != null) {
            val chosen = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull()
            if (chosen != null) connect(chosen) else { onStatus("Bluetooth apagado"); retry() }
            return
        }
        val dev = lastDevice
        if (dev != null) connect(dev) else scan()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan(); lastDevice = result.device; connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false; onStatus("Error de escaneo ($errorCode)"); retry()
        }
    }

    private fun scan() {
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) { onStatus("Bluetooth apagado"); retry(); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        sc.startScan(listOf(filter), settings, scanCb)
        onStatus("Buscando ESP32...")
    }

    private fun stopScan() {
        if (scanning) { runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }; scanning = false }
    }

    private fun connect(dev: BluetoothDevice) {
        onStatus("Conectando...")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasReady = ready
                ready = false
                synchronized(queue) { queue.clear(); writing = false }
                runCatching { g.close() }
                gatt = null
                if (!wasReady) lastDevice = null   // por si la dirección ya no vale: reescanear
                onStatus("Desconectado")
                retry()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = m
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX)
            if (c == null) { onStatus("Servicio NUS no encontrado"); g.disconnect(); return }
            rx = c; ready = true
            onStatus("Conectado (MTU $mtu)")
            onReady()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(queue) { writing = false }
            pump()
        }
    }
}
