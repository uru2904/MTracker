package com.uru2904.mtracker.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Turnos de la rotación semanal: mañana, tarde y noche. */
enum class Shift { TM, TT, TN }

/** Ventana de sueño esperada (inicio y fin; el fin puede caer al día siguiente). */
data class SleepWindow(val start: LocalTime, val end: LocalTime)

/** Todo lo configurable de los turnos (Ajustes). Los valores por defecto son los de siempre. */
data class ShiftConfig(
    val anchorMonday: LocalDate = LocalDate.of(2026, 11, 9),
    val anchorShift: Shift = Shift.TT,
    val rotation: List<Shift> = listOf(Shift.TT, Shift.TM, Shift.TN),
    val sleep: Map<Shift, SleepWindow> = mapOf(
        Shift.TM to SleepWindow(LocalTime.of(22, 30), LocalTime.of(6, 0)),
        Shift.TT to SleepWindow(LocalTime.of(1, 0), LocalTime.of(9, 0)),
        Shift.TN to SleepWindow(LocalTime.of(8, 30), LocalTime.of(15, 30)),
    ),
    val cutoffNight: LocalTime = LocalTime.of(7, 0),
    val cutoffOther: LocalTime = LocalTime.of(2, 0),
) {
    /** Mueve el ancla para que la semana de [date] sea [shift], manteniendo la rotación. */
    fun withCurrent(date: LocalDate, shift: Shift) =
        copy(anchorMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), anchorShift = shift)
}

/**
 * Calendario de turnos rotativos: una semana de cada turno, en el orden TT -> TM -> TN.
 * La semana se cuenta de lunes a domingo.
 *
 * [anchorMonday] es un lunes cuya semana tuvo el turno [anchorShift].
 */
class ShiftCalendar(val config: ShiftConfig = ShiftConfig()) {
    private val anchorMonday = config.anchorMonday
    private val anchorShift = config.anchorShift
    private val rotation = config.rotation

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

    /** Hora en la que cambia el día lógico: por defecto 07:00 en semanas de TN y 02:00 en TM y TT. */
    fun dayCutoffFor(date: LocalDate): LocalTime =
        if (shiftForWeekOf(date) == Shift.TN) config.cutoffNight else config.cutoffOther

    /**
     * Día lógico al que pertenece un momento. Lo que pasa antes del corte cuenta
     * para el día anterior (por ejemplo, una comida a las 03:00 en semana de TN).
     */
    fun logicalDate(moment: LocalDateTime): LocalDate {
        val date = moment.toLocalDate()
        return if (moment.toLocalTime() < dayCutoffFor(date)) date.minusDays(1) else date
    }

    /** Ventana de sueño esperada según el turno de la semana. */
    fun expectedSleep(date: LocalDate): SleepWindow = config.sleep.getValue(shiftForWeekOf(date))
}
