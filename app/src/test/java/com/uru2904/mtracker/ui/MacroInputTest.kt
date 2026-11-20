package com.uru2904.mtracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MacroInputTest {
    @Test fun calculaGrasasSiFaltan() {
        // Leche Protein 400 ml: 168 kcal, 21 P, 18,4 C -> grasas ~0
        val r = macroInput("168", "21", "18,4", "")!!
        assertEquals(3, r.auto)
        assertEquals(1.2, r.fat, 0.2) // la etiqueta redondea; da ~1 g
    }

    @Test fun calculaKcalSiFaltan() {
        val r = macroInput("", "10", "20", "5")!!
        assertEquals(165.0, r.kcal, 0.01)
    }

    @Test fun avisaSiNoCierran() {
        // El error real: carbos y grasas cruzados
        val r = macroInput("168", "21", "11,5", "18,4")!!
        assertNotNull(r.mismatchKcal)
    }

    @Test fun noAvisaSiCierran() {
        assertNull(macroInput("168", "21", "18,4", "0")!!.mismatchKcal)
    }

    @Test fun sinKcalNiTresMacrosNoSePuede() {
        assertNull(macroInput("", "10", "", ""))
    }
}
