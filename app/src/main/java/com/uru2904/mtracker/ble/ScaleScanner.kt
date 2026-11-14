package com.uru2904.mtracker.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/** Escanea anuncios Bluetooth y avisa cada lectura de balanza Xiaomi. También expone datos crudos para diagnóstico. */
class ScaleScanner(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: ScanCallback? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private fun uuid16(v: Int) = ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(v))

    /** [onReading]: lectura decodificada. [onRaw]: (nombre, dirección, texto con los datos) de equipos Xiaomi o con datos de balanza. */
    @SuppressLint("MissingPermission")
    fun start(onReading: (ScaleReading) -> Unit, onRaw: (String) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                var matched = false
                for (u in listOf(MiScaleParser.UUID_WEIGHT, MiScaleParser.UUID_BODY)) {
                    val data = record.getServiceData(uuid16(u)) ?: continue
                    matched = true
                    onRaw("${record.deviceName ?: "?"} · %04X · ${MiScaleParser.hex(data)}".format(u))
                    MiScaleParser.parse(u, data)?.let(onReading)
                }
                val name = record.deviceName.orEmpty()
                if (!matched && (name.contains("SCALE", true) || name.startsWith("MI", true))) {
                    val sd = record.serviceData?.entries?.joinToString(" | ") { "${it.key}: ${MiScaleParser.hex(it.value)}" }.orEmpty()
                    onRaw("$name · sin datos de balanza · $sd")
                }
            }
        }
        callback = cb
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        callback = null
    }
}
