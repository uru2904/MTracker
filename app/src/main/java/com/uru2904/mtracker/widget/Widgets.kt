package com.uru2904.mtracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uru2904.mtracker.MainActivity
import com.uru2904.mtracker.R
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.AppSettings
import com.uru2904.mtracker.notify.DailyNotifier
import com.uru2904.mtracker.notify.DayLights
import com.uru2904.mtracker.notify.Light
import com.uru2904.mtracker.notify.Topic
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** Todo lo que muestran los widgets, calculado una sola vez. */
data class WidgetData(
    val kcal: Double, val kcalT: Int, val prot: Double, val protT: Int,
    val carbs: Double, val carbsT: Int, val fat: Double, val fatT: Int,
    val nextRoutine: String, val gymDaysSince: Long?, val weekGym: List<Boolean>, val weekTarget: Int,
    val goalName: String?, val daysLeft: Long?, val goalStart: LocalDate?, val goalEnd: LocalDate?, val weight: Double?, val weightDelta: Double?, val targetWeight: Double?,
    val topics: List<Topic>,
)

object WidgetUpdater {
    const val ACCENT = 0xFF4F6FE0.toInt()
    const val TRACK = 0xFF262626.toInt()
    private val providers = listOf(TuDiaWidget::class.java, SemaforoWidget::class.java, MacrosWidget::class.java, GymWidget::class.java, RioWidget::class.java)

    /** Actualiza todos los widgets en unos segundos (los cambios seguidos se juntan). */
    fun request(c: Context, delaySec: Long = 3) {
        val ctx = c.applicationContext
        if (!hasAny(ctx)) return
        val req = OneTimeWorkRequestBuilder<WidgetWorker>().setInitialDelay(delaySec, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("widgets", ExistingWorkPolicy.REPLACE, req)
    }

    private fun hasAny(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return providers.any { m.getAppWidgetIds(ComponentName(c, it)).isNotEmpty() }
    }

    suspend fun load(c: Context): WidgetData {
        AppSettings.init(c)
        val db = AppDatabase.get(c)
        val cal = AppSettings.calendar()
        val zone = ZoneId.systemDefault()
        val day = cal.logicalDate(LocalDateTime.now())
        val goal = db.goals().activeNow(day.toString())
        val meals = db.meals().between(day.toString(), day.toString())
        val sessions = db.workouts().all()
        val dateOf = { ms: Long -> cal.logicalDate(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)) }
        val rotation = listOf("Full A", "Full B", "Full C")
        val next = sessions.lastOrNull { it.routine in rotation }?.routine?.let { rotation[(rotation.indexOf(it) + 1) % 3] } ?: "Full A"
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val gymDates = sessions.map { dateOf(it.start) }.toSet()
        val weights = db.weights().all()
        val now = System.currentTimeMillis()
        val day7 = TimeUnit.DAYS.toMillis(7)
        val last7 = weights.filter { it.timestamp > now - day7 }.map { it.kg }
        val prev7 = weights.filter { it.timestamp in (now - 2 * day7)..(now - day7) }.map { it.kg }
        val delta = if (last7.isNotEmpty() && prev7.isNotEmpty()) last7.average() - prev7.average() else null
        val topics = runCatching { DailyNotifier.topics(c) }.getOrDefault(emptyList())
        return WidgetData(
            kcal = meals.sumOf { it.kcal }, kcalT = goal?.kcalTarget ?: 2250,
            prot = meals.sumOf { it.protein }, protT = goal?.proteinTarget ?: 150,
            carbs = meals.sumOf { it.carbs }, carbsT = goal?.carbsTarget ?: 265,
            fat = meals.sumOf { it.fat }, fatT = goal?.fatTarget ?: 65,
            nextRoutine = next,
            gymDaysSince = sessions.lastOrNull()?.let { ChronoUnit.DAYS.between(dateOf(it.start), day).coerceAtLeast(0) },
            weekGym = (0..6).map { monday.plusDays(it.toLong()) in gymDates }, weekTarget = 3,
            goalName = goal?.name, daysLeft = goal?.endDate?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it)) }.getOrNull() },
            goalStart = goal?.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            goalEnd = goal?.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            weight = weights.lastOrNull()?.kg, weightDelta = delta, targetWeight = goal?.targetWeightKg,
            topics = topics,
        )
    }

    suspend fun updateAll(c: Context) {
        if (!hasAny(c)) return
        val d = load(c)
        val m = AppWidgetManager.getInstance(c)
        fun each(cls: Class<*>, build: () -> RemoteViews) =
            m.getAppWidgetIds(ComponentName(c, cls)).forEach { m.updateAppWidget(it, build()) }
        each(TuDiaWidget::class.java) { tuDia(c, d) }
        each(SemaforoWidget::class.java) { semaforo(c, d) }
        each(MacrosWidget::class.java) { macros(c, d) }
        each(GymWidget::class.java) { gym(c, d) }
        each(RioWidget::class.java) { rio(c, d) }
    }

    // ---------- Navegación ----------

    private fun open(c: Context, req: Int, tab: String, addMeal: Boolean = false): PendingIntent =
        PendingIntent.getActivity(c, req,
            Intent(c, MainActivity::class.java).putExtra("tab", tab).putExtra("addMeal", addMeal)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // ---------- Formato ----------

    private fun n(v: Number) = DayLights.n(v)
    private fun kg(v: Double) = String.format(java.util.Locale("es", "AR"), "%.1f", v)
    private fun pct(v: Double, t: Int) = if (t <= 0) 0 else (v / t * 100).toInt().coerceIn(0, 100)
    private fun dotRes(l: Light) = when (l) {
        Light.GREEN -> R.drawable.dot_green; Light.YELLOW -> R.drawable.dot_yellow
        Light.RED -> R.drawable.dot_red; Light.NONE -> R.drawable.dot_gray
    }
    private fun topic(d: WidgetData, name: String) = d.topics.firstOrNull { it.name == name }

    /** Dato corto debajo de cada tema del semáforo. */
    private fun short(t: Topic, d: WidgetData): String = when (t.name) {
        "Comida" -> "${n(d.prot)}/${d.protT} g"
        "Gym" -> when (val s = d.gymDaysSince) { null -> "sin datos"; 0L -> "hoy"; 1L -> "ayer"; else -> "hace $s d" }
        "Peso" -> t.value.replace(" días", " d").replace(" día", " d")
        else -> t.value.substringBefore(" /")
    }

    /** Anillo de progreso dibujado como imagen (los widgets no dibujan formas propias). */
    private fun ring(fraction: Float, sizePx: Int = 320, stroke: Float = 28f): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        val r = RectF(stroke, stroke, sizePx - stroke, sizePx - stroke)
        p.color = TRACK; cv.drawArc(r, 0f, 360f, false, p)
        p.color = ACCENT; cv.drawArc(r, -90f, 360f * fraction.coerceIn(0f, 1f), false, p)
        return b
    }

    // ---------- Widgets ----------

    private fun tuDia(c: Context, d: WidgetData) = RemoteViews(c.packageName, R.layout.widget_tu_dia).apply {
        setTextViewText(R.id.countdown, d.daysLeft?.takeIf { it >= 0 }?.let { "${d.goalName ?: "Objetivo"} en $it días" } ?: "")
        setTextViewText(R.id.kcal_value, "${n(d.kcal)} / ${n(d.kcalT)}")
        setProgressBar(R.id.kcal_bar, 100, pct(d.kcal, d.kcalT), false)
        setTextViewText(R.id.prot_value, "${n(d.prot)} / ${d.protT} g")
        setProgressBar(R.id.prot_bar, 100, pct(d.prot, d.protT), false)
        setTextViewText(R.id.carbs_value, "${n(d.carbs)} / ${d.carbsT} g")
        setProgressBar(R.id.carbs_bar, 100, pct(d.carbs, d.carbsT), false)
        setTextViewText(R.id.fat_value, "${n(d.fat)} / ${d.fatT} g")
        setProgressBar(R.id.fat_bar, 100, pct(d.fat, d.fatT), false)
        val info = listOfNotNull(
            topic(d, "Sueño")?.value?.takeIf { it != "sin datos" }?.let { "Sueño ${it.substringBefore(" /")}" },
            if ((d.gymDaysSince ?: 99) == 0L) "Gym: hoy" else "Gym: toca ${d.nextRoutine}",
            topic(d, "Pantalla")?.value?.takeIf { it != "sin permiso" }?.let { "Pantalla ${it.substringBefore(" /")}" },
        )
        setTextViewText(R.id.info, info.joinToString(" · "))
        setOnClickPendingIntent(R.id.root, open(c, 10, "Hoy"))
        setOnClickPendingIntent(R.id.add_meal, open(c, 11, "Comidas", addMeal = true))
    }

    private fun semaforo(c: Context, d: WidgetData) = RemoteViews(c.packageName, R.layout.widget_semaforo).apply {
        val dots = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4)
        val names = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4)
        val vals = intArrayOf(R.id.val0, R.id.val1, R.id.val2, R.id.val3, R.id.val4)
        d.topics.take(5).forEachIndexed { i, t ->
            setImageViewResource(dots[i], dotRes(t.light))
            setTextViewText(names[i], if (t.name == "Comida") "Proteínas" else t.name)
            setTextViewText(vals[i], short(t, d))
        }
        setOnClickPendingIntent(R.id.root, open(c, 20, "Hoy"))
    }

    private fun macros(c: Context, d: WidgetData) = RemoteViews(c.packageName, R.layout.widget_macros).apply {
        setImageViewBitmap(R.id.ring, ring((d.kcal / d.kcalT).toFloat()))
        val left = d.kcalT - d.kcal
        setTextViewText(R.id.ring_value, n(kotlin.math.abs(left)))
        setTextViewText(R.id.ring_label, if (left >= 0) "kcal quedan" else "kcal de más")
        setTextViewText(R.id.prot, "${n(d.prot)}/${d.protT}")
        setTextViewText(R.id.carbs, "${pct(d.carbs, d.carbsT)}%")
        setTextViewText(R.id.fat, "${pct(d.fat, d.fatT)}%")
        setOnClickPendingIntent(R.id.root, open(c, 30, "Comidas"))
    }

    private fun gym(c: Context, d: WidgetData) = RemoteViews(c.packageName, R.layout.widget_gym).apply {
        setTextViewText(R.id.next, d.nextRoutine)
        setTextViewText(R.id.last, when (val s = d.gymDaysSince) { null -> "Sin entrenamientos"; 0L -> "Última: hoy"; 1L -> "Última: hace 1 día"; else -> "Última: hace $s días" })
        val dots = intArrayOf(R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6)
        d.weekGym.forEachIndexed { i, did -> setImageViewResource(dots[i], if (did) R.drawable.dot_accent else R.drawable.dot_track) }
        setTextViewText(R.id.week, "${d.weekGym.count { it }} / ${d.weekTarget} esta semana")
        setOnClickPendingIntent(R.id.root, open(c, 40, "Gym"))
    }

    private fun rio(c: Context, d: WidgetData) = RemoteViews(c.packageName, R.layout.widget_rio).apply {
        setTextViewText(R.id.goal, d.goalName ?: "Sin objetivo activo")
        setTextViewText(R.id.days, d.daysLeft?.takeIf { it >= 0 }?.toString() ?: "–")
        setTextViewText(R.id.weight, d.weight?.let { kg(it) + " kg" } ?: "Sin pesajes")
        val dl = d.weightDelta
        setTextViewText(R.id.delta, when { dl == null -> ""; dl <= -0.05 -> "↓" + kg(-dl); dl >= 0.05 -> "↑" + kg(dl); else -> "=" })
        setTextColor(R.id.delta, if (dl != null && dl <= -0.05) 0xFF22C55E.toInt() else if (dl != null && dl >= 0.05) 0xFFF2C14E.toInt() else 0xFF8A8A8A.toInt())
        setTextViewText(R.id.target, d.targetWeight?.let { "meta ${kg(it)} kg" } ?: "")
        val st = d.goalStart; val en = d.goalEnd
        if (st != null && en != null && en.isAfter(st)) {
            val total = ChronoUnit.DAYS.between(st, en).toDouble()
            val done = ChronoUnit.DAYS.between(st, LocalDate.now()).coerceIn(0, total.toLong())
            setProgressBar(R.id.period_bar, 100, (done / total * 100).toInt(), false)
            val weeks = Math.ceil(total / 7).toInt()
            setTextViewText(R.id.period, "Semana ${(done / 7 + 1).coerceAtMost(weeks.toLong())} de $weeks")
        } else { setProgressBar(R.id.period_bar, 100, 0, false); setTextViewText(R.id.period, "") }
        setOnClickPendingIntent(R.id.root, open(c, 50, "Cuerpo"))
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { WidgetUpdater.updateAll(applicationContext) }
        return Result.success()
    }
}

/** Base: al agregar un widget o cada 30 minutos, actualiza todos. */
abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = WidgetUpdater.request(context, 0)
}
class TuDiaWidget : BaseWidget()
class SemaforoWidget : BaseWidget()
class MacrosWidget : BaseWidget()
class GymWidget : BaseWidget()
class RioWidget : BaseWidget()
