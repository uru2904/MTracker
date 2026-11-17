package com.uru2904.mtracker.domain

/** Minutos por fase de una noche (sumando sueño principal y siestas). */
data class SleepMinutes(val total: Long, val awake: Long, val light: Long, val deep: Long, val rem: Long) {
    val asleep get() = (total - awake).coerceAtLeast(0)
    val hasStages get() = light + deep + rem > 0
}

data class SleepScore(val score: Int, val label: String, val duration: String, val deep: String?, val rem: String?, val awake: String?)

/**
 * Puntaje propio calibrado con Samsung Health (noches del 14/11 = 61 y 16/11 = 84).
 * Cada factor se califica como Samsung y suma según su nivel (Excelente 100 %, Buena 80 %, Regular 55 %, Atención 30 %):
 *  - Tiempo de sueño real (50 pts): ≥7 h Excelente, ≥6 h Buena, ≥5 h Regular.
 *  - Sueño profundo, en minutos (15 pts): ≥50 Excelente, ≥30 Buena, ≥20 Regular.
 *  - Sueño REM, en % del sueño real (15 pts): ≥27 % Excelente, ≥20 % Buena, ≥15 % Regular.
 *  - Despierto, en minutos (20 pts): ≤30 Excelente, ≤45 Buena, ≤60 Regular.
 * Samsung además usa la latencia, que no pasa a Health Connect.
 */
object SleepScorer {
    private fun pts(level: String) = when (level) { "Excelente" -> 1.0; "Buena" -> 0.8; "Regular" -> 0.55; else -> 0.3 }

    fun score(m: SleepMinutes): SleepScore {
        val a = m.asleep
        val dur = when { a >= 7 * 60 -> "Excelente"; a >= 6 * 60 -> "Buena"; a >= 5 * 60 -> "Regular"; else -> "Atención" }
        if (!m.hasStages) {
            val s = (pts(dur) * 100).toInt()
            return SleepScore(s, label(s), dur, null, null, null)
        }
        val deep = when { m.deep >= 50 -> "Excelente"; m.deep >= 30 -> "Buena"; m.deep >= 20 -> "Regular"; else -> "Atención" }
        val remPct = if (a > 0) m.rem * 100.0 / a else 0.0
        val rem = when { remPct >= 27 -> "Excelente"; remPct >= 20 -> "Buena"; remPct >= 15 -> "Regular"; else -> "Atención" }
        val awake = when { m.awake <= 30 -> "Excelente"; m.awake <= 45 -> "Buena"; m.awake <= 60 -> "Regular"; else -> "Atención" }
        val s = (50 * pts(dur) + 15 * pts(deep) + 15 * pts(rem) + 20 * pts(awake)).toInt().coerceIn(0, 100)
        return SleepScore(s, label(s), dur, deep, rem, awake)
    }

    fun label(s: Int) = when { s >= 85 -> "Excelente"; s >= 70 -> "Buena"; s >= 50 -> "Regular"; else -> "Atención" }
}
