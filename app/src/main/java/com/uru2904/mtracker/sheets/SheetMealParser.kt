package com.uru2904.mtracker.sheets

import com.uru2904.mtracker.data.MealEntry
import com.uru2904.mtracker.domain.ShiftCalendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Convierte filas de la pestaña Comidas de la planilla en comidas de la app.
 * Columnas: Fecha (yyyy-MM-dd) | Hora (H:mm, puede faltar) | Comida | Descripción |
 * kcal | Proteína | Carbohidratos | Grasas | Confianza.
 * La Fecha de la planilla ya es el día lógico; si la hora es anterior al corte del día
 * (por ejemplo 00:56), el momento real es el día calendario siguiente.
 */
object SheetMealParser {
    fun parse(rows: List<List<String>>, calendar: ShiftCalendar = com.uru2904.mtracker.data.AppSettings.calendar(), zone: ZoneId = ZoneId.systemDefault()): List<MealEntry> =
        rows.mapNotNull { r ->
            val cell = { i: Int -> r.getOrNull(i)?.trim().orEmpty() }
            val date = parseDate(cell(0)) ?: return@mapNotNull null
            val kcal = num(cell(4)) ?: return@mapNotNull null
            val time = parseTime(cell(1))
            val cutoff = calendar.dayCutoffFor(date)
            val realDate = if (time != null && time < cutoff) date.plusDays(1) else date
            val ts = realDate.atTime(time ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
            MealEntry(
                timestamp = ts,
                logicalDate = date.toString(),
                mealType = cell(2).ifBlank { "Comida" },
                description = cell(3),
                kcal = kcal,
                protein = num(cell(5)) ?: 0.0,
                carbs = num(cell(6)) ?: 0.0,
                fat = num(cell(7)) ?: 0.0,
                confidence = cell(8).ifBlank { null },
                source = "planilla",
                remoteId = cell(9).ifBlank { null },
            )
        }

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    /** Acepta 2026-11-14 o 14/11/2026. */
    fun parseDate(s: String): LocalDate? {
        runCatching { return LocalDate.parse(s) }
        val m = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(s) ?: return null
        val (d, mo, y) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }

    private fun parseTime(s: String): LocalTime? {
        val m = Regex("^(\\d{1,2}):(\\d{2})").find(s) ?: return null
        val (h, min) = m.destructured
        return runCatching { LocalTime.of(h.toInt(), min.toInt()) }.getOrNull()
    }

    /** Extrae el ID de un link de Google Sheets (o devuelve el texto si ya es un ID). */
    fun sheetIdFrom(text: String): String? {
        val t = text.trim()
        Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)").find(t)?.let { return it.groupValues[1] }
        return if (Regex("^[a-zA-Z0-9-_]{30,}$").matches(t)) t else null
    }
}
