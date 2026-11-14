package com.uru2904.mtracker.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiScaleParserTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun miScale2Estable() {
        // 73,6 kg -> 73,6 * 200 = 14720 = 0x3980 -> bytes 80 39; estado 0x22 (estable)
        val r = MiScaleParser.parse(0x181D, b(0x22, 0x80, 0x39, 0xEA, 0x07, 11, 14, 8, 30, 0))!!
        assertEquals(73.6, r.kg, 0.001)
        assertTrue(r.stabilized)
        assertFalse(r.removed)
    }

    @Test fun miScale2MidiendoYBajado() {
        val midiendo = MiScaleParser.parse(0x181D, b(0x02, 0x80, 0x39))!!
        assertFalse(midiendo.stabilized)
        val bajado = MiScaleParser.parse(0x181D, b(0xA2, 0x80, 0x39))!!
        assertTrue(bajado.removed)
    }

    @Test fun bodyCompositionScale() {
        val d = ByteArray(13); d[0] = 0x02; d[1] = 0x24; d[11] = 0x80.toByte(); d[12] = 0x39
        val r = MiScaleParser.parse(0x181B, d)!!
        assertEquals(73.6, r.kg, 0.001)
        assertTrue(r.stabilized)
    }
}
