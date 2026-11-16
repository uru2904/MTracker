package com.uru2904.mtracker.domain

/** Minutos por fase de una noche (sumando sueño principal y siestas). */
data class SleepMinutes(val total: Long, val awake: Long, val light: Long, val deep: Long, val rem: Long) {
    val asleep get() = (total - awake).coerceAtLeast(0)
    val hasStages get() = light + deep + rem > 0
}

data class SleepScore(val score: Int, val label: String, val duration: String, val deep: String?, val rem: String?, val awake: String?)

/**
 * Puntaje propio (0-100), inspirado en Samsung Health:
 *  duración dormida 50 pts (7-9 h = completo), profundo 15 (≥13 %), REM 15 (≥20 %), despierto 20 (≤5 % del total).
 * Sin fases, solo cuenta la duración (escalada a 100).
 */
object SleepScorer {
    const val TARGET_MIN = 7 * 60L
    const val TARGET_MAX = 9 * 60L

    fun score(m: SleepMinutes): SleepScore {
        val a = m.asleep
        val dur = when {
            a in TARGET_MIN..TARGET_MAX -> 50.0
            a < TARGET_MIN -> 50.0 * a / TARGET_MIN
            else -> (50.0 - (a - TARGET_MAX) / 6.0).coerceAtLeast(35.0)
        }
        val durLabel = when { a < 6 * 60 -> "Atención"; a < TARGET_MIN -> "Regular"; a <= TARGET_MAX -> "Buena"; else -> "Excesiva" }
        if (!m.hasStages) {
            val s = (dur * 2).toInt().coerceIn(0, 100)
            return SleepScore(s, label(s), durLabel, null, null, null)
        }
        val deepPct = if (a > 0) m.deep * 100.0 / a else 0.0
        val remPct = if (a > 0) m.rem * 100.0 / a else 0.0
        val awakePct = if (m.total > 0) m.awake * 100.0 / m.total else 0.0
        val deep = 15.0 * (deepPct / 13.0).coerceAtMost(1.0)
        val rem = 15.0 * (remPct / 20.0).coerceAtMost(1.0)
        val awake = when { awakePct <= 5 -> 20.0; awakePct >= 25 -> 0.0; else -> 20.0 * (25 - awakePct) / 20 }
        val s = (dur + deep + rem + awake).toInt().coerceIn(0, 100)
        return SleepScore(
            s, label(s), durLabel,
            deep = if (deepPct >= 13) "Buena" else if (deepPct >= 8) "Regular" else "Atención",
            rem = if (remPct >= 20) "Buena" else if (remPct >= 14) "Regular" else "Atención",
            awake = if (awakePct <= 5) "Excelente" else if (awakePct <= 12) "Buena" else "Atención",
        )
    }

    fun label(s: Int) = when { s >= 85 -> "Excelente"; s >= 70 -> "Bueno"; s >= 50 -> "Regular"; else -> "Malo" }
}
