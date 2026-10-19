package dev.gpsnav.bridge.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import android.widget.Toast

/**
 * Diálogo que busca dispositivos BLE y deja elegir uno.
 * Los que anuncian el servicio del ESP32 Nav salen primero y marcados con ★.
 */
@SuppressLint("MissingPermission")
class DevicePicker(
    private val activity: Activity,
    private val currentAddress: String?,
    private val currentName: String?,
    private val onPicked: (address: String?, name: String?) -> Unit
) {
    private class Found(val name: String, val nus: Boolean, var rssi: Int)
    private class Item(val address: String?, val name: String?, val label: String) {
        override fun toString() = label
    }

    private val found = LinkedHashMap<String, Found>()
    private lateinit var adapter: ArrayAdapter<Item>

    private fun items(): List<Item> {
        val list = ArrayList<Item>()
        list += Item(null, null, "Automático\nSe conecta al primer ESP32 que encuentre")
        if (currentAddress != null) {
            val n = currentName ?: "ESP32"
            list += Item(currentAddress, n, "$n  (actual)\n$currentAddress")
        }
        found.entries
            .filter { it.key != currentAddress }
            .sortedWith(compareByDescending<Map.Entry<String, Found>> { it.value.nus }
                .thenByDescending { it.value.rssi })
            .forEach { (addr, f) ->
                list += Item(addr, f.name, "${f.name}${if (f.nus) "  ★" else ""}\n$addr  ·  ${f.rssi} dBm")
            }
        return list
    }

    private fun refresh() {
        adapter.clear(); adapter.addAll(items()); adapter.notifyDataSetChanged()
    }

    fun show() {
        val scanner = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(activity, "Activa el Bluetooth", Toast.LENGTH_LONG).show(); return
        }
        adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, items())

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                val nus = r.scanRecord?.serviceUuids?.contains(ParcelUuid(BleLink.NUS_SERVICE)) == true
                found[r.device.address] = Found(name, nus, r.rssi)
                refresh()
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Elige tu ESP32  (★ = ESP32 Nav)")
            .setAdapter(adapter) { _, which ->
                adapter.getItem(which)?.let { onPicked(it.address, it.name) }
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnDismissListener { runCatching { scanner.stopScan(cb) } }
        dialog.show()
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }
}
