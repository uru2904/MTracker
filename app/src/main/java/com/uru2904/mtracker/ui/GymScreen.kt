package com.uru2904.mtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
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

private val Green = Ok
private val Yellow = Warn
private val Red = Danger
private val es = Locale("es", "AR")
private const val WEEKLY_TARGET = 3

/** Pantalla Gym (estilo Weights): semáforos y volumen fijos arriba; solo el historial se desplaza. */
@Composable
fun GymScreen(db: AppDatabase, onImport: () -> Unit) {
    val sessions by com.uru2904.mtracker.data.Live.gymSessions(db).collectAsState()
    val stats by com.uru2904.mtracker.data.Live.gymStats(db).collectAsState()
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
            Text("Gym", color = Color.White, fontSize = Type.Title, fontWeight = FontWeight.Bold)
            Text("Importar CSV", color = AccentLight, fontSize = Type.Body, modifier = Modifier.clickable { onImport() }.padding(8.dp))
        }

        // ---- Fijo: semáforos ----
        val last7 = sessions.count { ChronoUnit.DAYS.between(dateOf(it), today) in 0..6 }
        val daysSince = sessions.firstOrNull()?.let { ChronoUnit.DAYS.between(dateOf(it), today).toInt() }
        val rotation = listOf("Full A", "Full B", "Full C")
        val lastFull = sessions.firstOrNull { it.routine in rotation }?.routine
        val next = lastFull?.let { rotation[(rotation.indexOf(it) + 1) % 3] } ?: "Full A"
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Indicator(
                Modifier.weight(1f), "Últimos 7 días", "$last7 / $WEEKLY_TARGET",
                when { last7 >= WEEKLY_TARGET -> Green; last7 == WEEKLY_TARGET - 1 -> Yellow; else -> Red },
            )
            Indicator(
                Modifier.weight(1f), "Desde el último",
                daysSince?.let { if (it == 0) "Hoy" else if (it == 1) "1 día" else "$it días" } ?: "–",
                when { daysSince == null -> Color.White; daysSince <= 2 -> Green; daysSince == 3 -> Yellow; else -> Red },
            )
            Indicator(Modifier.weight(1f), "Próxima rutina", next, Color.White)
        }

        // ---- Fijo: volumen semanal (8 semanas), tocar una barra la selecciona ----
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeks = (7 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
        val volumes = weeks.map { monday ->
            sessions.filter { val d = dateOf(it); !d.isBefore(monday) && d.isBefore(monday.plusWeeks(1)) }
                .sumOf { statsById[it.id]?.volume ?: 0.0 }
        }
        val counts = weeks.map { monday ->
            sessions.count { val d = dateOf(it); !d.isBefore(monday) && d.isBefore(monday.plusWeeks(1)) }
        }
        var selected by remember { mutableStateOf(weeks.size - 1) }
        val countPaint = remember {
            android.graphics.Paint().apply {
                isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
                .background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Volumen semanal", color = Muted, fontSize = Type.Small)
                Text(
                    (if (selected == weeks.size - 1) "Actual" else "Sem. del " + DateTimeFormatter.ofPattern("d/M").format(weeks[selected])) +
                        ": %,d kg".format(es, volumes[selected].toLong()),
                    color = Color.White, fontSize = Type.Small, fontWeight = FontWeight.SemiBold,
                )
            }
            val max = niceMax(volumes.maxOrNull() ?: 0.0)
            Row(Modifier.fillMaxWidth().height(130.dp)) {
                // Eje vertical
                Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    listOf(max, max / 2, 0.0).forEach { Text(kLabel(it), color = Muted, fontSize = Type.Chart) }
                }
                Canvas(
                    Modifier.weight(1f).fillMaxHeight().pointerInput(volumes.size) {
                        detectTapGestures { pos ->
                            val slot = size.width / volumes.size
                            selected = (pos.x / slot).toInt().coerceIn(0, volumes.size - 1)
                        }
                    }
                ) {
                    val n = volumes.size
                    val gap = 10.dp.toPx()
                    val w = (size.width - gap * (n - 1)) / n
                    listOf(0f, 0.5f, 1f).forEach { f ->
                        val y = size.height * f
                        drawLine(Track, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    countPaint.textSize = 11.sp.toPx()
                    volumes.forEachIndexed { i, v ->
                        val h = (v / max * size.height).toFloat().coerceAtLeast(if (v > 0) 4f else 2f)
                        drawRoundRect(
                            color = if (i == selected) AccentLight else Color(0xFF3A3A3A),
                            topLeft = Offset(i * (w + gap), size.height - h),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                        // Cantidad de entrenamientos de la semana, en la base de la barra
                        if (counts[i] > 0) {
                            val inside = h >= 18.dp.toPx()
                            countPaint.color = (if (inside) (if (i == selected) Color.Black else Color.White) else Muted).toArgb()
                            val y = if (inside) size.height - 5.dp.toPx() else size.height - h - 4.dp.toPx()
                            drawContext.canvas.nativeCanvas.drawText("${counts[i]}", i * (w + gap) + w / 2, y, countPaint)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
                weeks.forEachIndexed { i, m ->
                    Text(
                        if (i == weeks.size - 1) "Actual" else DateTimeFormatter.ofPattern("d/M").format(m),
                        color = if (i == selected) Color.White else Muted, fontSize = Type.Chart,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- Se desplaza: historial ----
        Text("Historial", color = Muted, fontSize = Type.Small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
        ) {
            if (sessions.isEmpty()) Text("Todavía no hay sesiones. Importá el CSV de Hevy.", color = Muted, fontSize = Type.Body)
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                sessions.take(100).forEachIndexed { i, w ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
                    val st = statsById[w.id]
                    Row(
                        Modifier.fillMaxWidth().clickable { detail = w }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(w.routine, color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                            Text(
                                DateTimeFormatter.ofPattern("EEE d/M", es).format(dateOf(w)) +
                                    " · ${w.durationMin} min" + (st?.let { " · ${it.exercises} ejercicios" } ?: ""),
                                color = Muted, fontSize = Type.Small,
                            )
                        }
                        Text("%,d kg".format(es, (st?.volume ?: 0.0).toLong()), color = Soft, fontSize = Type.Body)
                    }
                }
            }
        }
    }

    detail?.let { w -> SessionDetail(db, w) { detail = null } }
}

/** Máximo "redondo" para el eje (ej.: 13.851 -> 15.000). */
private fun niceMax(v: Double): Double {
    if (v <= 0) return 1000.0
    val step = when { v <= 5000 -> 1000.0; v <= 20000 -> 2500.0; else -> 5000.0 }
    return Math.ceil(v / step) * step
}

private fun kLabel(v: Double): String = if (v == 0.0) "0" else if (v >= 1000) "${num(Math.round(v / 100) / 10.0)}k" else "${v.toInt()}"

@Composable
private fun Indicator(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier.fillMaxHeight().background(Card, RoundedCornerShape(14.dp)).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        // Estilo Whoop: el color del estado va en el número, sin punto.
        Text(label, color = Muted, fontSize = Type.Small, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(value, color = color, fontSize = Type.Headline, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                    Text(w.routine, color = Color.White, fontSize = Type.Title, fontWeight = FontWeight.Bold)
                    Text(
                        DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", es).format(Instant.ofEpochMilli(w.start).atZone(zone)) +
                            " · ${w.durationMin} min",
                        color = Muted, fontSize = Type.Small,
                    )
                }
                Text("Cerrar", color = AccentLight, fontSize = Type.Body, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            val volume = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
            Text("Volumen total: %,d kg · ${sets.size} series".format(es, volume.toLong()), color = Soft, fontSize = Type.Body)
            // Orden de aparición en la sesión (el CSV no trae el orden de ejercicios; se usa el de carga).
            sets.sortedBy { it.id }.groupBy { it.exercise }.forEach { (exercise, ss) ->
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(exercise, color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                    ss.sortedBy { it.setIndex }.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Serie ${i + 1}", color = Muted, fontSize = Type.Body)
                            Text(
                                (s.weightKg?.let { "${num(it)} kg × " } ?: "Peso corporal × ") + (s.reps ?: 0),
                                color = Color.White, fontSize = Type.Body,
                            )
                        }
                    }
                }
            }
        }
    }
}
