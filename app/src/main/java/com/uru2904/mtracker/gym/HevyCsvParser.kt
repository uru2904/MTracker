package com.uru2904.mtracker.gym

import java.time.LocalDateTime
import java.time.ZoneId

/** Una serie del CSV de Hevy. */
data class HevySet(
    val exercise: String,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
)

/** Una sesión del CSV de Hevy, con sus series. */
data class HevySession(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val sets: List<HevySet>,
) {
    fun startMillis(zone: ZoneId = ZoneId.systemDefault()): Long = start.atZone(zone).toInstant().toEpochMilli()
    val durationMin: Int get() = java.time.Duration.between(start, end).toMinutes().toInt()
}

/**
 * Lee el CSV que exporta Hevy ("Exportar datos" en la app).
 * Columnas: title, start_time, end_time, description, exercise_title, superset_id,
 * exercise_notes, set_index, set_type, weight_kg, reps, distance_km, duration_seconds, rpe.
 * Fechas como "12 nov 2026, 11:45" (meses en español o inglés).
 */
object HevyCsvParser {

    private val months = mapOf(
        "ene" to 1, "jan" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4,
        "may" to 5, "jun" to 6, "jul" to 7, "ago" to 8, "aug" to 8,
        "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dic" to 12, "dec" to 12,
    )

    fun parse(text: String): List<HevySession> {
        val rows = parseCsv(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name).also {
            require(it >= 0) { "El archivo no parece un CSV de Hevy (falta la columna $name)" }
        }
        val iTitle = col("title"); val iStart = col("start_time"); val iEnd = col("end_time")
        val iEx = col("exercise_title"); val iIdx = col("set_index")
        val iW = col("weight_kg"); val iReps = col("reps")

        return rows.drop(1)
            .filter { it.size > maxOf(iTitle, iStart, iEnd, iEx, iIdx, iW, iReps) }
            .groupBy { Triple(it[iTitle], it[iStart], it[iEnd]) }
            .map { (key, setRows) ->
                HevySession(
                    title = key.first.trim(),
                    start = parseDate(key.second),
                    end = parseDate(key.third),
                    sets = setRows.map {
                        HevySet(
                            exercise = it[iEx].trim(),
                            setIndex = it[iIdx].trim().toIntOrNull() ?: 0,
                            weightKg = it[iW].trim().replace(',', '.').toDoubleOrNull(),
                            reps = it[iReps].trim().toIntOrNull(),
                        )
                    },
                )
            }
            .sortedBy { it.start }
    }

    /** "12 nov 2026, 11:45" -> LocalDateTime */
    fun parseDate(s: String): LocalDateTime {
        val parts = s.replace(",", " ").trim().split(Regex("\\s+"))
        require(parts.size >= 4) { "Fecha no reconocida: $s" }
        val day = parts[0].toInt()
        val month = months[parts[1].lowercase().trimEnd('.')] ?: error("Mes no reconocido: $s")
        val year = parts[2].toInt()
        val (h, m) = parts[3].split(":").map { it.toInt() }
        return LocalDateTime.of(year, month, day, h, m)
    }

    /** CSV con comillas dobles (incluye comas y comillas escapadas dentro de campos). */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}
