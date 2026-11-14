package com.uru2904.mtracker.ble

/** Lectura de una balanza Xiaomi a partir del anuncio Bluetooth (sin emparejar). */
data class ScaleReading(val kg: Double, val stabilized: Boolean, val removed: Boolean)

/**
 * Protocolo público de las balanzas Xiaomi (usado por openScale y ESPHome):
 * - Mi Scale / Mi Smart Scale 2 (XMTZC01HM, XMTZC04HM): service data 0x181D.
 *   byte 0 = estado (bit0 lb, bit4 jin, bit5 estable, bit7 se bajó), bytes 1-2 = peso little endian.
 * - Mi Body Composition Scale (XMTZC02HM, XMTZC05HM): service data 0x181B.
 *   byte 0 = unidad (bit0 lb, bit6 jin?), byte 1 = control (bit5 estable, bit7 se bajó), bytes 11-12 = peso.
 * El divisor es 200 en kg y 100 en lb/jin.
 */
object MiScaleParser {
    const val UUID_WEIGHT = 0x181D
    const val UUID_BODY = 0x181B

    fun parse(serviceUuid16: Int, data: ByteArray): ScaleReading? = when (serviceUuid16) {
        UUID_WEIGHT -> {
            if (data.size < 3) null else {
                val status = data[0].toInt() and 0xFF
                val raw = u16(data, 1)
                toReading(raw, lb = status and 0x01 != 0, jin = status and 0x10 != 0, stable = status and 0x20 != 0, removed = status and 0x80 != 0)
            }
        }
        UUID_BODY -> {
            if (data.size < 13) null else {
                val unit = data[0].toInt() and 0xFF
                val ctrl = data[1].toInt() and 0xFF
                val raw = u16(data, 11)
                toReading(raw, lb = unit and 0x01 != 0, jin = unit and 0x40 != 0, stable = ctrl and 0x20 != 0, removed = ctrl and 0x80 != 0)
            }
        }
        else -> null
    }

    private fun toReading(raw: Int, lb: Boolean, jin: Boolean, stable: Boolean, removed: Boolean): ScaleReading? {
        val kg = when {
            lb -> raw / 100.0 * 0.45359237
            jin -> raw / 100.0 * 0.5
            else -> raw / 200.0
        }
        if (kg < 1 || kg > 200) return null
        return ScaleReading(Math.round(kg * 10) / 10.0, stable, removed)
    }

    private fun u16(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

    fun hex(d: ByteArray) = d.joinToString(" ") { "%02X".format(it) }
}
