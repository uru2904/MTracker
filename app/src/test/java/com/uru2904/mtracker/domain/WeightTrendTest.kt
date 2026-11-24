package com.uru2904.mtracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WeightTrendTest {
    private val d0 = LocalDate.of(2026, 11, 1)

    @Test fun pesoConstanteDaTendenciaIgual() {
        val w = (0L..10L).map { d0.plusDays(it) to 74.0 }
        assertEquals(74.0, WeightTrend.daily(w).last().second, 1e-9)
    }

    @Test fun unaPesadaAltaMueveSoloUnPoco() {
        val w = (0L..9L).map { d0.plusDays(it) to 74.0 } + (d0.plusDays(10) to 75.0)
        assertEquals(74.1, WeightTrend.daily(w).last().second, 1e-9)
    }

    @Test fun ritmoDeBajadaLineal() {
        // Baja 0,1 kg por día durante 60 días: la tendencia termina bajando ~0,7 kg/semana
        val w = (0L..60L).map { d0.plusDays(it) to 80.0 - 0.1 * it }
        val r = WeightTrend.weeklyRate(w, d0.plusDays(60))!!
        assertEquals(-0.7, r, 0.05)
    }

    @Test fun sinPesadasRecientesNoHayRitmo() {
        val w = listOf(d0 to 74.0, d0.plusDays(1) to 73.8)
        assertNull(WeightTrend.weeklyRate(w, d0.plusDays(40)))
    }

    @Test fun huecosSeInterpolan() {
        val w = listOf(d0 to 74.0, d0.plusDays(4) to 74.0)
        assertEquals(5, WeightTrend.daily(w).size)
    }

    @Test fun proyeccion() {
        assertEquals(72.0, WeightTrend.projection(d0 to 74.0, -0.5, d0.plusDays(28)), 1e-9)
    }
}
