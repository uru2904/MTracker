package com.uru2904.mtracker.ui

import android.content.Context
import androidx.compose.foundation.background
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

/** Calcula el check-in con los datos de la app. */
suspend fun computeCheckIn(db: AppDatabase, goal: GoalPeriod?): CheckIn {
    val zone = ZoneId.systemDefault()
    val complete = db.days().all().filter { it.complete }.map { it.date }.toSet()
    val intake = db.meals().kcalByDay().filter { it.logicalDate in complete }
        .associate { LocalDate.parse(it.logicalDate) to it.kcal }
    val weights = db.weights().all().map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() to it.kg }
    return EnergyModel.checkIn(LocalDate.now(), intake, weights, goal?.kcalTarget ?: 2250, LOSS_KG_PER_WEEK)
}

/** Tarjeta del check-in semanal (estilo MacroFactor), en Inicio. */
@Composable
fun CheckInCard(db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(checkInDue(context)) }
    if (!visible) return
    var goal by remember { mutableStateOf<GoalPeriod?>(null) }
    var result by remember { mutableStateOf<CheckIn?>(null) }
    LaunchedEffect(Unit) {
        goal = db.goals().activeNow(LocalDate.now().toString())
        result = computeCheckIn(db, goal)
    }
    val r = result ?: return
    val g = goal

    fun close(decision: String, newKcal: Int?) {
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

    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Check-in semanal", color = AccentLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (!r.ready) {
            Text("Todavía faltan datos para calcular tu gasto real", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Días completos: ${r.completeDays} de ${EnergyModel.MIN_COMPLETE_DAYS} · Pesadas: ${r.weighIns} de ${EnergyModel.MIN_WEIGH_INS} (últimos 21 días). " +
                    "Marcá \"Día completo\" en Comidas cuando cargaste todo lo del día.",
                color = Soft, fontSize = 13.sp,
            )
            Button(onClick = { close("Faltan datos", null) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier.fillMaxWidth()) { Text("Entendido", color = Color.White) }
            return@Column
        }
        val change = r.trendChangeKgPerWeek ?: 0.0
        Text(
            "Tu tendencia ${if (change <= 0) "bajó" else "subió"} ${num(Math.abs(Math.round(change * 100) / 100.0))} kg por semana",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Text("Comiste en promedio %,d kcal · gasto estimado %,d kcal (confianza %s)".format(java.util.Locale("es", "AR"), r.avgIntake, r.tdee, r.confidence.lowercase()),
            color = Soft, fontSize = 13.sp)
        val current = g?.kcalTarget ?: 2250
        val proposed = r.proposedKcal ?: current
        Text(
            if (proposed == current) "Tu objetivo sigue en %,d kcal para bajar ${num(LOSS_KG_PER_WEEK)} kg por semana.".format(java.util.Locale("es", "AR"), current)
            else "Objetivo nuevo: %,d kcal (antes %,d) para bajar ${num(LOSS_KG_PER_WEEK)} kg por semana.".format(java.util.Locale("es", "AR"), proposed, current),
            color = Color.White, fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { close("Mantener", null) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier.weight(1f)) { Text("Mantener", color = Color.White) }
            Button(onClick = { close("Aceptado", proposed) }, colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(1f)) { Text("Aceptar", color = Color.White) }
        }
    }
}
