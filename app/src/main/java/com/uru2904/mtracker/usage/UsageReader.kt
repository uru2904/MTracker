package com.uru2904.mtracker.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Apps que se miden, con su color en el gráfico. */
enum class TrackedApp(val label: String, val color: Long, val packages: Set<String>) {
    X("X", 0xFFE5E5E5, setOf("com.twitter.android")),
    INSTAGRAM("Instagram", 0xFFE1306C, setOf("com.instagram.android")),
    YOUTUBE("YouTube", 0xFFFF3B30, setOf("com.google.android.youtube", "com.google.android.apps.youtube.music")),
    KICK("Kick", 0xFF53FC18, setOf("com.kick.mobile")),
    TWITCH("Twitch", 0xFF9146FF, setOf("tv.twitch.android.app"));

    companion object {
        /** Por paquete conocido o, si el paquete es otro, por el nombre visible de la app. */
        fun of(pkg: String, label: String?): TrackedApp? =
            entries.firstOrNull { pkg in it.packages } ?: entries.firstOrNull { label != null && label.equals(it.label, true) }
    }
}

/** Uso diario (minutos por app) de este dispositivo, calculado con los eventos de Android. */
class UsageReader(private val context: Context) {
    private val zone = ZoneId.systemDefault()

    fun hasPermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private val labels = HashMap<String, String?>()
    private fun label(pkg: String): String? = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrNull()
    }

    /** Minutos por día y por app de los últimos [days] días (Android guarda el detalle unos 7-10 días). */
    fun daily(days: Int = 7): Map<LocalDate, Map<TrackedApp, Long>> {
        if (!hasPermission()) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = LocalDate.now().minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now)
        val open = HashMap<TrackedApp, Long>()
        val ms = HashMap<Pair<LocalDate, TrackedApp>, Long>()

        fun add(app: TrackedApp, from: Long, to: Long) {
            var a = from
            while (a < to) {
                val day = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val b = minOf(to, dayEnd)
                ms[day to app] = (ms[day to app] ?: 0) + (b - a)
                a = b
            }
        }
        fun closeAll(at: Long) { open.forEach { (app, t) -> add(app, t, at) }; open.clear() }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val app = TrackedApp.of(e.packageName, label(e.packageName))
                    // Cambio a otra app: se cierra lo que estaba abierto.
                    open.keys.filter { it != app }.forEach { add(it, open[it]!!, e.timeStamp); open.remove(it) }
                    if (app != null && app !in open) open[app] = e.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val app = TrackedApp.of(e.packageName, label(e.packageName)) ?: continue
                    open.remove(app)?.let { add(app, it, e.timeStamp) }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> closeAll(e.timeStamp)
            }
        }
        closeAll(now)
        return ms.entries.groupBy({ it.key.first }, { it.key.second to it.value / 60_000 })
            .mapValues { (_, l) -> l.toMap() }
    }
}

/** Celular o tablet: la tablet solo mide y sube su uso. */
object DeviceRole {
    fun isTablet(context: Context): Boolean {
        val p = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        return if (p.contains("tablet")) p.getBoolean("tablet", false)
        else context.resources.configuration.smallestScreenWidthDp >= 600
    }

    fun set(context: Context, tablet: Boolean) =
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().putBoolean("tablet", tablet).apply()

    fun tab(tablet: Boolean) = if (tablet) "Uso tablet" else "Uso celular"
}
