package com.uru2904.mtracker.ui

import androidx.compose.material3.minimumInteractiveComponentSize
import android.app.Activity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.MeasurementEntry
import com.uru2904.mtracker.data.MeasurementType
import com.uru2904.mtracker.data.WeightEntry
import com.uru2904.mtracker.health.Composition
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.sheets.SheetsSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Después de pesarse (estilo Renpho): checklist con
 *  1. Peso ✓ (y tendencia de 30 días, estilo Happy Scale)
 *  2. Composición corporal con el reloj: la app la espera en Health Connect y se completa sola
 *  3. Medidas con cinta, solo si pasaron 14 días o más desde la última
 */
@Composable
fun WeighInDialog(db: AppDatabase, weight: WeightEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val health = remember { HealthRepository(context) }
    val sync = remember { SheetsSync(context, db) }
    val recent by com.uru2904.mtracker.data.Live.weightsSince(db, com.uru2904.mtracker.data.Live.daysAgo(30)).collectAsState()
    val goal by com.uru2904.mtracker.data.Live.goal(db, LocalDate.now().toString()).collectAsState()

    // Composición: se busca en Health Connect cada 5 s durante 10 minutos.
    var composition by remember { mutableStateOf<Composition?>(null) }
    var compSkipped by remember { mutableStateOf(false) }
    var compMissingPerm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val since = Instant.ofEpochMilli(weight.timestamp).minus(30, ChronoUnit.MINUTES)
        repeat(120) {
            if (composition != null || compSkipped) return@LaunchedEffect
            val granted = runCatching { health.grantedPermissions() }.getOrDefault(emptySet())
            compMissingPerm = granted.none { it.endsWith("READ_BODY_FAT") }
            composition = runCatching { health.compositionSince(since) }.getOrNull()
            delay(5_000)
        }
    }

    // Medidas: tocan si pasaron 14 días o más.
    var measuresDue by remember { mutableStateOf<Boolean?>(null) }
    var lastDate by remember { mutableStateOf<String?>(null) }
    var types by remember { mutableStateOf<List<MeasurementType>>(emptyList()) }
    val values = remember { mutableStateMapOf<Long, String>() }
    var measuresSaved by remember { mutableStateOf(false) }
    var measuresSkipped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        lastDate = db.measurements().lastDate()
        types = db.measurements().allTypes().filter { it.active }
        val days = lastDate?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now()) }
        measuresDue = days == null || days >= 14
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Peso + tendencia
            val prevAvg = recent.filter { it.timestamp < weight.timestamp - 6L * 86_400_000 }.takeLast(7).map { it.kg }.average()
            val target = goal?.targetWeightKg
            Text("Pesada guardada", color = Muted, fontSize = Type.Small)
            Text("${num(weight.kg)} kg", color = Color.White, fontSize = Type.Hero, fontWeight = FontWeight.Bold)
            val notes = buildList {
                if (!prevAvg.isNaN()) add("%+.1f kg vs. la semana pasada".format(java.util.Locale("es", "AR"), weight.kg - prevAvg))
                if (target != null) add(if (weight.kg > target) "faltan ${num(Math.round((weight.kg - target) * 10) / 10.0)} kg para ${num(target)}" else "objetivo cumplido")
            }
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = Soft, fontSize = Type.Body)
            if (recent.size >= 3) TrendChart(recent.map { it.kg }, target)

            // 1. Peso
            Step(done = true, title = "Peso", detail = "Guardado en la app y en Health Connect")

            // 2. Composición con el reloj
            val comp = composition
            when {
                comp != null -> Step(
                    done = true, title = "Composición corporal",
                    detail = listOfNotNull(
                        comp.bodyFatPct?.let { "Grasa ${num(Math.round(it * 10) / 10.0)} %" },
                        comp.leanKg?.let { "Masa magra ${num(Math.round(it * 10) / 10.0)} kg" },
                    ).joinToString(" · "),
                )
                compSkipped -> Step(done = false, title = "Composición corporal", detail = "Salteada esta vez")
                else -> StepPending(
                    title = "Medí tu composición con el reloj",
                    detail = if (compMissingPerm)
                        "Falta el permiso de composición en Health Connect: andá a Inicio y tocá \"Conectar Health Connect\"."
                    else
                        "En el reloj: Samsung Health → Composición corporal → Medir. Poné los dedos medio y anular en los dos botones y quedate quieto unos 15 segundos. Cuando Samsung lo sincronice, aparece acá solo.",
                    waiting = !compMissingPerm,
                    onSkip = { compSkipped = true },
                )
            }

            // 3. Medidas (cada 14 días)
            when (measuresDue) {
                null -> {}
                false -> Step(done = true, title = "Medidas", detail = "Al día (última: ${lastDate ?: "-"}). Te las pido cada 14 días.")
                true -> when {
                    measuresSaved -> Step(done = true, title = "Medidas", detail = "Guardadas")
                    measuresSkipped -> Step(done = false, title = "Medidas", detail = "Salteadas: te las pido la próxima vez que te peses")
                    else -> Column(
                        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Tocan las medidas con cinta", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                        Text(
                            (lastDate?.let { "Última: $it. " } ?: "") + "Cinta firme sin apretar, relajado, a la misma altura que la vez anterior. Tocá ? para ver cómo medir cada una.",
                            color = Muted, fontSize = Type.Small,
                        )
                        types.forEach { t -> MeasureField(t, values[t.id] ?: "", { values[t.id] = it }, Modifier.fillMaxWidth()) }
                        val filled = values.mapNotNull { (id, v) -> parseCm(v)?.let { id to it } }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { measuresSkipped = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                                modifier = Modifier.weight(1f),
                            ) { Text("Ahora no", color = Color.White) }
                            Button(
                                onClick = {
                                    val today = LocalDate.now().toString()
                                    scope.launch {
                                        val ok = runCatching {
                                            db.measurements().insertAll(filled.map { (typeId, cm) -> MeasurementEntry(typeId = typeId, date = today, cm = cm) })
                                        }
                                        if (ok.isSuccess) measuresSaved = true
                                        else snack.say("No se pudieron guardar: ${ok.exceptionOrNull()?.message}")
                                    }
                                    // La planilla se actualiza por detrás, sin trabar la pantalla.
                                    val app = context.applicationContext
                                    AppScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { sync.syncSilently(app, health) } }
                                },
                                enabled = filled.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Track),
                                modifier = Modifier.weight(1f),
                            ) { Text("Guardar", color = if (filled.isNotEmpty()) Color.White else Muted) }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    (context as? Activity)?.let { a -> scope.launch { sync.syncSilently(a, health) } }
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Listo", color = Color.White, fontSize = Type.Large) }
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null,
            tint = if (done) AccentLight else Muted, modifier = Modifier.size(24.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = Type.Small)
        }
    }
}

@Composable
private fun StepPending(title: String, detail: String, waiting: Boolean, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (waiting) CircularProgressIndicator(color = AccentLight, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Icon(Icons.Outlined.Circle, contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp))
            Text(title, color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
        }
        Text(detail, color = Muted, fontSize = Type.Small)
        Text("Ahora no", color = AccentLight, fontSize = Type.Body, modifier = Modifier.minimumInteractiveComponentSize().clickable { onSkip() }.padding(vertical = 4.dp))
    }
}

/** Puntos grises = pesadas; línea azul = media móvil de 7 pesadas; línea punteada = objetivo. */
@Composable
private fun TrendChart(kgs: List<Double>, target: Double?) {
    val avg = kgs.indices.map { i -> kgs.subList(maxOf(0, i - 6), i + 1).average() }
    val all = kgs + listOfNotNull(target)
    val min = all.minOrNull()!! - 0.3
    val max = all.maxOrNull()!! + 0.3
    Canvas(Modifier.fillMaxWidth().height(110.dp).background(Card, RoundedCornerShape(14.dp)).padding(12.dp)) {
        fun x(i: Int) = if (kgs.size == 1) size.width / 2 else i * size.width / (kgs.size - 1)
        fun y(v: Double) = (size.height * (1 - (v - min) / (max - min))).toFloat()
        target?.let {
            val ty = y(it)
            var xx = 0f
            while (xx < size.width) { drawLine(Color(0xFF444444), Offset(xx, ty), Offset(xx + 6f, ty), 2f); xx += 12f }
        }
        kgs.forEachIndexed { i, v -> drawCircle(Color(0xFF555555), 3.5f, Offset(x(i), y(v))) }
        val path = Path()
        avg.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, AccentLight, style = Stroke(width = 4f))
    }
}
