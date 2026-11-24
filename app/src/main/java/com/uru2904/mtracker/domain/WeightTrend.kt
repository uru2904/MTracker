package com.uru2904.mtracker.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Peso tendencia (como MacroFactor o Happy Scale): media móvil exponencial diaria del peso de balanza.
 * Los días sin pesada se completan interpolando entre pesadas; el promedio del día cuenta como un dato.
 */
object WeightTrend {
    /** Suavizado diario: cada día nuevo pesa un 10% (Hacker's Diet). */
    const val ALPHA = 0.1

    /** Tendencia de cada día, desde la primera hasta la última pesada. */
    fun daily(weights: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> {
        if (weights.isEmpty()) return emptyList()
        val byDay = weights.groupBy({ it.first }, { it.second }).mapValues { it.value.average() }.toSortedMap()
        val days = byDay.keys.toList()
        val out = mutableListOf<Pair<LocalDate, Double>>()
        var trend = byDay.getValue(days.first())
        out += days.first() to trend
        for (i in 1 until days.size) {
            val a = days[i - 1]; val b = days[i]
            val va = byDay.getValue(a); val vb = byDay.getValue(b)
            val gap = ChronoUnit.DAYS.between(a, b)
            for (k in 1..gap) {
                val v = va + (vb - va) * k / gap
                trend += ALPHA * (v - trend)
                out += a.plusDays(k) to trend
            }
        }
        return out
    }

    /**
     * Ritmo semanal (kg/semana) según la pendiente de la tendencia de los últimos [days] días.
     * null si no hay al menos 2 pesadas en ese período.
     */
    fun weeklyRate(weights: List<Pair<LocalDate, Double>>, today: LocalDate = LocalDate.now(), days: Long = 20): Double? {
        val from = today.minusDays(days)
        if (weights.count { !it.first.isBefore(from) } < 2) return null
        val t = daily(weights).filter { !it.first.isBefore(from) }
        if (t.size < 2) return null
        val xs = t.map { ChronoUnit.DAYS.between(from, it.first).toDouble() }
        val ys = t.map { it.second }
        val mx = xs.average(); val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return slope * 7
    }

    /** Peso proyectado a [date] siguiendo el ritmo actual desde la última tendencia. */
    fun projection(lastTrend: Pair<LocalDate, Double>, ratePerWeek: Double, date: LocalDate): Double =
        lastTrend.second + ratePerWeek / 7 * ChronoUnit.DAYS.between(lastTrend.first, date)
}
