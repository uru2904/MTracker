package com.uru2904.mtracker.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Turnos de la rotación semanal. */
enum class Shift(val start: LocalTime, val end: LocalTime) {
    TM(LocalTime.of(7, 0), LocalTime.of(15, 0)),
    TT(LocalTime.of(15, 0), LocalTime.of(23, 0)),
    TN(LocalTime.of(23, 0), LocalTime.of(7, 0)),
}

/** Ventana de sueño esperada (inicio y fin; el fin puede caer al día siguiente). */
data class SleepWindow(val start: LocalTime, val end: LocalTime)

/**
 * Calendario de turnos rotativos: una semana de cada turno, en el orden TT -> TM -> TN.
 * La semana se cuenta de lunes a domingo; el TN de una semana arranca el domingo
 * anterior a las 23:00 (eso no cambia qué semana es, solo cuándo empieza el primer turno).
 *
 * [anchorMonday] es un lunes cuya semana tuvo el turno [anchorShift].
 */
class ShiftCalendar(
    private val anchorMonday: LocalDate = LocalDate.of(2026, 11, 9),
    private val anchorShift: Shift = Shift.TT,
) {
    private val rotation = listOf(Shift.TT, Shift.TM, Shift.TN)

    init {
        require(anchorMonday.dayOfWeek == DayOfWeek.MONDAY) { "El ancla tiene que ser un lunes" }
    }

    /** Turno de la semana que contiene [date]. */
    fun shiftForWeekOf(date: LocalDate): Shift {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = ChronoUnit.WEEKS.between(anchorMonday, monday)
        val base = rotation.indexOf(anchorShift)
        val idx = Math.floorMod(base + weeks, rotation.size.toLong()).toInt()
        return rotation[idx]
    }

    /** Hora en la que cambia el día lógico: 07:00 en semanas de TN, 02:00 en TM y TT. */
    fun dayCutoffFor(date: LocalDate): LocalTime =
        if (shiftForWeekOf(date) == Shift.TN) LocalTime.of(7, 0) else LocalTime.of(2, 0)

    /**
     * Día lógico al que pertenece un momento. Lo que pasa antes del corte cuenta
     * para el día anterior (por ejemplo, una comida a las 03:00 en semana de TN).
     */
    fun logicalDate(moment: LocalDateTime): LocalDate {
        val date = moment.toLocalDate()
        return if (moment.toLocalTime() < dayCutoffFor(date)) date.minusDays(1) else date
    }

    /** Ventana de sueño esperada según el turno de la semana. Valores por defecto, ajustables. */
    fun expectedSleep(date: LocalDate): SleepWindow = when (shiftForWeekOf(date)) {
        Shift.TM -> SleepWindow(LocalTime.of(22, 30), LocalTime.of(6, 0))
        Shift.TT -> SleepWindow(LocalTime.of(1, 0), LocalTime.of(9, 0))
        Shift.TN -> SleepWindow(LocalTime.of(8, 30), LocalTime.of(15, 30))
    }
}
