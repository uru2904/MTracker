package com.uru2904.mtracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyModelTest {
    private val today = LocalDate.of(2026, 12, 7)

    @Test fun faltanDatos() {
        val r = EnergyModel.checkIn(today, mapOf(today.minusDays(1) to 2200.0), emptyList(), 2250, 0.4)
        assertFalse(r.ready)
        assertEquals(1, r.completeDays)
    }

    @Test fun gastoRealYObjetivoAcotado() {
        // 28 días comiendo 2.250 y bajando 0,5 kg/semana -> gasto ≈ 2.250 + 0,5×7700/7 = 2.800
        val intake = (1..21L).associate { today.minusDays(it) to 2250.0 }
        val weights = (0..40L).filter { it % 2 == 0L }.map { d -> today.minusDays(40 - d) to (75.0 - 0.5 / 7 * d) }
        val r = EnergyModel.checkIn(today, intake, weights, 2250, 0.4)
        assertTrue(r.ready)
        assertTrue("gasto ${r.tdee}", r.tdee!! in 2700..2900)
        // Objetivo ≈ 2.800 − 440 = 2.360 (dentro del límite de ±150)
        assertTrue("objetivo ${r.proposedKcal}", r.proposedKcal!! in 2300..2400)
        // Si el gasto diera mucho más, el cambio se limita a +150
        val big = EnergyModel.checkIn(today, intake.mapValues { 2600.0 }, weights, 2250, 0.4)
        assertEquals(2400, big.proposedKcal)
    }

    @Test fun carbos() = assertEquals(266, EnergyModel.carbsFor(2250, 150, 65))
}
