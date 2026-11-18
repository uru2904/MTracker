package com.uru2904.mtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.records.SleepSessionRecord
import com.uru2904.mtracker.domain.ShiftCalendar
import com.uru2904.mtracker.domain.SleepScorer
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.health.SleepNight
import com.uru2904.mtracker.health.SleepVitals
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale("es", "AR")
private val CAwake = Color(0xFFF472B6)
private val CRem = Color(0xFFA5B4FC)
private val CLight = Color(0xFF6366F1)
private val CDeep = Color(0xFF3730A3)
private val Good = Ok
private val Bad = Danger

private fun hm(min: Long) = if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"

private fun labelColor(l: String?) = when (l) { "Excelente", "Buena" -> Good; "Regular" -> Warn; null -> Muted; else -> Bad }

/** Pantalla Sueño (estilo AutoSleep + detalle tipo Samsung Health). */
@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val health = remember { HealthRepository(context) }
    val calendar = remember { com.uru2904.mtracker.data.AppSettings.calendar() }
    @Suppress("UNCHECKED_CAST")
    var nights by remember { mutableStateOf(com.uru2904.mtracker.data.UiCache.sleepNights as? List<SleepNight>) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(nights?.firstOrNull()?.date) }
    var vitals by remember { mutableStateOf<SleepVitals?>(null) }
    var pulling by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    suspend fun load() {
        try {
            nights = health.sleepNights(30)
            com.uru2904.mtracker.data.UiCache.sleepNights = nights
            if (selected == null || nights!!.none { it.date == selected }) selected = nights!!.firstOrNull()?.date
            error = null
        } catch (e: Exception) { error = "No pude leer el sueño de Health Connect: ${e.message}" }
    }
    LaunchedEffect(Unit) { load() }

    val list = nights.orEmpty()
    val night = list.firstOrNull { it.date == selected }
    LaunchedEffect(night?.date) {
        vitals = null
        night?.let { n -> vitals = runCatching { health.sleepVitals(n.main.start, n.main.end) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Encabezado fijo: título y noche seleccionada
        ScreenHeader("Sueño")
        val sel = selected
        if (sel != null && list.isNotEmpty()) {
            DateSelector(sel, min = list.last().date, max = list.first().date, onChange = { selected = it })
        }

        RefreshBox(refreshing = pulling, onRefresh = { scope.launch { pulling = true; load(); pulling = false } }) {
            Column(
                Modifier.fillMaxSize()
                    .swipeDays(
                        onPrev = { selected?.let { d -> if (list.isNotEmpty() && d > list.last().date) selected = d.minusDays(1) } },
                        onNext = { selected?.let { d -> if (list.isNotEmpty() && d < list.first().date) selected = d.plusDays(1) } },
                    )
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    error != null -> InfoCard(error!!)
                    nights == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Soft, strokeWidth = 2.dp)
                    }
                    list.isEmpty() -> EmptyState("Sin datos de sueño en los últimos 30 días. Revisá que el reloj sincronice con Samsung Health.")
                    night == null -> EmptyState("No hay sueño registrado esa noche.")
                    else -> {
                        val m = night.minutes
                        val sc = SleepScorer.score(m)
                        val tf = DateTimeFormatter.ofPattern("HH:mm")
                        fun t(i: Instant) = tf.format(i.atZone(zone))

                        // ---- Puntaje y resumen ----
                        Column(
                            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(18.dp)).padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${sc.score}", color = Color.White, fontSize = Type.Hero, fontWeight = FontWeight.Bold)
                                Text(sc.label, color = labelColor(sc.label), fontSize = Type.Headline, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                            }
                            Text("Puntaje MTracker (propio, no es el de Samsung)", color = Muted, fontSize = Type.Chart)
                            Text("${hm(m.total)} · horas de sueño", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                            if (m.hasStages) Text("${hm(m.asleep)} · tiempo de sueño real", color = Soft, fontSize = Type.Body)
                            night.blocks.forEach { b ->
                                Text(
                                    "${t(b.start)} – ${t(b.end)}" + if (b === night.main) "" else " · siesta",
                                    color = Muted, fontSize = Type.Small,
                                )
                            }
                            val win = calendar.expectedSleep(night.date)
                            Text(
                                "Ventana esperada (turno ${calendar.shiftForWeekOf(night.date).name}): ${win.start}–${win.end}",
                                color = Muted, fontSize = Type.Small,
                            )
                        }

                        // ---- Factores ----
                        if (m.hasStages) {
                            Text("Factores del puntaje", color = Muted, fontSize = Type.Small)
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Factor(Modifier.weight(1f), "Tiempo de sueño", hm(m.asleep), sc.duration)
                                Factor(Modifier.weight(1f), "Sueño profundo", hm(m.deep), sc.deep)
                            }
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Factor(Modifier.weight(1f), "Sueño REM", hm(m.rem), sc.rem)
                                Factor(Modifier.weight(1f), "Despierto", hm(m.awake), sc.awake)
                            }

                            // ---- Fases ----
                            Column(
                                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("Fases del sueño principal", color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                                Hypnogram(night.main)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(t(night.main.start), color = Muted, fontSize = Type.Chart)
                                    Text(t(night.main.end), color = Muted, fontSize = Type.Chart)
                                }
                                val tot = m.total.coerceAtLeast(1)
                                listOf(Triple("Despierto", m.awake, CAwake), Triple("REM", m.rem, CRem),
                                    Triple("Ligero", m.light, CLight), Triple("Profundo", m.deep, CDeep)).forEach { (name, v, c) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("$name ${v * 100 / tot} %", color = c, fontSize = Type.Small)
                                            Text(hm(v), color = Soft, fontSize = Type.Small)
                                        }
                                        Box(Modifier.fillMaxWidth().height(10.dp).background(Track, RoundedCornerShape(5.dp))) {
                                            Box(Modifier.fillMaxWidth((v.toFloat() / tot).coerceIn(0f, 1f)).fillMaxHeight().background(c, RoundedCornerShape(5.dp)))
                                        }
                                    }
                                }
                            }
                        }

                        // ---- Signos durante el sueño ----
                        val v = vitals
                        if (v != null && (v.hrAvg != null || v.spo2Avg != null || v.rrAvg != null)) {
                            Column(
                                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Durante el sueño", color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                                v.hrAvg?.let { Vital("Ritmo cardíaco", "$it ppm" + (v.hrMin?.let { mn -> " (mín. $mn)" } ?: "")) }
                                v.spo2Avg?.let { Vital("Oxígeno en sangre", "${Math.round(it)} %" + (v.spo2Min?.let { mn -> " (mín. ${Math.round(mn)} %)" } ?: "")) }
                                v.rrAvg?.let { Vital("Frecuencia respiratoria", "${num(Math.round(it * 10) / 10.0)} resp/min") }
                            }
                        }

                        // ---- 14 días con rango objetivo sombreado ----
                        Column(
                            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val days = (13 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
                            val byDate = list.associateBy { it.date }
                            val withData = days.mapNotNull { byDate[it] }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Últimos 14 días", color = Muted, fontSize = Type.Small)
                                if (withData.isNotEmpty()) Text(
                                    "Promedio: " + hm(withData.sumOf { it.totalMin } / withData.size),
                                    color = Color.White, fontSize = Type.Small, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            SleepBars(days, byDate, selected) { d -> if (byDate[d] != null) selected = d }
                            Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
                                days.forEachIndexed { i, d ->
                                    Text(
                                        if (i % 2 == 1 || i == days.lastIndex) "${d.dayOfMonth}" else "",
                                        color = if (d == selected) Color.White else Muted, fontSize = Type.Chart,
                                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Legend(AccentLight, "Principal"); Legend(Color(0xFF4B5E9E), "Siesta"); Legend(OkBg, "Objetivo 7–9 h")
                            }
                        }

                        // ---- Historial ----
                        Text("Historial", color = Muted, fontSize = Type.Small)
                        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                            list.forEachIndexed { i, n ->
                                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
                                val s = SleepScorer.score(n.minutes)
                                Row(
                                    Modifier.fillMaxWidth().clickable { selected = n.date }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(DateTimeFormatter.ofPattern("EEE d/M", es).format(n.date),
                                            color = if (n.date == selected) AccentLight else Color.White, fontSize = Type.Body)
                                        Text(hm(n.totalMin) + if (n.napMin > 0) " · siesta ${num(Math.round(n.napMin / 6.0) / 10.0)} h" else "",
                                            color = Muted, fontSize = Type.Small)
                                    }
                                    Text("${s.score}", color = labelColor(s.label), fontSize = Type.Large, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Factor(modifier: Modifier, title: String, value: String, label: String?) {
    Column(
        modifier.fillMaxHeight().border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Muted, fontSize = Type.Small)
        Text(value, color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.Bold)
        label?.let { Text(it, color = labelColor(it), fontSize = Type.Small, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Vital(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Soft, fontSize = Type.Body)
        Text(value, color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Legend(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, RoundedCornerShape(2.dp)))
        Text(text, color = Muted, fontSize = Type.Chart, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Hipnograma estilo Samsung: Despierto arriba, Profundo abajo. */
@Composable
private fun Hypnogram(b: com.uru2904.mtracker.health.SleepBlock) {
    val levels = mapOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE to (0 to CAwake), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to (0 to CAwake),
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to (0 to CAwake), SleepSessionRecord.STAGE_TYPE_REM to (1 to CRem),
        SleepSessionRecord.STAGE_TYPE_LIGHT to (2 to CLight), SleepSessionRecord.STAGE_TYPE_SLEEPING to (2 to CLight),
        SleepSessionRecord.STAGE_TYPE_DEEP to (3 to CDeep),
    )
    val span = Duration.between(b.start, b.end).toMillis().coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val rowH = size.height / 4
        b.stages.forEach { s ->
            val (lvl, c) = levels[s.type] ?: return@forEach
            val x0 = Duration.between(b.start, s.start).toMillis().toFloat() / span * size.width
            val x1 = Duration.between(b.start, s.end).toMillis().toFloat() / span * size.width
            drawRoundRect(c, Offset(x0, lvl * rowH + rowH * 0.15f), Size((x1 - x0).coerceAtLeast(2f), rowH * 0.7f), CornerRadius(4f, 4f))
        }
    }
}

/** Barras de horas por día (principal + siesta apilada) con el rango objetivo 7–9 h sombreado. Tocar selecciona la noche. */
@Composable
private fun SleepBars(days: List<LocalDate>, byDate: Map<LocalDate, SleepNight>, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val maxH = 12f
    Row(Modifier.fillMaxWidth().height(140.dp)) {
        Column(Modifier.fillMaxHeight().width(28.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            Text("12h", color = Muted, fontSize = Type.Chart)
            Text("6h", color = Muted, fontSize = Type.Chart)
            Text("0", color = Muted, fontSize = Type.Chart)
        }
        Canvas(
            Modifier.weight(1f).fillMaxHeight().pointerInput(days) {
                detectTapGestures { pos -> onSelect(days[(pos.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.lastIndex)]) }
            }
        ) {
            fun y(h: Float) = size.height * (1 - h / maxH)
            // Rango objetivo
            drawRect(OkBg, Offset(0f, y(9f)), Size(size.width, y(7f) - y(9f)))
            drawLine(OkBg, Offset(0f, y(9f)), Offset(size.width, y(9f)), 1f)
            drawLine(OkBg, Offset(0f, y(7f)), Offset(size.width, y(7f)), 1f)
            val n = days.size
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            days.forEachIndexed { i, d ->
                val night = byDate[d] ?: return@forEachIndexed
                val main = (night.main.minutes / 60f).coerceAtMost(maxH)
                val nap = (night.napMin / 60f).coerceAtMost(maxH - main)
                val x = i * (w + gap)
                val sel = d == selected
                drawRoundRect(if (sel) AccentLight else Color(0xFF6B7FC4), Offset(x, y(main)), Size(w, size.height - y(main)), CornerRadius(4f, 4f))
                if (nap > 0) drawRoundRect(if (sel) Color(0xFF7C8FD6) else Color(0xFF4B5E9E), Offset(x, y(main + nap)), Size(w, y(main) - y(main + nap)), CornerRadius(4f, 4f))
            }
        }
    }
}
