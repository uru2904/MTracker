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

    /**
     * Empieza a escuchar. Devuelve null si arrancó, o un mensaje si no pudo.
     * [onAny]: cualquier anuncio Bluetooth (para mostrar que está escuchando). [onError]: Android frenó la búsqueda.
     */
    @SuppressLint("MissingPermission")
    fun start(
        onReading: (ScaleReading) -> Unit,
        onRaw: (String) -> Unit,
        onAny: () -> Unit = {},
        onError: (String) -> Unit = {},
    ): String? {
        val scanner = adapter?.bluetoothLeScanner ?: return "Bluetooth no disponible: prendé el Bluetooth"
        // Android bloquea en silencio a la app que arranca más de 5 búsquedas en 30 segundos.
        val now = System.currentTimeMillis()
        starts.removeAll { now - it > 30_000 }
        if (starts.size >= 4) {
            val wait = (30_000 - (now - starts.first())) / 1000 + 1
            return "Android limita los reinicios: probá de nuevo en $wait s"
        }
        stop()
        starts.add(now)
        val cb = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                onError(
                    when (errorCode) {
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, 6 /* SCANNING_TOO_FREQUENTLY */ ->
                            "Android frenó la búsqueda por demasiados intentos. Esperá 30 s y tocá Reiniciar escucha."
                        else -> "No se pudo escuchar el Bluetooth (error $errorCode)."
                    }
                )
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onAny()
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
        return runCatching { scanner.startScan(null, settings, cb); null }.getOrElse { "No se pudo escuchar: ${it.message}" }
    }

    companion object {
        /** Momentos de los últimos arranques (compartido entre pantallas). */
        private val starts = mutableListOf<Long>()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        callback = null
    }
}
