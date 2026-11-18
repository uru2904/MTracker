package com.uru2904.mtracker.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.uru2904.mtracker.data.DayStatus
import com.uru2904.mtracker.data.MealEntry
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.GoalPeriod
import com.uru2904.mtracker.domain.CheckIn
import com.uru2904.mtracker.domain.EnergyModel
import com.uru2904.mtracker.sheets.AuthStep
import com.uru2904.mtracker.sheets.SheetsSync
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Ritmo de bajada elegido (kg por semana). */
const val LOSS_KG_PER_WEEK = 0.4

private fun prefs(c: Context) = c.getSharedPreferences("checkin", Context.MODE_PRIVATE)

/** Toca check-in los lunes (o si pasaron 7 días o más desde el último). */
fun checkInDue(c: Context, today: LocalDate = LocalDate.now()): Boolean {
    val last = prefs(c).getString("last", null)?.let { LocalDate.parse(it) }
    return when {
        last == null -> today.dayOfWeek == DayOfWeek.MONDAY
        last == today -> false
        else -> today.dayOfWeek == DayOfWeek.MONDAY || ChronoUnit.DAYS.between(last, today) >= 7
    }
}

/**
 * Días a revisar en el check-in: los nuevos desde el check-in anterior hasta ayer.
 * El primero arranca en el primer día con comidas. Nunca más de 21 días (la ventana del cálculo).
 */
fun reviewRange(last: LocalDate?, firstMeal: LocalDate?, today: LocalDate): List<LocalDate> {
    val to = today.minusDays(1)
    val earliest = to.minusDays(EnergyModel.WINDOW_DAYS.toLong() - 1)
    var from = last ?: firstMeal ?: to.minusDays(6)
    if (from.isBefore(earliest)) from = earliest
    if (from.isAfter(to)) from = to.minusDays(6)
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}

/** Un día de la revisión: comidas cargadas y si ya estaba marcado completo. */
data class ReviewDay(val date: LocalDate, val meals: List<MealEntry>, val complete: Boolean) {
    val kcal get() = meals.sumOf { it.kcal }
    val protein get() = meals.sumOf { it.protein }
}

private suspend fun loadReview(c: Context, db: AppDatabase): List<ReviewDay> {
    val last = prefs(c).getString("last", null)?.let { LocalDate.parse(it) }
    val first = db.meals().firstDate()?.let { LocalDate.parse(it) }
    val days = reviewRange(last, first, LocalDate.now())
    if (days.isEmpty()) return emptyList()
    val meals = db.meals().between(days.first().toString(), days.last().toString()).groupBy { it.logicalDate }
    val status = db.days().all().associate { it.date to it.complete }
    return days.map { ReviewDay(it, meals[it.toString()].orEmpty(), status[it.toString()] == true) }
}

/** Calcula el check-in con los datos de la app. */
suspend fun computeCheckIn(db: AppDatabase, goal: GoalPeriod?): CheckIn {
    val zone = ZoneId.systemDefault()
    val complete = db.days().all().filter { it.complete }.map { it.date }.toSet()
    val intake = db.meals().kcalByDay().filter { it.logicalDate in complete }
        .associate { LocalDate.parse(it.logicalDate) to it.kcal }
    val weights = db.weights().all().map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() to it.kg }
    return EnergyModel.checkIn(LocalDate.now(), intake, weights, goal?.kcalTarget ?: 2250, LOSS_KG_PER_WEEK)
}

/** Check-in semanal (estilo MacroFactor), en Inicio: primero confirmás los días completos y después ves el ajuste. */
@Composable
fun CheckInCard(db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(checkInDue(context)) }
    if (!visible) return
    var review by remember { mutableStateOf<List<ReviewDay>?>(null) }
    val checked = remember { mutableStateMapOf<LocalDate, Boolean>() }
    var goal by remember { mutableStateOf<GoalPeriod?>(null) }
    var result by remember { mutableStateOf<CheckIn?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        goal = db.goals().activeNow(LocalDate.now().toString())
        val initial = loadReview(context, db)
        initial.forEach { checked[it.date] = it.complete }
        review = initial
        // Trae lo cargado por chat y lo marcado en la planilla antes de revisar (sin mostrar nada si falla).
        runCatching {
            val sync = SheetsSync(context, db)
            (sync.authorize(context) as? AuthStep.Token)?.let { sync.sync(it.token); sync.syncDays(it.token) }
        }
        val fresh = loadReview(context, db)
        fresh.forEach { d -> if (initial.firstOrNull { it.date == d.date }?.complete != d.complete) checked[d.date] = d.complete }
        review = fresh
    }
    val g = goal

    fun syncDays() = scope.launch {
        runCatching {
            val sync = SheetsSync(context, db)
            (sync.authorize(context) as? AuthStep.Token)?.let { sync.syncDays(it.token) }
        }
    }

    fun confirmDays() {
        val list = review ?: return
        saving = true
        scope.launch {
            db.days().upsertAll(list.map { DayStatus(it.date.toString(), checked[it.date] == true, pending = true) })
            syncDays()
            result = computeCheckIn(db, goal)
            saving = false
        }
    }

    fun close(decision: String, newKcal: Int?) {
        val r = result ?: return
        prefs(context).edit().putString("last", LocalDate.now().toString()).apply()
        visible = false
        scope.launch {
            if (newKcal != null && g != null) {
                db.goals().update(g.copy(kcalTarget = newKcal, carbsTarget = EnergyModel.carbsFor(newKcal, g.proteinTarget, g.fatTarget)))
            }
            runCatching {
                val sync = SheetsSync(context, db)
                (sync.authorize(context) as? AuthStep.Token)?.let {
                    sync.logCheckIn(it.token, listOf(
                        LocalDate.now().toString(), r.completeDays, r.avgIntake ?: "", r.trendChangeKgPerWeek?.let { v -> Math.round(v * 100) / 100.0 } ?: "",
                        r.tdee ?: "", g?.kcalTarget ?: "", newKcal ?: g?.kcalTarget ?: "", decision,
                    ))
                }
            }
        }
    }

    val days = review ?: return
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Check-in semanal", color = AccentLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val r = result
        if (r == null) {
            ReviewStep(days, checked, saving, onConfirm = ::confirmDays)
        } else {
            ResultStep(r, g, onBack = { result = null }, close = ::close)
        }
    }
}

private val es = java.util.Locale("es", "AR")
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** Paso 1: lista de días (plegados) con sus comidas para confirmar cuáles están completos. */
@Composable
private fun ReviewStep(days: List<ReviewDay>, checked: MutableMap<LocalDate, Boolean>, saving: Boolean, onConfirm: () -> Unit) {
    val expanded = remember { mutableStateMapOf<LocalDate, Boolean>() }
    Text("Confirmá qué días cargaste completos", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("Solo esos entran en el cálculo de tu gasto. Tocá un día para ver sus comidas.", color = Soft, fontSize = 13.sp)
    days.forEach { d ->
        val open = expanded[d.date] == true
        Column(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded[d.date] = !open }.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked[d.date] == true, onCheckedChange = { checked[d.date] = it },
                    colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Muted, checkmarkColor = Color.White),
                )
                val name = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, es).replaceFirstChar { it.uppercase() }.trimEnd('.')
                Text("$name ${d.date.dayOfMonth}/${d.date.monthValue}", color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (d.meals.isEmpty()) "Sin comidas" else "%,d kcal · %d g P".format(es, d.kcal.toInt(), d.protein.toInt()),
                    color = if (d.meals.isEmpty()) Muted else Soft, fontSize = 13.sp,
                )
                Text(if (open) "  ▾" else "  ▸", color = Muted, fontSize = 13.sp)
            }
            if (open) {
                Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (d.meals.isEmpty()) Text("No hay comidas cargadas este día.", color = Muted, fontSize = 13.sp)
                    d.meals.forEach { m ->
                        val time = if (m.timestamp > 0) Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault()).format(hhmm) else ""
                        Row {
                            Text("$time  ${m.mealType} · ${m.description}", color = Soft, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("  ${m.kcal.toInt()}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    val n = days.count { checked[it.date] == true }
    Button(onClick = onConfirm, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = Accent),
        modifier = Modifier.fillMaxWidth()) {
        Text(if (saving) "Calculando…" else "Confirmar ($n de ${days.size} completos)", color = Color.White)
    }
}

/** Paso 2: resultado del cálculo y decisión. */
@Composable
private fun ResultStep(r: CheckIn, g: GoalPeriod?, onBack: () -> Unit, close: (String, Int?) -> Unit) {
    if (!r.ready) {
        Text("Todavía faltan datos para calcular tu gasto real", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Días completos: ${r.completeDays} de ${EnergyModel.MIN_COMPLETE_DAYS} · Pesadas: ${r.weighIns} de ${EnergyModel.MIN_WEIGH_INS} (últimos 21 días). " +
                "Seguí cargando todo y pesándote: el objetivo queda como está.",
            color = Soft, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier.weight(1f)) { Text("Revisar días", color = Color.White) }
            Button(onClick = { close("Faltan datos", null) }, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(1f)) { Text("Entendido", color = Color.White) }
        }
        return
    }
    val change = r.trendChangeKgPerWeek ?: 0.0
    Text(
        "Tu tendencia ${if (change <= 0) "bajó" else "subió"} ${num(Math.abs(Math.round(change * 100) / 100.0))} kg por semana",
        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
    )
    Text("Comiste en promedio %,d kcal · gasto estimado %,d kcal (confianza %s) · %d días completos".format(es, r.avgIntake, r.tdee, r.confidence.lowercase(), r.completeDays),
        color = Soft, fontSize = 13.sp)
    val current = g?.kcalTarget ?: 2250
    val proposed = r.proposedKcal ?: current
    Text(
        if (proposed == current) "Tu objetivo sigue en %,d kcal para bajar ${num(LOSS_KG_PER_WEEK)} kg por semana.".format(es, current)
        else "Objetivo nuevo: %,d kcal (antes %,d) para bajar ${num(LOSS_KG_PER_WEEK)} kg por semana.".format(es, proposed, current),
        color = Color.White, fontSize = 14.sp,
    )
    Text("‹ Revisar días", color = AccentLight, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { close("Mantener", null) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
            modifier = Modifier.weight(1f)) { Text("Mantener", color = Color.White) }
        Button(onClick = { close("Aceptado", proposed) }, colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.weight(1f)) { Text("Aceptar", color = Color.White) }
    }
}
