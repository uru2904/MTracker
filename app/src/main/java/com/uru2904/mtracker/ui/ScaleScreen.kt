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
import androidx.compose.foundation.layout.fillMaxSize
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

    LaunchedEffect(Unit) { launcher.launch(perms) }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    // ---- Datos de la pantalla Cuerpo ----
    val chartSince = remember { com.uru2904.mtracker.data.Live.daysAgo(365) }
    LaunchedEffect(Unit) { runCatching { com.uru2904.mtracker.sync.WeightImport.run(context, db, health) } }
    val weights90 by com.uru2904.mtracker.data.Live.weightsSince(db, chartSince).collectAsState()
    val types by com.uru2904.mtracker.data.Live.types(db).collectAsState()
    val measures by com.uru2904.mtracker.data.Live.measures(db).collectAsState()
    val goal by com.uru2904.mtracker.data.Live.goal(db, LocalDate.now().toString()).collectAsState()
    var selectedType by remember { mutableStateOf<Long?>(null) } // null = peso
    var addMeasures by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val es = Locale("es", "AR")

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenHeader("Cuerpo")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Resumen de peso ----
            val last = weights90.lastOrNull()
            val monthAgo = weights90.filter { it.timestamp <= System.currentTimeMillis() - 25L * 86_400_000 }.lastOrNull()
                ?: weights90.firstOrNull()
            val target = goal?.targetWeightKg
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Peso actual", color = Muted, fontSize = Type.Small)
                Text(last?.let { "${num(it.kg)} kg" } ?: "– kg", color = Color.White, fontSize = Type.Number, fontWeight = FontWeight.Bold)
                val notes = buildList {
                    if (last != null && monthAgo != null && monthAgo.id != last.id) {
                        val d = Math.round((last.kg - monthAgo.kg) * 10) / 10.0
                        val days = ((last.timestamp - monthAgo.timestamp) / 86_400_000).coerceAtLeast(1)
                        add("${if (d > 0) "+" else ""}${num(d)} kg en $days días")
                    }
                    if (last != null && target != null) {
                        add(if (last.kg > target) "faltan ${num(Math.round((last.kg - target) * 10) / 10.0)} kg para ${num(target)}" else "objetivo cumplido")
                    }
                }
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = Soft, fontSize = Type.Body)
            }

            // ---- Gráfico: peso o la medida seleccionada ----
            val selType = types.firstOrNull { it.id == selectedType }
            val points: List<Pair<Long, Double>> = if (selType == null) weights90.map { it.timestamp to it.kg }
                else measures.filter { it.typeId == selType.id }.map {
                    LocalDate.parse(it.date).atStartOfDay(zone).toInstant().toEpochMilli() to it.cm
                }
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (selType == null) "Peso" else selType.name, color = Muted, fontSize = Type.Small)
                }
                if (points.size < 2) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(if (points.isEmpty()) "Sin datos todavía" else "Hace falta más de una medición para el gráfico", color = Muted, fontSize = Type.Small)
                } else LineChart(points, if (selType == null) target else null, if (selType == null) "kg" else "cm")
                if (points.size >= 2) Row(Modifier.fillMaxWidth().padding(start = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val f = DateTimeFormatter.ofPattern("d/M")
                    Text(f.format(Instant.ofEpochMilli(points.first().first).atZone(zone)), color = Muted, fontSize = Type.Chart)
                    Text(f.format(Instant.ofEpochMilli(points.last().first).atZone(zone)), color = Muted, fontSize = Type.Chart)
                }
            }

            // ---- Medidas (tocar una la grafica) ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tocá una para ver su gráfico", color = Muted, fontSize = Type.Small)
                Text("+ Cargar medidas", color = AccentLight, fontSize = Type.Small, modifier = Modifier.minimumInteractiveComponentSize().clickable { addMeasures = true }.padding(4.dp))
            }
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                val rows = listOf<Pair<Long?, String>>(null to "Peso") + types.map { it.id to it.name }
                rows.forEachIndexed { i, (id, name) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
                    val sel = selectedType == id
                    val (value, delta) = if (id == null) {
                        (last?.let { "${num(it.kg)} kg" } ?: "–") to null
                    } else {
                        val h = measures.filter { it.typeId == id }
                        val v = h.lastOrNull()
                        val prev = h.dropLast(1).lastOrNull()
                        (v?.let { "${num(it.cm)} cm" } ?: "Sin cargar") to
                            if (v != null && prev != null) Math.round((v.cm - prev.cm) * 10) / 10.0 else null
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { selectedType = id }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = if (sel) AccentLight else Color.White, fontSize = Type.Body, modifier = Modifier.weight(1f))
                        delta?.takeIf { it != 0.0 }?.let {
                            Text("${if (it > 0) "+" else ""}${num(it)}", color = Muted, fontSize = Type.Small, modifier = Modifier.padding(end = 12.dp))
                        }
                        Text(value, color = if (sel) AccentLight else if (value == "Sin cargar") Muted else Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            measures.maxByOrNull { it.date }?.let {
                Text("Últimas medidas: " + DateTimeFormatter.ofPattern("EEE d/M", es).format(LocalDate.parse(it.date)), color = Muted, fontSize = Type.Small)
            }

            // ---- Balanza ----
            Text("Pesarse", color = Muted, fontSize = Type.Small)
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        live?.let { "${num(it.kg)} kg" } ?: "– – kg",
                        color = if (live?.stabilized == true) Color.White else Soft,
                        fontSize = Type.Title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    Text("Reiniciar", color = AccentLight, fontSize = Type.Small, modifier = Modifier.minimumInteractiveComponentSize().clickable { launcher.launch(perms) }.padding(4.dp))
                }
                Text(status, color = Muted, fontSize = Type.Small)
                Text(
                    when {
                        scanError != null -> scanError!!
                        scanning -> "Escuchando" + ".".repeat(dots)
                        else -> "Sin escuchar"
                    },
                    color = if (scanError != null) Warn else Muted, fontSize = Type.Small,
                )
            }

            pending?.let { r ->
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("¿Guardar ${num(r.kg)} kg?", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { dismissedKg = r.kg; pending = null; status = "Descartado" },
                            colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                            modifier = Modifier.weight(1f),
                        ) { Text("Descartar", color = Color.White) }
                        Button(
                            onClick = { confirm(r) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.weight(1f),
                        ) { Text("Guardar", color = Color.White) }
                    }
                }
            }

            if (locked) Row(
                Modifier.fillMaxWidth().background(Color(0xFF0B0B0B), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Pesaje registrado. Bloqueado hasta las " +
                        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(lockUntil).atZone(zone)),
                    color = Muted, fontSize = Type.Small, modifier = Modifier.weight(1f),
                )
                Text(
                    "Desbloquear", color = AccentLight, fontSize = Type.Small,
                    modifier = Modifier.clickable {
                        lockUntil = 0L; prefs.edit().putLong("lock_until", 0L).apply(); dismissedKg = null
                    }.padding(start = 10.dp),
                )
            }

            // ---- Pesadas ----
            Text("Pesadas · últimos 30 días", color = Muted, fontSize = Type.Small)
            val fmt = DateTimeFormatter.ofPattern("EEE d/M HH:mm", es)
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                recent.reversed().take(15).forEachIndexed { i, w -> androidx.compose.runtime.key(w.id) {
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(Track))
                    SwipeToDelete(onDelete = { toDelete = w }) {
                        Row(Modifier.fillMaxWidth().background(Card).clickable { detail = w }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmt.format(Instant.ofEpochMilli(w.timestamp).atZone(zone)), color = Soft, fontSize = Type.Body)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                fatByWeight[w.id]?.let { Text("${num(Math.round(it * 10) / 10.0)} %", color = Soft, fontSize = Type.Body) }
                                Text("${num(w.kg)} kg", color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } }
                if (recent.isEmpty()) Text("Todavía no hay pesadas. Subite a la balanza con esta pantalla abierta y la app la detecta sola.", color = Muted, fontSize = Type.Body, modifier = Modifier.padding(14.dp))
            }
        }
    }

    if (addMeasures) MeasuresDialog(db, types) { addMeasures = false }

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
 * Línea con puntos, interactiva: tocar o arrastrar selecciona el punto más cercano
 * (línea vertical + valor y fecha arriba). Por defecto, el último.
 */
@Composable
private fun LineChart(points: List<Pair<Long, Double>>, target: Double?, unit: String) {
    val values = points.map { it.second } + listOfNotNull(target)
    val lo = values.minOrNull()!!; val hi = values.maxOrNull()!!
    val pad = ((hi - lo) * 0.15).coerceAtLeast(0.5)
    val min = lo - pad; val max = hi + pad
    val t0 = points.first().first; val t1 = points.last().first
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
                .pointerInput(points) {
                    fun pick(x: Float) {
                        val w = size.width.toFloat()
                        sel = points.indices.minByOrNull { i ->
                            val px = if (t1 == t0) w / 2 else ((points[i].first - t0).toFloat() / (t1 - t0)) * w
                            kotlin.math.abs(px - x)
                        } ?: sel
                    }
                    detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { change, _ -> pick(change.position.x) }
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
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<Long, String>() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cargar medidas", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
            Text("Cinta firme sin apretar, relajado, a la misma altura que la vez anterior. Dejá vacías las que no midas.", color = Muted, fontSize = Type.Small)
            types.forEach { t ->
                OutlinedTextField(
                    value = values[t.id] ?: "",
                    onValueChange = { values[t.id] = it },
                    label = { Text(t.name) },
                    suffix = { Text("cm", color = Muted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentLight, unfocusedBorderColor = Muted,
                        focusedLabelColor = AccentLight, unfocusedLabelColor = Muted, cursorColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val filled = values.filterValues { it.replace(',', '.').toDoubleOrNull() != null }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Card), modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Cancelar", color = Color.White)
                }
                Button(
                    onClick = {
                        val today = LocalDate.now().toString()
                        scope.launch {
                            db.measurements().insertAll(filled.map { (id, v) -> MeasurementEntry(typeId = id, date = today, cm = v.replace(',', '.').toDouble()) })
                            runCatching { SheetsSync(context, db).syncSilently(context, HealthRepository(context)) }
                            onClose()
                        }
                    },
                    enabled = filled.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Track),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Guardar", color = if (filled.isNotEmpty()) Color.White else Muted) }
            }
        }
    }
}
