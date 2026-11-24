package com.uru2904.mtracker.notify

import com.uru2904.mtracker.domain.ShiftCalendar
import java.time.LocalDateTime

/** Color de cada tema del semáforo. NONE = sin datos (gris). */
enum class Light(val dot: String) { GREEN("🟢"), YELLOW("🟡"), RED("🔴"), NONE("⚪") }

/** Un tema del semáforo y, si no está en verde, la línea que se ve al expandir la notificación. */
data class Topic(val name: String, val light: Light, val detail: String = "")

/** Reglas del semáforo diario (las mismas que usaba la alerta de Claude). */
object DayLights {
    const val MIN_PROTEIN = 110.0

    fun hm(min: Long) = if (min < 60) "$min min" else "${min / 60} h" + if (min % 60 > 0) " ${min % 60}" else ""

    /** Comida del día: rojo si no hay nada cargado; amarillo si falta proteína o las kcal se alejan más de 20%. */
    fun food(meals: Int, kcal: Double, protein: Double, kcalTarget: Int, proteinTarget: Int): Topic {
        if (meals == 0) return Topic("Comida", Light.RED, "Comida: nada cargado hoy")
        val lowP = protein < MIN_PROTEIN
        val offK = kcal < kcalTarget * 0.8 || kcal > kcalTarget * 1.2
        if (!lowP && !offK) return Topic("Comida", Light.GREEN)
        val parts = buildList {
            if (lowP) add("proteína ${protein.toInt()} de $proteinTarget g (scoop + Leche Protein ≈ 46 g)")
            if (offK) add("${kcal.toInt()} de $kcalTarget kcal")
        }
        return Topic("Comida", Light.YELLOW, "Comida: " + parts.joinToString(", "))
    }

    /** Gym: verde hasta 3 días desde la última sesión, amarillo 4–5, rojo 6 o más. */
    fun gym(daysSince: Long?, next: String): Topic = when {
        daysSince == null -> Topic("Gym", Light.NONE, "Gym: sin datos")
        daysSince <= 3 -> Topic("Gym", Light.GREEN)
        daysSince <= 5 -> Topic("Gym", Light.YELLOW, "Gym: $daysSince días sin ir, toca $next")
        else -> Topic("Gym", Light.RED, "Gym: $daysSince días sin ir, toca $next")
    }

    /** Sueño: promedio de las últimas noches contra lo esperado del turno. */
    fun sleep(avgMin: Long?, expectedMin: Long): Topic {
        if (avgMin == null) return Topic("Sueño", Light.NONE, "Sueño: sin datos del reloj")
        val missing = expectedMin - avgMin
        val text = "Sueño: promedio ${hm(avgMin)} de ${hm(expectedMin)} esperadas"
        return when {
            missing <= 0 -> Topic("Sueño", Light.GREEN)
            missing <= 60 -> Topic("Sueño", Light.YELLOW, text)
            else -> Topic("Sueño", Light.RED, text)
        }
    }

    /** Peso: verde si te pesaste en los últimos 3 días, amarillo 4–7, rojo 8 o más. */
    fun weight(daysSince: Long?): Topic = when {
        daysSince == null -> Topic("Peso", Light.NONE, "Peso: sin pesajes")
        daysSince <= 3 -> Topic("Peso", Light.GREEN)
        daysSince <= 7 -> Topic("Peso", Light.YELLOW, "Peso: $daysSince días sin pesarte")
        else -> Topic("Peso", Light.RED, "Peso: $daysSince días sin pesarte")
    }

    /** Pantalla: verde debajo del 80% del límite, amarillo hasta el límite, rojo si se pasó. */
    fun screen(min: Long?, limit: Long): Topic = when {
        min == null -> Topic("Pantalla", Light.NONE, "Pantalla: falta el permiso de uso")
        min < limit * 0.8 -> Topic("Pantalla", Light.GREEN)
        min <= limit -> Topic("Pantalla", Light.YELLOW, "Pantalla: ${hm(min)} de ${hm(limit)}")
        else -> Topic("Pantalla", Light.RED, "Pantalla: ${hm(min)} de ${hm(limit)}")
    }

    /** Título, texto corto y texto expandido de la notificación. */
    data class Message(val title: String, val text: String, val expanded: String)

    fun message(topics: List<Topic>): Message {
        if (topics.all { it.light == Light.GREEN })
            return Message("Todo en verde hoy 💪", "Comida, gym, sueño, peso y pantalla al día", "Comida, gym, sueño, peso y pantalla al día")
        val dots = topics.joinToString("  ") { "${it.light.dot} ${it.name}" }
        val order = listOf(Light.RED, Light.YELLOW, Light.NONE)
        val details = topics.filter { it.light != Light.GREEN }.sortedBy { order.indexOf(it.light) }
            .joinToString("\n") { "${it.light.dot} ${it.detail}" }
        return Message("Tu día", dots, details)
    }

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
