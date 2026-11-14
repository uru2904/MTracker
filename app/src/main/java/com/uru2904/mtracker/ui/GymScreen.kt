package com.uru2904.mtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.WorkoutSession
import com.uru2904.mtracker.data.WorkoutSet
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val Green = Color(0xFF22C55E)
private val Yellow = Color(0xFFF2C14E)
private val Red = Color(0xFFF87171)
private val es = Locale("es", "AR")
private const val WEEKLY_TARGET = 3

/** Pantalla Gym (estilo Weights): semáforos, volumen semanal e historial de sesiones con detalle. */
@Composable
fun GymScreen(db: AppDatabase, onImport: () -> Unit) {
    val sessions by db.workouts().allFlow().collectAsState(initial = emptyList())
    val stats by db.workouts().statsFlow().collectAsState(initial = emptyList())
    val statsById = stats.associateBy { it.sessionId }
    var detail by remember { mutableStateOf<WorkoutSession?>(null) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    fun dateOf(w: WorkoutSession) = Instant.ofEpochMilli(w.start).atZone(zone).toLocalDate()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gym", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Importar CSV", color = AccentLight, fontSize = 14.sp, modifier = Modifier.clickable { onImport() }.padding(8.dp))
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Semáforos
            val last7 = sessions.count { ChronoUnit.DAYS.between(dateOf(it), today) in 0..6 }
            val daysSince = sessions.firstOrNull()?.let { ChronoUnit.DAYS.between(dateOf(it), today).toInt() }
            val rotation = listOf("Full A", "Full B", "Full C")
            val lastFull = sessions.firstOrNull { it.routine in rotation }?.routine
            val next = lastFull?.let { rotation[(rotation.indexOf(it) + 1) % 3] } ?: "Full A"
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Indicator(
                    Modifier.weight(1f), "Últimos 7 días", "$last7 / $WEEKLY_TARGET",
                    when { last7 >= WEEKLY_TARGET -> Green; last7 == WEEKLY_TARGET - 1 -> Yellow; else -> Red },
                )
                Indicator(
                    Modifier.weight(1f), "Desde el último",
                    daysSince?.let { if (it == 0) "Hoy" else if (it == 1) "1 día" else "$it días" } ?: "–",
                    when { daysSince == null -> Muted; daysSince <= 2 -> Green; daysSince == 3 -> Yellow; else -> Red },
                )
                Indicator(Modifier.weight(1f), "Próxima", next, AccentLight)
            }

            // Volumen semanal (últimas 8 semanas)
            val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeks = (7 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
            val volumes = weeks.map { monday ->
                sessions.filter { val d = dateOf(it); !d.isBefore(monday) && d.isBefore(monday.plusWeeks(1)) }
                    .sumOf { statsById[it.id]?.volume ?: 0.0 }
            }
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Volumen semanal", color = Muted, fontSize = 13.sp)
                    Text("Esta semana: %,d kg".format(es, volumes.last().toLong()), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                val max = (volumes.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val n = volumes.size
                    val gap = 10.dp.toPx()
                    val w = (size.width - gap * (n - 1)) / n
                    volumes.forEachIndexed { i, v ->
                        val h = (v / max * size.height).toFloat().coerceAtLeast(if (v > 0) 4f else 2f)
                        drawRoundRect(
                            color = if (i == n - 1) AccentLight else Color(0xFF2E2E2E),
                            topLeft = Offset(i * (w + gap), size.height - h),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weeks.forEachIndexed { i, m ->
                        Text(
                            if (i == weeks.size - 1) "Esta" else DateTimeFormatter.ofPattern("d/M").format(m),
                            color = Muted, fontSize = 10.sp,
                        )
                    }
                }
            }

            // Historial
            Text("Historial", color = Muted, fontSize = 13.sp)
            if (sessions.isEmpty()) Text("Todavía no hay sesiones. Importá el CSV de Hevy.", color = Muted, fontSize = 14.sp)
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                sessions.take(60).forEachIndexed { i, w ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Color(0xFF1F1F1F)))
                    val st = statsById[w.id]
                    Row(
                        Modifier.fillMaxWidth().clickable { detail = w }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(w.routine, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                DateTimeFormatter.ofPattern("EEE d/M", es).format(dateOf(w)) +
                                    " · ${w.durationMin} min" + (st?.let { " · ${it.exercises} ejercicios" } ?: ""),
                                color = Muted, fontSize = 12.sp,
                            )
                        }
                        Text("%,d kg".format(es, (st?.volume ?: 0.0).toLong()), color = Soft, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    detail?.let { w -> SessionDetail(db, w) { detail = null } }
}

@Composable
private fun Indicator(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier.background(Card, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Detalle de una sesión: cada ejercicio con sus series (peso × repeticiones). */
@Composable
private fun SessionDetail(db: AppDatabase, w: WorkoutSession, onClose: () -> Unit) {
    var sets by remember { mutableStateOf<List<WorkoutSet>>(emptyList()) }
    LaunchedEffect(w.id) { sets = db.workouts().setsOf(w.id) }
    val zone = ZoneId.systemDefault()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(w.routine, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", es).format(Instant.ofEpochMilli(w.start).atZone(zone)) +
                            " · ${w.durationMin} min",
                        color = Muted, fontSize = 13.sp,
                    )
                }
                Text("Cerrar", color = AccentLight, fontSize = 15.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            val volume = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
            Text("Volumen total: %,d kg · ${sets.size} series".format(es, volume.toLong()), color = Soft, fontSize = 14.sp)
            // Orden de aparición en la sesión (el CSV no trae el orden de ejercicios; se usa el de carga).
            sets.sortedBy { it.id }.groupBy { it.exercise }.forEach { (exercise, ss) ->
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(exercise, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    ss.sortedBy { it.setIndex }.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Serie ${i + 1}", color = Muted, fontSize = 14.sp)
                            Text(
                                (s.weightKg?.let { "${num(it)} kg × " } ?: "Peso corporal × ") + (s.reps ?: 0),
                                color = Color.White, fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
