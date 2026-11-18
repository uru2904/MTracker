package com.uru2904.mtracker.data

import android.content.Context
import android.content.SharedPreferences
import com.uru2904.mtracker.domain.Shift
import com.uru2904.mtracker.domain.ShiftCalendar
import com.uru2904.mtracker.domain.ShiftConfig
import com.uru2904.mtracker.domain.SleepWindow
import java.time.LocalDate
import java.time.LocalTime

/**
 * Ajustes del usuario que no están en la base: ritmo de bajada, límite de pantalla y turnos.
 * Si no se inicializó (por ejemplo, en los tests), devuelve los valores por defecto.
 * Los objetivos de nutrición y el período (Río) viven en la tabla goal_period.
 */
object AppSettings {
    private var prefs: SharedPreferences? = null

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs?.edit()?.apply(block)?.apply() }

    var lossKgPerWeek: Double
        get() = prefs?.getFloat("loss", 0.4f)?.toDouble()?.let { Math.round(it * 100) / 100.0 } ?: 0.4
        set(v) = edit { putFloat("loss", v.toFloat()) }

    var screenLimitMin: Long
        get() = prefs?.getLong("screenLimit", 180L) ?: 180L
        set(v) = edit { putLong("screenLimit", v) }

    private fun window(s: String?): SleepWindow? = s?.split("-")?.takeIf { it.size == 2 }?.let {
        runCatching { SleepWindow(LocalTime.parse(it[0]), LocalTime.parse(it[1])) }.getOrNull()
    }
    private fun SleepWindow.str() = "$start-$end"

    var shifts: ShiftConfig
        get() {
            val p = prefs ?: return ShiftConfig()
            val d = ShiftConfig()
            return ShiftConfig(
                anchorMonday = p.getString("anchorMonday", null)?.let { LocalDate.parse(it) } ?: d.anchorMonday,
                anchorShift = p.getString("anchorShift", null)?.let { Shift.valueOf(it) } ?: d.anchorShift,
                rotation = p.getString("rotation", null)?.split(",")?.map { Shift.valueOf(it) } ?: d.rotation,
                sleep = Shift.values().associateWith { s -> window(p.getString("sleep.$s", null)) ?: d.sleep.getValue(s) },
                cutoffNight = p.getString("cutoffNight", null)?.let { LocalTime.parse(it) } ?: d.cutoffNight,
                cutoffOther = p.getString("cutoffOther", null)?.let { LocalTime.parse(it) } ?: d.cutoffOther,
            )
        }
        set(c) = edit {
            putString("anchorMonday", c.anchorMonday.toString())
            putString("anchorShift", c.anchorShift.name)
            putString("rotation", c.rotation.joinToString(",") { it.name })
            remove("hours.TM"); remove("hours.TT"); remove("hours.TN")
            c.sleep.forEach { (s, w) -> putString("sleep.$s", w.str()) }
            putString("cutoffNight", c.cutoffNight.toString())
            putString("cutoffOther", c.cutoffOther.toString())
        }

    fun calendar() = ShiftCalendar(shifts)
}
