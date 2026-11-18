package com.uru2904.mtracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ShiftCalendarTest {
    private val cal = ShiftCalendar()

    @Test fun rotacionSemanal() {
        assertEquals(Shift.TT, cal.shiftForWeekOf(LocalDate.of(2026, 11, 14))) // sábado, semana TT
        assertEquals(Shift.TM, cal.shiftForWeekOf(LocalDate.of(2026, 11, 16)))
        assertEquals(Shift.TN, cal.shiftForWeekOf(LocalDate.of(2026, 11, 23)))
        assertEquals(Shift.TT, cal.shiftForWeekOf(LocalDate.of(2026, 11, 30)))
        assertEquals(Shift.TN, cal.shiftForWeekOf(LocalDate.of(2026, 11, 2))) // semana anterior al ancla
    }

    @Test fun cortePorTurno() {
        // Semana TN: una comida a las 05:00 del martes cuenta para el lunes
        assertEquals(LocalDate.of(2026, 11, 23), cal.logicalDate(LocalDateTime.of(2026, 11, 24, 5, 0)))
        // Semana TN: a las 07:30 ya es el mismo día
        assertEquals(LocalDate.of(2026, 11, 24), cal.logicalDate(LocalDateTime.of(2026, 11, 24, 7, 30)))
        // Semana TM: 01:30 cuenta para el día anterior, 02:30 no
        assertEquals(LocalDate.of(2026, 11, 16), cal.logicalDate(LocalDateTime.of(2026, 11, 17, 1, 30)))
        assertEquals(LocalDate.of(2026, 11, 17), cal.logicalDate(LocalDateTime.of(2026, 11, 17, 2, 30)))
    }

    @Test fun cambiarTurnoDeEstaSemana() {
        // Si esta semana (16/11) pasa a ser TN, la siguiente sigue la rotación TT -> TM -> TN -> TT
        val c = ShiftCalendar(ShiftConfig().withCurrent(LocalDate.of(2026, 11, 18), Shift.TN))
        assertEquals(Shift.TN, c.shiftForWeekOf(LocalDate.of(2026, 11, 16)))
        assertEquals(Shift.TT, c.shiftForWeekOf(LocalDate.of(2026, 11, 23)))
        assertEquals(Shift.TM, c.shiftForWeekOf(LocalDate.of(2026, 11, 30)))
    }

    @Test fun rotacionInvertida() {
        val c = ShiftCalendar(ShiftConfig(rotation = listOf(Shift.TT, Shift.TN, Shift.TM)))
        assertEquals(Shift.TN, c.shiftForWeekOf(LocalDate.of(2026, 11, 16)))
    }
}
