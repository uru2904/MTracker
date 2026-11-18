package com.uru2904.mtracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReviewRangeTest {
    private val mon23 = LocalDate.of(2026, 11, 23)

    @Test fun primerCheckInArrancaEnLaPrimeraComida() {
        val r = reviewRange(null, LocalDate.of(2026, 11, 14), mon23)
        assertEquals(LocalDate.of(2026, 11, 14), r.first())
        assertEquals(LocalDate.of(2026, 11, 22), r.last())
        assertEquals(9, r.size)
    }

    @Test fun semanaNormalSonSieteDias() {
        val r = reviewRange(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 14), mon23)
        assertEquals(7, r.size)
        assertEquals(LocalDate.of(2026, 11, 16), r.first())
    }

    @Test fun nuncaMasDe21Dias() {
        val r = reviewRange(null, LocalDate.of(2026, 9, 1), mon23)
        assertEquals(21, r.size)
    }

    @Test fun sinComidasMuestraLaSemana() {
        assertEquals(7, reviewRange(null, null, mon23).size)
    }
}
