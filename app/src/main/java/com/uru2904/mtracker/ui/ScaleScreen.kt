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
            val allPoints: List<Pair<Long, Double>> = if (selType == null) weights90.map { it.timestamp to it.kg }
                else measures.filter { it.typeId == selType.id }.map {
                    LocalDate.parse(it.date).atStartOfDay(zone).toInstant().toEpochMilli() to it.cm
                }
            // Ventana visible: los últimos [rangeDays] días, corrida [offsetDays] hacia atrás.
            val dayMs = 86_400_000L
            val endOfToday = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val maxOffset = allPoints.firstOrNull()?.let { ((endOfToday - it.first) / dayMs - rangeDays).toFloat().coerceAtLeast(0f) } ?: 0f
            val off = if (rangeDays == 0) 0f else offsetDays.coerceIn(0f, maxOffset)
            val winTo = if (rangeDays == 0) allPoints.lastOrNull()?.first ?: endOfToday else endOfToday - (off * dayMs).toLong()
            val winFrom = if (rangeDays == 0) allPoints.firstOrNull()?.first ?: (endOfToday - 60 * dayMs) else winTo - rangeDays * dayMs
            val points = allPoints.filter { it.first in winFrom..winTo }
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selType == null) "Peso" else selType.name, color = Muted, fontSize = Type.Small)
                    if (off > 0.5f) Text("Volver a hoy", color = AccentLight, fontSize = Type.Small,
                        modifier = Modifier.minimumInteractiveComponentSize().clickable { offsetDays = 0f }.padding(4.dp))
                }
                // Botones de rango
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30 d", 60 to "60 d", 91 to "3 m", 182 to "6 m", 0 to "Todo").forEach { (d, label) ->
                        val on = rangeDays == d
                        Box(
                            Modifier.background(if (on) Accent else Surface2, RoundedCornerShape(14.dp))
                                .clickable { rangeDays = d; offsetDays = 0f; AppSettings.chartRangeDays = d }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(label, color = if (on) Color.White else Soft, fontSize = Type.Small) }
                    }
                }
                // Deslizar horizontalmente mueve la ventana en el tiempo (no en "Todo").
                Box(
                    Modifier.fillMaxWidth().pointerInput(rangeDays, maxOffset) {
                        if (rangeDays == 0) return@pointerInput
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            val perPx = rangeDays / size.width.toFloat()
                            offsetDays = (offsetDays + dx * perPx).coerceIn(0f, maxOffset)
                        }
                    }
                ) {
                    if (points.size < 2) Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                allPoints.isEmpty() -> "Sin datos todavía"
                                allPoints.size == 1 -> "Hace falta más de una medición para el gráfico"
                                else -> "Sin datos en este período. Deslizá para ver otros."
                            }, color = Muted, fontSize = Type.Small,
                        )
                    } else Column { LineChart(points, if (selType == null) target else null, if (selType == null) "kg" else "cm", winFrom, winTo) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val f = DateTimeFormatter.ofPattern("d/M/yy")
                    Text(f.format(Instant.ofEpochMilli(winFrom).atZone(zone)), color = Muted, fontSize = Type.Chart)
                    if (rangeDays != 0 && maxOffset > 0f) Text("← deslizá para ver antes", color = Muted, fontSize = Type.Chart)
                    Text(if (off < 0.5f && rangeDays != 0) "hoy" else f.format(Instant.ofEpochMilli(winTo - 1).atZone(zone)), color = Muted, fontSize = Type.Chart)
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
            // ---- Historial de la medida elegida: tocar para editar o borrar ----
            types.firstOrNull { it.id == selectedType }?.let { t ->
                val hist = measures.filter { it.typeId == t.id }.sortedByDescending { it.date }
                if (hist.isNotEmpty()) {
                    Text("Historial de ${t.name.lowercase()} · tocá una para editarla", color = Muted, fontSize = Type.Small)
                    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                        hist.forEachIndexed { i, m ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
                            Row(
                                Modifier.fillMaxWidth().clickable { editing = m }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(DateTimeFormatter.ofPattern("EEE d/M/yyyy", es).format(LocalDate.parse(m.date)), color = Color.White, fontSize = Type.Body, modifier = Modifier.weight(1f))
                                Text("${num(m.cm)} cm", color = Color.White, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                            }
                        }
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
