package com.uru2904.mtracker.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Resultado del check-in semanal (estilo MacroFactor). */
data class CheckIn(
    val ready: Boolean,
    val completeDays: Int,
    val weighIns: Int,
    val avgIntake: Int?,
    val trendChangeKgPerWeek: Double?,
    val tdee: Int?,
    val proposedKcal: Int?,
    val confidence: String,
)

/**
 * Gasto energético real a partir de lo que comiste y la tendencia de peso.
 *  - Tendencia: media móvil exponencial diaria del peso (alfa 0,1), así el agua y la sal no mueven el número.
 *  - Ventana: los últimos 21 días hasta ayer. Solo cuentan los días marcados como completos.
 *  - Gasto = ingesta promedio − pendiente de la tendencia (kg/día) × 7.700.
 *  - Objetivo = gasto − déficit para el ritmo elegido, con un cambio máximo de ±150 kcal por semana y un piso de 1.900.
 */
object EnergyModel {
    const val KCAL_PER_KG = 7700.0
    const val WINDOW_DAYS = 21L
    const val MIN_COMPLETE_DAYS = 10
    const val MIN_WEIGH_INS = 6
    const val MAX_STEP = 150
    const val FLOOR = 1900

    /** Tendencia por día (desde la primera pesada hasta [until]). */
    fun trend(weights: List<Pair<LocalDate, Double>>, until: LocalDate): Map<LocalDate, Double> {
        if (weights.isEmpty()) return emptyMap()
        val byDay = weights.groupBy({ it.first }, { it.second }).mapValues { it.value.average() }
        val start = byDay.keys.min()
        var t = byDay[start]!!
        val out = LinkedHashMap<LocalDate, Double>()
        var d = start
        while (!d.isAfter(until)) {
            byDay[d]?.let { t += 0.1 * (it - t) }
            out[d] = t
            d = d.plusDays(1)
        }
        return out
    }

    fun checkIn(
        today: LocalDate,
        intakeByCompleteDay: Map<LocalDate, Double>,
        weights: List<Pair<LocalDate, Double>>,
        currentKcal: Int,
        lossKgPerWeek: Double,
    ): CheckIn {
        val end = today.minusDays(1)
        val start = end.minusDays(WINDOW_DAYS - 1)
        val inWindow = { d: LocalDate -> !d.isBefore(start) && !d.isAfter(end) }
        val intake = intakeByCompleteDay.filterKeys(inWindow)
        val weighIns = weights.count { inWindow(it.first) }
        val avg = intake.values.takeIf { it.isNotEmpty() }?.average()

        val tr = trend(weights, end).filterKeys(inWindow)
        // Pendiente por mínimos cuadrados de la tendencia (kg por día)
        val slope = if (tr.size >= 7) {
            val xs = tr.keys.map { ChronoUnit.DAYS.between(start, it).toDouble() }
            val ys = tr.values.toList()
            val mx = xs.average(); val my = ys.average()
            val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
            val den = xs.sumOf { (it - mx) * (it - mx) }
            if (den > 0) num / den else null
        } else null

        val ready = intake.size >= MIN_COMPLETE_DAYS && weighIns >= MIN_WEIGH_INS && avg != null && slope != null
        if (!ready) return CheckIn(false, intake.size, weighIns, avg?.toInt(), slope?.times(7), null, null, "Juntando datos")

        val tdee = avg!! - slope!! * KCAL_PER_KG
        val target = tdee - lossKgPerWeek * KCAL_PER_KG / 7
        val bounded = target.coerceIn((currentKcal - MAX_STEP).toDouble(), (currentKcal + MAX_STEP).toDouble())
        val proposed = (Math.round(maxOf(bounded, FLOOR.toDouble()) / 25.0) * 25).toInt()
        val confidence = if (intake.size >= 16 && weighIns >= 12) "Alta" else "Media"
        return CheckIn(true, intake.size, weighIns, avg.toInt(), slope * 7, tdee.toInt(), proposed, confidence)
    }

    /** Carbohidratos que completan las calorías con proteína y grasas fijas. */
    fun carbsFor(kcal: Int, protein: Int, fat: Int) = ((kcal - protein * 4 - fat * 9) / 4).coerceAtLeast(100)
}
