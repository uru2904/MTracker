package com.uru2904.mtracker.ui

import androidx.compose.material3.minimumInteractiveComponentSize
import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uru2904.mtracker.data.MeasurementEntry
import com.uru2904.mtracker.data.AppSettings
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import com.uru2904.mtracker.data.MeasurementType
import com.uru2904.mtracker.sheets.SheetsSync
import java.time.LocalDate
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uru2904.mtracker.ble.ScaleReading
import com.uru2904.mtracker.ble.ScaleScanner
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.WeightEntry
import com.uru2904.mtracker.health.HealthRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pesaje con la Mi Smart Scale 2: la app escucha los anuncios Bluetooth de la balanza,
 * muestra el peso en vivo y lo guarda solo cuando se estabiliza (en la app y en Health Connect).
 * Pantalla de prueba de la fase 0; la pantalla Cuerpo definitiva se diseña después.
 */
@Composable
fun ScaleScreen(db: AppDatabase) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val scanner = remember { ScaleScanner(context) }
    val health = remember { HealthRepository(context) }
    var live by remember { mutableStateOf<ScaleReading?>(null) }
    var status by remember { mutableStateOf("Subite a la balanza") }
    var scanning by remember { mutableStateOf(false) }
    val raw = remember { mutableStateListOf<String>() }
    var showRaw by remember { mutableStateOf(false) }
    var weighed by remember { mutableStateOf<WeightEntry?>(null) }
    var weighing by remember { mutableStateOf(false) } // hoja "Pesarse" abierta
    val since = remember { com.uru2904.mtracker.data.Live.daysAgo(30) }
    val recent by com.uru2904.mtracker.data.Live.weightsSince(db, since).collectAsState()

    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // Bloqueo: después de guardar una pesada no se ofrece otra por 3 horas (evita lecturas accidentales).
    val prefs = remember { context.getSharedPreferences("scale", android.content.Context.MODE_PRIVATE) }
    var lockUntil by remember { mutableStateOf(prefs.getLong("lock_until", 0L)) }
    var pending by remember { mutableStateOf<ScaleReading?>(null) }
    var dismissedKg by remember { mutableStateOf<Double?>(null) }
    var toDelete by remember { mutableStateOf<WeightEntry?>(null) }
    var detail by remember { mutableStateOf<WeightEntry?>(null) }
    // % de grasa medido con el reloj cerca de cada pesada (de 30 min antes a 2 h después).
    var fatByWeight by remember { mutableStateOf(com.uru2904.mtracker.data.UiCache.fatByWeight) }
    LaunchedEffect(recent) {
        val fats = runCatching { health.bodyFatSince(Instant.ofEpochMilli(since)) }.getOrDefault(emptyList())
        fatByWeight = recent.mapNotNull { w ->
            val t = w.timestamp
            fats.filter { it.first in (t - 30 * 60_000)..(t + 2 * 3_600_000) }.maxByOrNull { it.first }?.let { w.id to it.second }
        }.toMap()
        com.uru2904.mtracker.data.UiCache.fatByWeight = fatByWeight
    }
    val locked = System.currentTimeMillis() < lockUntil

    /** Lectura estable: no se guarda sola, se ofrece para confirmar. */
    fun offer(r: ScaleReading) {
        if (r.kg < 20) return // toque con la mano u objeto: se ignora
        if (System.currentTimeMillis() < lockUntil) return
        if (dismissedKg == r.kg) return
        pending = r
    }

    fun confirm(r: ScaleReading) {
        val now = System.currentTimeMillis()
        pending = null
        lockUntil = now + 3 * 3_600_000L
        prefs.edit().putLong("lock_until", lockUntil).apply()
        scope.launch {
            val entry = WeightEntry(timestamp = now, kg = r.kg, source = "balanza")
            db.weights().insert(entry)
            val hc = runCatching { health.writeWeight(r.kg, Instant.ofEpochMilli(now)) }.isSuccess
            status = "Guardado: ${num(r.kg)} kg" + if (hc) " (también en Health Connect)" else ""
            weighing = false
            weighed = entry // abre el checklist: composición y medidas
        }
    }

    var signals by remember { mutableStateOf(0) }
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(scanning) { while (scanning) { kotlinx.coroutines.delay(500); dots = dots % 3 + 1 } }
    var scanError by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        if (!scanner.bluetoothOn) { status = "Prendé el Bluetooth del celular"; scanning = false; return }
        scanError = null
        val err = scanner.start(
            onReading = { r ->
                live = r
                when {
                    r.removed -> status = "Te bajaste de la balanza"
                    r.stabilized -> offer(r)
                    else -> status = "Midiendo…"
                }
            },
            onRaw = { line -> if (raw.size > 30) raw.removeAt(0); raw.add(line) },
            onAny = { signals++ },
            onError = { msg -> scanError = msg; scanning = false },
        )
        scanError = err
        scanning = err == null
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) startScan() else status = "Sin permiso de dispositivos cercanos no puedo leer la balanza"
    }

    // La balanza se escucha solo con la hoja "Pesarse" abierta.
    LaunchedEffect(weighing) {
        if (weighing) launcher.launch(perms) else { scanner.stop(); scanning = false; live = null; pending = null }
    }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    // ---- Datos de la pantalla Cuerpo ----
    val chartSince = 0L // todo el historial: el rango lo eligen los botones
    LaunchedEffect(Unit) { runCatching { com.uru2904.mtracker.sync.WeightImport.run(context, db, health) } }
    val weights90 by com.uru2904.mtracker.data.Live.weightsSince(db, chartSince).collectAsState()
    val types by com.uru2904.mtracker.data.Live.types(db).collectAsState()
    val measures by com.uru2904.mtracker.data.Live.measures(db).collectAsState()
    val goal by com.uru2904.mtracker.data.Live.goal(db, LocalDate.now().toString()).collectAsState()
    var selectedType by remember { mutableStateOf<Long?>(null) } // null = peso
    var rangeDays by remember { mutableStateOf(AppSettings.chartRangeDays) }
    var offsetDays by remember { mutableStateOf(0f) } // cuántos días hacia atrás se corrió la ventana
    var editing by remember { mutableStateOf<MeasurementEntry?>(null) }
    var addMeasures by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val es = Locale("es", "AR")

    var allWeighIns by remember { mutableStateOf(false) }
    var measureOpen by remember { mutableStateOf<MeasurementType?>(null) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenHeader("Cuerpo") {
            Text(
                "Pesarse", color = Color.White, fontSize = Type.Body,
                modifier = Modifier.background(Accent, RoundedCornerShape(18.dp)).clickable { weighing = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Peso: tendencia, ritmo y proyección ----
            val today = LocalDate.now()
            val dated = weights90.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() to it.kg }
            val trend = remember(weights90) { com.uru2904.mtracker.domain.WeightTrend.daily(dated) }
            val rate = remember(weights90) { com.uru2904.mtracker.domain.WeightTrend.weeklyRate(dated, today) }
            val last = weights90.lastOrNull()
            val target = goal?.targetWeightKg
            val goalEnd = goal?.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it.isAfter(today) }
            val goalRate = -AppSettings.lossKgPerWeek
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(trend.lastOrNull()?.let { "${num(Math.round(it.second * 10) / 10.0)} kg" } ?: "– kg",
                        color = Color.White, fontSize = Type.Number, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (weights90.isNotEmpty()) Text("Historial ›", color = AccentLight, fontSize = Type.Body,
                        modifier = Modifier.minimumInteractiveComponentSize().clickable { allWeighIns = true }.padding(4.dp))
                }
                val projKg = if (rate != null && goalEnd != null && trend.isNotEmpty())
                    com.uru2904.mtracker.domain.WeightTrend.projection(trend.last(), rate, goalEnd) else null
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    @Composable fun Stat(label: String, value: String, color: Color, m: Modifier) = Column(m) {
                        Text(label, color = Muted, fontSize = Type.Small)
                        Text(value, color = color, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                    }
                    Stat("Ritmo", rate?.let { "${if (it > 0) "+" else ""}${num(Math.round(it * 10) / 10.0)}/sem" } ?: "–",
                        if (rate == null) Muted else if (rate in (goalRate - 0.3)..(goalRate + 0.2)) Ok else Warn, Modifier.weight(1f))
                    if (goalEnd != null) Stat("${goalEnd.dayOfMonth}/${goalEnd.monthValue}", projKg?.let { "${num(Math.round(it * 10) / 10.0)} kg" } ?: "–",
                        if (projKg == null) Muted else if (target == null || projKg <= target + 0.3) Ok else Warn, Modifier.weight(1f))
                    if (target != null) Stat("Meta", "${num(target)} kg", Color.White, Modifier.weight(1f))
                }

                // Rango y gráfico
                val dayMs = 86_400_000L
                val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val firstT = weights90.firstOrNull()?.timestamp
                val maxOffset = firstT?.let { ((endOfToday - it) / dayMs - rangeDays).toFloat().coerceAtLeast(0f) } ?: 0f
                val off = if (rangeDays == 0) 0f else offsetDays.coerceIn(0f, maxOffset)
                val winTo = endOfToday - (off * dayMs).toLong()
                val winFrom = if (rangeDays == 0) firstT ?: (endOfToday - 60 * dayMs) else winTo - rangeDays * dayMs
                // La proyección sale de todas las pesadas (ritmo de los últimos 20 días), no del período que se muestra.
                val showProj = off < 0.5f && goalEnd != null && rate != null && trend.isNotEmpty()
                val axisTo = if (showProj) goalEnd!!.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else winTo
                val ms = { d: LocalDate -> d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }
                val scale = weights90.filter { it.timestamp in winFrom..winTo }.map { it.timestamp to it.kg }
                val trendPts = trend.map { ms(it.first) to it.second }.filter { it.first in winFrom..winTo }
                val proj = if (showProj) listOf(ms(trend.last().first) to trend.last().second,
                    ms(goalEnd!!) to com.uru2904.mtracker.domain.WeightTrend.projection(trend.last(), rate!!, goalEnd)) else emptyList()

                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).height(32.dp).background(Surface2, RoundedCornerShape(10.dp)).padding(3.dp)) {
                        listOf(30 to "30 d", 60 to "60 d", 91 to "3 m", 182 to "6 m", 0 to "Todo").forEach { (d, label) ->
                            val on = rangeDays == d
                            Box(
                                Modifier.weight(1f).fillMaxHeight().background(if (on) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { rangeDays = d; offsetDays = 0f; AppSettings.chartRangeDays = d },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = if (on) Color.White else Soft, fontSize = Type.Small) }
                        }
                    }
                    val moved = off > 0.5f
                    Box(
                        Modifier.width(48.dp).fillMaxHeight().then(if (moved) Modifier.clickable { offsetDays = 0f } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Text("Hoy", color = if (moved) AccentLight else Color.Transparent, fontSize = Type.Small) }
                }
                Box(
                    Modifier.fillMaxWidth().pointerInput(rangeDays, maxOffset) {
                        if (rangeDays == 0) return@pointerInput
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            offsetDays = (offsetDays + dx * rangeDays / size.width.toFloat()).coerceIn(0f, maxOffset)
                        }
                    }
                ) {
                    if (scale.isEmpty() && trendPts.size < 2) Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                        Text(if (weights90.isEmpty()) "Todavía no hay pesadas" else "Sin pesadas en este período. Deslizá para ver otros.", color = Muted, fontSize = Type.Small)
                    } else Column { WeightChart(scale, trendPts, proj, target, winFrom, axisTo) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val f = DateTimeFormatter.ofPattern("d/M/yy")
                    Text(f.format(Instant.ofEpochMilli(winFrom).atZone(zone)), color = Muted, fontSize = Type.Chart)
                    Text(if (showProj) "${goalEnd!!.dayOfMonth}/${goalEnd.monthValue}" else if (off < 0.5f) "hoy" else f.format(Instant.ofEpochMilli(winTo - 1).atZone(zone)), color = Muted, fontSize = Type.Chart)
                }
            }

            // ---- Medidas: grilla de tarjetas ----
            val lastMeasure = measures.maxByOrNull { it.date }?.date?.let { LocalDate.parse(it) }
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Medidas", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                    Text(
                        lastMeasure?.plusDays(14)?.let { if (!it.isAfter(today)) "  · toca medir" else "  · próximas ${it.dayOfMonth}/${it.monthValue}" } ?: "",
                        color = if (lastMeasure != null && !lastMeasure.plusDays(14).isAfter(today)) Warn else Muted,
                        fontSize = Type.Small, modifier = Modifier.weight(1f),
                    )
                    Text("+ Cargar", color = AccentLight, fontSize = Type.Body,
                        modifier = Modifier.minimumInteractiveComponentSize().clickable { addMeasures = true }.padding(4.dp))
                }
                types.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { t ->
                            val h = measures.filter { it.typeId == t.id }.sortedBy { it.date }
                            val v = h.lastOrNull(); val prev = h.dropLast(1).lastOrNull()
                            Column(
                                Modifier.weight(1f).background(Surface2, RoundedCornerShape(12.dp)).clickable { measureOpen = t }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(t.name, color = Muted, fontSize = Type.Small, maxLines = 1)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(v?.let { "${num(it.cm)} cm" } ?: "–", color = if (v != null) Color.White else Muted,
                                        fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                                    if (v != null && prev != null) {
                                        val d = Math.round((v.cm - prev.cm) * 10) / 10.0
                                        if (d != 0.0) {
                                            val good = if (measureDownIsGood(t.name)) d < 0 else d > 0
                                            Text("  ${if (d > 0) "▲" else "▼"}${num(kotlin.math.abs(d))} cm", color = if (good) Ok else Warn,
                                                fontSize = Type.Small, modifier = Modifier.padding(bottom = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
                if (lastMeasure == null) Text("Todavía no cargaste medidas.", color = Muted, fontSize = Type.Small)
            }

        }
    }

    // ---- Hoja de pesaje ----
    if (weighing) Dialog(onDismissRequest = { weighing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader("Pesarse", onBack = { weighing = false })
            Spacer(Modifier.height(20.dp))
            Text(
                live?.let { "${num(it.kg)} kg" } ?: "– – kg",
                color = if (live?.stabilized == true) Color.White else Soft,
                fontSize = Type.Hero, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                when {
                    scanError != null -> scanError!!
                    locked -> "Ya te pesaste hoy a las " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(lockUntil - 3 * 3_600_000L).atZone(zone)) + "."
                    scanning -> status + " · escuchando" + ".".repeat(dots)
                    else -> status
                },
                color = if (scanError != null) Warn else Muted, fontSize = Type.Body,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text("Reiniciar Bluetooth", color = AccentLight, fontSize = Type.Body,
                modifier = Modifier.align(Alignment.CenterHorizontally).minimumInteractiveComponentSize().clickable { launcher.launch(perms) }.padding(8.dp))
            pending?.let { r ->
                Text("¿Guardar ${num(r.kg)} kg?", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { dismissedKg = r.kg; pending = null; status = "Descartado" },
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2), modifier = Modifier.weight(1f).height(52.dp)) { Text("Descartar", color = Color.White) }
                    Button(onClick = { confirm(r) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.weight(1f).height(52.dp)) { Text("Guardar", color = Color.White) }
                }
            }
            if (locked && pending == null) Button(
                onClick = { lockUntil = 0L; prefs.edit().putLong("lock_until", 0L).apply(); dismissedKg = null },
                colors = ButtonDefaults.buttonColors(containerColor = Surface2), modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Pesarme de nuevo", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("Subite descalzo, quieto, hasta que el número se quede fijo. Mejor al despertar, después del baño.",
                color = Muted, fontSize = Type.Small, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

    // ---- Todas las pesadas ----
    if (allWeighIns) Dialog(onDismissRequest = { allWeighIns = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            ScreenHeader("Historial de peso", onBack = { allWeighIns = false })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                    WeighInRows(weights90.reversed(), fatByWeight, onOpen = { detail = it }, onDelete = { toDelete = it })
                }
            }
        }
    }

    // ---- Detalle de una medida ----
    measureOpen?.let { t -> MeasureDetailDialog(t, measures.filter { it.typeId == t.id }, onEdit = { editing = it }) { measureOpen = null } }

    if (addMeasures) MeasuresDialog(db, types) { addMeasures = false }
    editing?.let { m ->
        types.firstOrNull { it.id == m.typeId }?.let { t -> EditMeasureDialog(db, t, m) { editing = null } }
    }

    weighed?.let { w -> WeighInDialog(db, w) { weighed = null } }
    detail?.let { w -> WeightDetailDialog(db, w) { detail = null } }

    toDelete?.let { w ->
        ConfirmDialog(
            title = "¿Borrar esta pesada?",
            text = "${num(w.kg)} kg se borra de la app y de Health Connect.",
            onConfirm = {
                AppScope.launch {
                    db.weights().delete(w)
                    com.uru2904.mtracker.sync.WeightImport.markDeleted(context, w.timestamp)
                    snack.undoable(
                        "Pesada borrada",
                        undo = { db.weights().insert(w); com.uru2904.mtracker.sync.WeightImport.unmarkDeleted(context, w.timestamp) },
                        commit = { runCatching { health.deleteWeight(Instant.ofEpochMilli(w.timestamp)) } },
                    )
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}


/**
 * Línea con puntos, interactiva: tocar selecciona el punto más cercano
 * (línea vertical + valor y fecha arriba). Por defecto, el último.
 */
@Composable
private fun LineChart(points: List<Pair<Long, Double>>, target: Double?, unit: String, from: Long? = null, to: Long? = null) {
    val values = points.map { it.second } + listOfNotNull(target)
    val lo = values.minOrNull()!!; val hi = values.maxOrNull()!!
    val pad = ((hi - lo) * 0.15).coerceAtLeast(0.5)
    val min = lo - pad; val max = hi + pad
    val t0 = from ?: points.first().first; val t1 = to ?: points.last().first
    var sel by remember(points) { mutableStateOf(points.lastIndex) }
    val zone = ZoneId.systemDefault()
    val p = points[sel.coerceIn(0, points.lastIndex)]

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(
            DateTimeFormatter.ofPattern("EEE d/M/yy", Locale("es", "AR")).format(Instant.ofEpochMilli(p.first).atZone(zone)),
            color = Muted, fontSize = Type.Small,
        )
        Text("${num(Math.round(p.second * 10) / 10.0)} $unit", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth().height(150.dp)) {
        Column(
            Modifier.fillMaxHeight().padding(end = 6.dp).width(28.dp),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End,
        ) {
            Text(num(Math.round(max * 10) / 10.0), color = Muted, fontSize = Type.Chart)
            Text(num(Math.round(min * 10) / 10.0), color = Muted, fontSize = Type.Chart)
        }
        Canvas(
            Modifier.weight(1f).fillMaxHeight()
                .pointerInput(points) {
                    fun pick(x: Float) {
                        val w = size.width.toFloat()
                        sel = points.indices.minByOrNull { i ->
                            val px = if (t1 == t0) w / 2 else ((points[i].first - t0).toFloat() / (t1 - t0)) * w
                            kotlin.math.abs(px - x)
                        } ?: sel
                    }
                    detectTapGestures { pick(it.x) }
                }
        ) {
            fun x(t: Long) = if (t1 == t0) size.width / 2 else ((t - t0).toFloat() / (t1 - t0)) * size.width
            fun y(v: Double) = (size.height * (1 - (v - min) / (max - min))).toFloat()
            listOf(0f, 0.5f, 1f).forEach { f -> drawLine(Track, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f) }
            target?.let {
                val ty = y(it); var xx = 0f
                while (xx < size.width) { drawLine(Color(0xFF555555), Offset(xx, ty), Offset(xx + 6f, ty), 2f); xx += 12f }
            }
            val path = Path()
            points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
            drawPath(path, AccentLight, style = Stroke(width = 3f))
            points.forEach { (t, v) -> drawCircle(AccentLight.copy(alpha = 0.6f), 3.5f, Offset(x(t), y(v))) }
            // Selección
            val sx = x(p.first); val sy = y(p.second)
            drawLine(Color(0xFF666666), Offset(sx, 0f), Offset(sx, size.height), 2f)
            drawCircle(Color.Black, 9f, Offset(sx, sy))
            drawCircle(Color.White, 7f, Offset(sx, sy))
        }
    }
}

/** Carga manual de medidas con cinta (fecha de hoy). */
@Composable
private fun MeasuresDialog(db: AppDatabase, types: List<MeasurementType>, onClose: () -> Unit) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<Long, String>() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cargar medidas", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
            Text("Cinta firme sin apretar, relajado, a la misma altura que la vez anterior. Dejá vacías las que no midas. Tocá ? para ver cómo medir cada una.", color = Muted, fontSize = Type.Small)
            types.forEach { t -> MeasureField(t, values[t.id] ?: "", { values[t.id] = it }, Modifier.fillMaxWidth()) }
            val filled = values.mapNotNull { (id, v) -> parseCm(v)?.let { id to it } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Card), modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Cancelar", color = Color.White)
                }
                Button(
                    onClick = {
                        val today = LocalDate.now().toString()
                        val app = context.applicationContext
                        // Guarda y cierra al instante; la planilla se actualiza por detrás.
                        AppScope.launch {
                            val ok = runCatching {
                                db.measurements().insertAll(filled.map { (id, cm) -> MeasurementEntry(typeId = id, date = today, cm = cm) })
                            }
                            snack.say(if (ok.isSuccess) "Medidas guardadas" else "No se pudieron guardar: ${ok.exceptionOrNull()?.message}")
                            if (ok.isSuccess) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { SheetsSync(app, db).syncSilently(app, HealthRepository(app)) }
                            }
                        }
                        onClose()
                    },
                    enabled = filled.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Track),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Guardar", color = if (filled.isNotEmpty()) Color.White else Muted) }
            }
        }
    }
}

/** Editar o borrar una medida ya cargada. */
@Composable
private fun EditMeasureDialog(db: AppDatabase, t: MeasurementType, m: MeasurementEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val snack = LocalSnack.current
    var value by remember { mutableStateOf(num(m.cm)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val app = context.applicationContext
    fun syncLater() = AppScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { SheetsSync(app, db).syncSilently(app, HealthRepository(app)) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.name} · " + DateTimeFormatter.ofPattern("d/M/yyyy").format(LocalDate.parse(m.date)), color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Borrar medida", tint = Danger)
                }
            }
        },
        text = { MeasureField(t, value, { value = it }, Modifier.fillMaxWidth()) },
        confirmButton = {
            val cm = parseCm(value)
            TextButton(enabled = cm != null, onClick = {
                AppScope.launch {
                    runCatching { db.measurements().update(m.copy(cm = cm!!)) }
                        .onSuccess { snack.say("Medida actualizada"); syncLater() }
                        .onFailure { snack.say("No se pudo guardar: ${it.message}") }
                }
                onClose()
            }) { Text("Guardar", color = if (cm != null) AccentLight else Muted) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar", color = Muted) } },
    )
    if (confirmDelete) ConfirmDialog(
        title = "¿Borrar esta medida?",
        text = "${t.name} del ${DateTimeFormatter.ofPattern("d/M/yyyy").format(LocalDate.parse(m.date))}: ${num(m.cm)} cm. También se borra de la planilla.",
        confirmLabel = "Borrar",
        onConfirm = {
            confirmDelete = false
            AppScope.launch {
                runCatching {
                    com.uru2904.mtracker.sheets.MeasureDeletes.add(app, m.typeId, m.date)
                    db.measurements().delete(m)
                }.onSuccess {
                    snack.undoable("Medida borrada", undo = {
                        com.uru2904.mtracker.sheets.MeasureDeletes.remove(app, m.typeId, m.date)
                        db.measurements().insert(m.copy(id = 0))
                    }, commit = { syncLater() })
                }.onFailure { snack.say("No se pudo borrar: ${it.message}") }
            }
            onClose()
        },
        onDismiss = { confirmDelete = false },
    )
}

/** En cintura y cadera conviene bajar; en el resto (pecho, brazo, muslo, gemelos), subir. */
fun measureDownIsGood(name: String): Boolean = name.lowercase().let { "cintura" in it || "cadera" in it }

/** Filas de pesadas: fecha, % de grasa del reloj (si hay) y peso; deslizar para borrar. */
@Composable
private fun WeighInRows(items: List<WeightEntry>, fat: Map<Long, Double>, onOpen: (WeightEntry) -> Unit, onDelete: (WeightEntry) -> Unit) {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("EEE d/M HH:mm", Locale("es", "AR"))
    items.forEachIndexed { i, w -> androidx.compose.runtime.key(w.id) {
        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
        SwipeToDelete(onDelete = { onDelete(w) }) {
            Row(Modifier.fillMaxWidth().background(Card).clickable { onOpen(w) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Instant.ofEpochMilli(w.timestamp).atZone(zone)), color = Soft, fontSize = Type.Body, modifier = Modifier.weight(1f))
                fat[w.id]?.let { Text("grasa ${num(Math.round(it * 10) / 10.0)} %", color = Muted, fontSize = Type.Small, modifier = Modifier.padding(end = 14.dp)) }
                Text("${num(w.kg)} kg", color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
            }
        }
    } }
}

/** Pantalla de una medida: gráfico, historial y editar. */
@Composable
private fun MeasureDetailDialog(t: MeasurementType, items: List<MeasurementEntry>, onEdit: (MeasurementEntry) -> Unit, onClose: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val es = Locale("es", "AR")
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            ScreenHeader(t.name, onBack = onClose)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val sorted = items.sortedBy { it.date }
                val pts = sorted.map { LocalDate.parse(it.date).atStartOfDay(zone).toInstant().toEpochMilli() to it.cm }
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    if (pts.size < 2) Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        Text(if (pts.isEmpty()) "Sin mediciones todavía" else "Hace falta más de una medición para el gráfico", color = Muted, fontSize = Type.Small)
                    } else LineChart(pts, null, "cm")
                }
                Text("Historial · tocá una para editarla o borrarla", color = Muted, fontSize = Type.Small)
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                    val desc = sorted.reversed()
                    desc.forEachIndexed { i, m ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
                        Row(Modifier.fillMaxWidth().clickable { onEdit(m) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(DateTimeFormatter.ofPattern("EEE d/M/yyyy", es).format(LocalDate.parse(m.date)), color = Color.White, fontSize = Type.Body, modifier = Modifier.weight(1f))
                            desc.getOrNull(i + 1)?.let { p ->
                                val d = Math.round((m.cm - p.cm) * 10) / 10.0
                                if (d != 0.0) {
                                    val good = if (measureDownIsGood(t.name)) d < 0 else d > 0
                                    Text("${if (d > 0) "▲" else "▼"}${num(kotlin.math.abs(d))} cm", color = if (good) Ok else Warn, fontSize = Type.Small, modifier = Modifier.padding(end = 12.dp))
                                }
                            }
                            Text("${num(m.cm)} cm", color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("Cómo medir", color = Muted, fontSize = Type.Small)
                Text(measureHelp(t.name), color = Soft, fontSize = Type.Body,
                    modifier = Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp))
            }
        }
    }
}

/**
 * Gráfico de peso: puntos de balanza (claros), línea de tendencia, proyección punteada y meta.
 * Tocar selecciona la pesada más cercana.
 */
@Composable
private fun WeightChart(
    scale: List<Pair<Long, Double>>, trend: List<Pair<Long, Double>>, proj: List<Pair<Long, Double>>,
    target: Double?, from: Long, to: Long,
) {
    val values = scale.map { it.second } + trend.map { it.second } + proj.map { it.second } + listOfNotNull(target)
    val lo = values.minOrNull() ?: 0.0; val hi = values.maxOrNull() ?: 1.0
    val pad = ((hi - lo) * 0.12).coerceAtLeast(0.4)
    val min = lo - pad; val max = hi + pad
    var sel by remember(scale) { mutableStateOf(scale.lastIndex) }
    val zone = ZoneId.systemDefault()
    val p = scale.getOrNull(sel)
    val trendAt = p?.let { s -> trend.minByOrNull { kotlin.math.abs(it.first - s.first) }?.second }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(p?.let { DateTimeFormatter.ofPattern("EEE d/M/yy", Locale("es", "AR")).format(Instant.ofEpochMilli(it.first).atZone(zone)) } ?: "",
            color = Muted, fontSize = Type.Small)
        Row(verticalAlignment = Alignment.Bottom) {
            trendAt?.let { Text("tend. ${num(Math.round(it * 10) / 10.0)}  ", color = Muted, fontSize = Type.Small) }
            Text(p?.let { "${num(it.second)} kg" } ?: "", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.Bold)
        }
    }
    Row(Modifier.fillMaxWidth().height(170.dp).padding(top = 6.dp)) {
        Column(Modifier.fillMaxHeight().padding(end = 6.dp).width(28.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            Text(num(Math.round(max * 10) / 10.0), color = Muted, fontSize = Type.Chart)
            Text(num(Math.round(min * 10) / 10.0), color = Muted, fontSize = Type.Chart)
        }
        Canvas(
            Modifier.weight(1f).fillMaxHeight().pointerInput(scale) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat()
                    sel = scale.indices.minByOrNull { i -> kotlin.math.abs(((scale[i].first - from).toFloat() / (to - from)) * w - pos.x) } ?: sel
                }
            }
        ) {
            fun x(t: Long) = ((t - from).toFloat() / (to - from).coerceAtLeast(1)) * size.width
            fun y(v: Double) = (size.height * (1 - (v - min) / (max - min))).toFloat()
            listOf(0f, 0.5f, 1f).forEach { f -> drawLine(Track, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f) }
            target?.let {
                val ty = y(it); var xx = 0f
                while (xx < size.width) { drawLine(Color(0xFF555555), Offset(xx, ty), Offset(xx + 6f, ty), 2f); xx += 12f }
            }
            scale.forEach { (t, v) -> drawCircle(AccentLight.copy(alpha = 0.35f), 5f, Offset(x(t), y(v))) }
            if (trend.size >= 2) {
                val path = Path()
                trend.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
                drawPath(path, AccentLight, style = Stroke(width = 4f))
            }
            if (proj.size == 2) drawLine(AccentLight, Offset(x(proj[0].first), y(proj[0].second)), Offset(x(proj[1].first), y(proj[1].second)), 3f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            p?.let {
                val sx = x(it.first); val sy = y(it.second)
                drawLine(Color(0xFF666666), Offset(sx, 0f), Offset(sx, size.height), 2f)
                drawCircle(Color.Black, 9f, Offset(sx, sy)); drawCircle(Color.White, 7f, Offset(sx, sy))
            }
        }
    }
}
