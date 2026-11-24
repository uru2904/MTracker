package com.uru2904.mtracker.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.uru2904.mtracker.MainActivity
import com.uru2904.mtracker.R
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.AppSettings
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.usage.UsageReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Notificación diaria tipo semáforo: comida, gym, sueño, peso y pantalla. */
object DailyNotifier {
    private const val CHANNEL = "daily"
    private const val NOTIF_ID = 2001
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Programa el próximo aviso (alarma exacta) o lo cancela si está apagado. */
    fun schedule(context: Context) {
        AppSettings.init(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, DailyAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!AppSettings.dailyNotify) return
        val at = nextFire().atZone(zone).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun nextFire(): LocalDateTime = DayLights.nextFire(LocalDateTime.now(), AppSettings.calendar(), AppSettings.notifyOffsetMin)

    /** Arma y manda el aviso ya (desde la alarma o con "Probar ahora"). */
    fun runNow(context: Context, test: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<DailyNotifyWorker>()
            .setInputData(workDataOf("test" to test))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Resumen del día", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Semáforo diario de comida, gym, sueño, peso y pantalla"
        })
    }

    /** Calcula el semáforo del día lógico actual. */
    suspend fun topics(context: Context): List<Topic> {
        val db = AppDatabase.get(context)
        val cal = AppSettings.calendar()
        val now = LocalDateTime.now()
        val day = cal.logicalDate(now)
        val today = LocalDate.now()

        // Comida
        val goal = db.goals().activeNow(day.toString())
        val kcalT = goal?.kcalTarget ?: 2250
        val protT = goal?.proteinTarget ?: 150
        val meals = db.meals().between(day.toString(), day.toString())
        val food = DayLights.food(meals.size, meals.sumOf { it.kcal }, meals.sumOf { it.protein }, kcalT, protT)

        // Gym (Hevy y Health Connect ya viven en la tabla de entrenamientos)
        val sessions = db.workouts().all()
        val dateOf = { ms: Long -> cal.logicalDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)) }
        val rotation = listOf("Full A", "Full B", "Full C")
        val next = sessions.lastOrNull { it.routine in rotation }?.routine?.let { rotation[(rotation.indexOf(it) + 1) % 3] } ?: "Full A"
        val gym = DayLights.gym(sessions.lastOrNull()?.let { ChronoUnit.DAYS.between(dateOf(it.start), day).coerceAtLeast(0) }, next)

        // Sueño: promedio de las últimas 3 noches con datos contra lo esperado de cada turno
        val nights = runCatching {
            withTimeoutOrNull(20_000) { HealthRepository(context).sleepNights(5) }
        }.getOrNull().orEmpty().filter { !it.date.isAfter(today) }.take(3)
        val expected = nights.map { n ->
            val w = cal.expectedSleep(n.date)
            Duration.between(w.start, w.end).toMinutes().let { if (it <= 0) it + 24 * 60 else it }
        }
        val sleep = if (nights.isEmpty()) DayLights.sleep(null, 0)
        else DayLights.sleep(nights.map { it.totalMin }.average().toLong(), expected.average().toLong())

        // Peso
        val lastW = db.weights().all().lastOrNull()
        val weight = DayLights.weight(lastW?.let { ChronoUnit.DAYS.between(dateOf(it.timestamp), day).coerceAtLeast(0) })

        // Pantalla: del día lógico (si ya pasó la medianoche, suma lo de hoy)
        val reader = UsageReader(context)
        val screen = if (!reader.hasPermission()) DayLights.screen(null, AppSettings.screenLimitMin) else {
            val usage = reader.daily(2)
            val min = (usage[day]?.values?.sum() ?: 0) + if (today != day) usage[today]?.values?.sum() ?: 0 else 0
            DayLights.screen(min, AppSettings.screenLimitMin)
        }
        return listOf(food, gym, sleep, weight, screen)
    }

    suspend fun post(context: Context, test: Boolean) {
        channel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        // Trae lo último de la planilla (comidas cargadas por chat) antes de armar el semáforo.
        runCatching {
            withTimeoutOrNull(30_000) {
                com.uru2904.mtracker.sheets.SheetsSync(context, AppDatabase.get(context)).syncSilently(context, HealthRepository(context))
            }
        }
        val m = DayLights.message(topics(context))
        val open = PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).putExtra("home", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (test) m.title + " (prueba)" else m.title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (m.text == m.expanded) m.text else m.text + "\n\n" + m.expanded))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
    }
}

class DailyNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppSettings.init(applicationContext)
        val test = inputData.getBoolean("test", false)
        val day = AppSettings.calendar().logicalDate(LocalDateTime.now()).toString()
        // Un solo aviso por día lógico (salvo la prueba)
        if (!test && AppSettings.lastNotifiedDay == day) return Result.success()
        runCatching { DailyNotifier.post(applicationContext, test) }
        if (!test) AppSettings.lastNotifiedDay = day
        return Result.success()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("work", "Tareas en segundo plano", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(applicationContext, "work").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Armando el resumen del día").build()
        return androidx.work.ForegroundInfo(2002, n)
    }
}

/** Dispara el aviso y programa el del día siguiente. */
class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DailyNotifier.runNow(context)
        DailyNotifier.schedule(context)
    }
}

/** Después de reiniciar el celular o actualizar la app, vuelve a programar el aviso. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DailyNotifier.schedule(context)
}
