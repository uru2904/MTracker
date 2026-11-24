package com.uru2904.mtracker.notify

import com.uru2904.mtracker.domain.ShiftCalendar
import java.time.LocalDateTime

/** Color de cada tema del semáforo. NONE = sin datos (gris). */
enum class Light(val dot: String) { GREEN("🟢"), YELLOW("🟡"), RED("🔴"), NONE("⚪") }

/** Un tema del semáforo con su valor corto (columna gris de la notificación expandida). */
data class Topic(val name: String, val light: Light, val value: String = "")

/** Reglas del semáforo diario (las mismas que usaba la alerta de Claude). */
object DayLights {
    const val MIN_PROTEIN = 110.0
    private val ar = java.util.Locale("es", "AR")

    fun n(v: Number) = String.format(ar, "%,d", v.toLong())
    fun hm(min: Long) = when {
        min < 60 -> "$min min"
        min % 60 == 0L -> "${min / 60} h"
        else -> "${min / 60} h %02d".format(min % 60)
    }
    private fun ago(d: Long) = when (d) { 0L -> "hoy"; 1L -> "hace 1 día"; else -> "hace $d días" }

    /** Comida del día: rojo si no hay nada cargado; amarillo si falta proteína o las kcal se alejan más de 20%. */
    fun food(meals: Int, kcal: Double, protein: Double, kcalTarget: Int, proteinTarget: Int): Topic {
        if (meals == 0) return Topic("Comida", Light.RED, "nada cargado")
        val lowP = protein < MIN_PROTEIN
        val offK = kcal < kcalTarget * 0.8 || kcal > kcalTarget * 1.2
        val light = if (lowP || offK) Light.YELLOW else Light.GREEN
        return Topic("Comida", light, "${n(kcal)} / ${n(kcalTarget)} kcal · ${n(protein)} g P")
    }

    /** Gym: verde hasta 3 días desde la última sesión, amarillo 4–5, rojo 6 o más. */
    fun gym(daysSince: Long?, next: String): Topic = when {
        daysSince == null -> Topic("Gym", Light.NONE, "sin datos")
        daysSince <= 3 -> Topic("Gym", Light.GREEN, ago(daysSince))
        daysSince <= 5 -> Topic("Gym", Light.YELLOW, "${ago(daysSince)} · toca $next")
        else -> Topic("Gym", Light.RED, "${ago(daysSince)} · toca $next")
    }

    /** Sueño: promedio de las últimas noches contra lo esperado del turno. */
    fun sleep(avgMin: Long?, expectedMin: Long): Topic {
        if (avgMin == null) return Topic("Sueño", Light.NONE, "sin datos")
        val missing = expectedMin - avgMin
        val light = when { missing <= 0 -> Light.GREEN; missing <= 60 -> Light.YELLOW; else -> Light.RED }
        return Topic("Sueño", light, "${hm(avgMin)} / ${hm(expectedMin)}")
    }

    /** Peso: verde si te pesaste en los últimos 3 días, amarillo 4–7, rojo 8 o más. */
    fun weight(daysSince: Long?): Topic = when {
        daysSince == null -> Topic("Peso", Light.NONE, "sin pesajes")
        daysSince <= 3 -> Topic("Peso", Light.GREEN, ago(daysSince))
        daysSince <= 7 -> Topic("Peso", Light.YELLOW, ago(daysSince))
        else -> Topic("Peso", Light.RED, ago(daysSince))
    }

    /** Pantalla: verde debajo del 80% del límite, amarillo hasta el límite, rojo si se pasó. */
    fun screen(min: Long?, limit: Long): Topic = when {
        min == null -> Topic("Pantalla", Light.NONE, "sin permiso")
        min < limit * 0.8 -> Topic("Pantalla", Light.GREEN, "${hm(min)} / ${hm(limit)}")
        min <= limit -> Topic("Pantalla", Light.YELLOW, "${hm(min)} / ${hm(limit)}")
        else -> Topic("Pantalla", Light.RED, "${hm(min)} / ${hm(limit)}")
    }

    /** Título y texto corto (la fila de puntos). La vista expandida es la tabla de [topics]. */
    data class Message(val title: String, val text: String, val allGreen: Boolean)

    fun message(topics: List<Topic>): Message =
        if (topics.all { it.light == Light.GREEN })
            Message("Todo en verde hoy 💪", "Comida, gym, sueño, peso y pantalla al día", true)
        else Message("Tu día", topics.joinToString("  ") { "${it.light.dot} ${it.name}" }, false)

    /**
     * Próximo aviso: [offsetMin] minutos antes de que termine el día lógico actual
     * (por defecto 00:00 en TM/TT, con corte a las 02:00, y 05:00 en TN, con corte a las 07:00).
     */
    fun nextFire(now: LocalDateTime, cal: ShiftCalendar, offsetMin: Long): LocalDateTime {
        var d = cal.logicalDate(now)
        while (true) {
            val next = d.plusDays(1)
            val fire = next.atTime(cal.dayCutoffFor(next)).minusMinutes(offsetMin)
            if (fire.isAfter(now)) return fire
            d = next
        }
    }
}
