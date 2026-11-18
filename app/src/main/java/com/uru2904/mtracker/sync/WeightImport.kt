package com.uru2904.mtracker.sync

import android.content.Context
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.WeightEntry
import com.uru2904.mtracker.health.HealthRepository

/**
 * Trae a la app los pesos que otras apps (Samsung Health) guardaron en Health Connect.
 * No duplica: saltea los que ya están (±2 min) y los que borraste a mano.
 */
object WeightImport {
    private const val PREFS = "weights"

    fun markDeleted(context: Context, timestamp: Long) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("deleted", emptySet())!!.toMutableSet().apply { add(timestamp.toString()) }
        p.edit().putStringSet("deleted", set).apply()
    }

    fun unmarkDeleted(context: Context, timestamp: Long) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet("deleted", emptySet())!!.toMutableSet().apply { remove(timestamp.toString()) }
        p.edit().putStringSet("deleted", set).apply()
    }

    suspend fun run(context: Context, db: AppDatabase, health: HealthRepository): Int {
        if (!health.isAvailable()) return 0
        val history = runCatching { health.weightHistory() }.getOrDefault(emptyList())
        if (history.isEmpty()) return 0
        val deleted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("deleted", emptySet())!!.mapNotNull { it.toLongOrNull() }
        val existing = db.weights().all().map { it.timestamp }
        fun near(list: List<Long>, t: Long) = list.any { kotlin.math.abs(it - t) <= 120_000 }
        val toAdd = history.filter { (t, _, _) -> !near(existing, t) && !near(deleted, t) }
            .map { (t, kg, pkg) ->
                WeightEntry(timestamp = t, kg = Math.round(kg * 10) / 10.0, source = if (pkg.contains("shealth")) "samsung" else pkg)
            }
        if (toAdd.isNotEmpty()) db.weights().insertAll(toAdd)
        return toAdd.size
    }
}
