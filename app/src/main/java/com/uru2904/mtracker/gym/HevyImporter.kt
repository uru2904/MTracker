package com.uru2904.mtracker.gym

import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.WorkoutSession
import com.uru2904.mtracker.data.WorkoutSet

data class ImportResult(val added: Int, val skipped: Int)

/** Guarda en la base las sesiones del CSV que todavía no estaban (por fecha de inicio). */
class HevyImporter(private val db: AppDatabase) {
    suspend fun import(text: String): ImportResult {
        val sessions = HevyCsvParser.parse(text)
        var added = 0
        var skipped = 0
        for (s in sessions) {
            val start = s.startMillis()
            if (db.workouts().countAt(start, "hevy") > 0) { skipped++; continue }
            db.workouts().insertWithSets(
                WorkoutSession(start = start, routine = s.title, durationMin = s.durationMin, source = "hevy"),
                s.sets.map { WorkoutSet(sessionId = 0, exercise = it.exercise, setIndex = it.setIndex, weightKg = it.weightKg, reps = it.reps) },
            )
            added++
        }
        return ImportResult(added, skipped)
    }
}
