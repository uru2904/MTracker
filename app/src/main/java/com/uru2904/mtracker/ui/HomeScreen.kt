package com.uru2904.mtracker.ui

import androidx.compose.material3.minimumInteractiveComponentSize
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.Live
import com.uru2904.mtracker.data.UiCache
import com.uru2904.mtracker.domain.ShiftCalendar
import com.uru2904.mtracker.domain.SleepScorer
import com.uru2904.mtracker.gym.HevyImporter
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.health.SleepNight
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val es = Locale("es", "AR")
private val Green = Ok
private val Yellow = Warn
private val Red = Danger

/** Lo que Inicio lee de Health Connect (se guarda en memoria para no parpadear). */
data class HomeHealth(val steps: Long, val night: SleepNight?, val exercises: List<String>, val nights: List<SleepNight> = emptyList())

/**
 * Inicio (Whoop + MacroFactor): 3 anillos del día (calorías, proteína, sueño) y
 * tarjetas tocables con mini gráfico (gym, peso, pasos). Cuenta regresiva a Río.
 */
@Composable
fun HomeScreen(health: HealthRepository, db: AppDatabase, sharedUri: MutableState<Uri?>, onNavigate: (Tab) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val calendar = remember { com.uru2904.mtracker.data.AppSettings.calendar() }
    val zone = ZoneId.systemDefault()
    val today = remember { calendar.logicalDate(LocalDateTime.now()) }

    // Datos de la base (en memoria: sin parpadeo)
    val totals by Live.totals(db, today.toString()).collectAsState()
    val goal by Live.goal(db, today.toString()).collectAsState()
    val sessions by Live.gymSessions(db).collectAsState()
    val weights by Live.weightsSince(db, Live.daysAgo(30)).collectAsState()

    // Importar el CSV de Hevy cuando se comparte a la app
    val snack = LocalSnack.current
    LaunchedEffect(sharedUri.value) {
        sharedUri.value?.let { uri ->
            snack.say(try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("No se pudo abrir el archivo")
                val r = HevyImporter(db).import(text)
                "Hevy: ${r.added} sesiones nuevas, ${r.skipped} ya estaban."
            } catch (e: Exception) { "No se pudo importar: ${e.message}" })
            sharedUri.value = null
        }
    }

    // Health Connect
    val available = remember { health.isAvailable() }
    var granted by remember { mutableStateOf(UiCache.granted) }
    var data by remember { mutableStateOf(UiCache.inicio as? HomeHealth) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        try {
            granted = health.grantedPermissions()
            UiCache.granted = granted
            if (granted!!.isNotEmpty()) {
                val fmt = DateTimeFormatter.ofPattern("EEE d/M", es)
                val nights = runCatching { health.sleepNights(7) }.getOrDefault(emptyList())
                data = HomeHealth(
                    nights = nights,
                    steps = health.stepsToday(),
                    night = nights.firstOrNull(),
                    exercises = health.recentExercises(7).take(3).map {
                        "${fmt.format(it.startTime.atZone(zone))} · ${HealthRepository.exerciseName(it.exerciseType)} · " +
                            "${Duration.between(it.startTime, it.endTime).toMinutes()} min"
                    },
                )
                UiCache.inicio = data
            }
            error = null
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { refresh() }
    }
    LaunchedEffect(Unit) { if (available) refresh() }
    var refreshing by remember { mutableStateOf(false) }

    // Uso de pantalla (celular + tablet)
    var usage by remember { mutableStateOf(UiCache.usage as? UsageData) }
    suspend fun loadUsageCard() {
        val token = runCatching { (com.uru2904.mtracker.sheets.SheetsSync(context, db).authorize(context) as? com.uru2904.mtracker.sheets.AuthStep.Token)?.token }.getOrNull()
        usage = runCatching { loadUsage(context, db, token) }.getOrNull() ?: usage
        UiCache.usage = usage
    }
    LaunchedEffect(Unit) { loadUsageCard() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Encabezado fijo ----
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        }
        val endDate = goal?.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val daysLeft = endDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
        ScreenHeader(
            "MTracker",
            subtitle = "v$version" + if (daysLeft != null && daysLeft >= 0) " · Faltan $daysLeft días para ${goal?.name}" else "",
        ) {
            androidx.compose.material3.IconButton(onClick = { onNavigate(Tab.Ajustes) }) {
                androidx.compose.material3.Icon(Icons.Outlined.Settings, "Ajustes", tint = Soft)
            }
        }

        RefreshBox(refreshing = refreshing, onRefresh = { scope.launch { refreshing = true; refresh(); loadUsageCard(); refreshing = false } }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ---- Avisos (solo si hace falta) ----
                if (!available) InfoCard("Health Connect no está disponible en este teléfono.")
                val required = remember {
                    health.permissions +
                        (if (health.backgroundReadSupported()) setOf(health.backgroundPermission) else emptySet()) +
                        (if (health.historyReadSupported()) setOf(health.historyPermission) else emptySet())
                }
                val missing = granted?.let { required - it }.orEmpty()
                if (available && (granted == null || missing.isNotEmpty())) {
                    Notice(
                        if (granted.isNullOrEmpty()) "Falta dar permisos de Health Connect" else "Faltan ${missing.size} permisos de Health Connect",
                        "Conectar",
                    ) { launcher.launch(required) }
                }
                val power = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
                var batteryOk by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                    batteryOk = power.isIgnoringBatteryOptimizations(context.packageName)
                    onPauseOrDispose { }
                }
                if (!batteryOk) Notice("La sincronización en segundo plano está limitada por la batería", "Permitir") {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(android.net.Uri.parse("package:" + context.packageName))
                        )
                    }
                }
                error?.let { Notice("Error al leer Health Connect: $it", null) {} }

                CheckInCard(db)

                // ---- Anillos del día ----
                val protT = goal?.proteinTarget ?: 150
                val night = data?.night?.takeIf { ChronoUnit.DAYS.between(it.date, LocalDate.now()) <= 1 }
                val sleepMin = night?.totalMin ?: 0
                Row(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(vertical = 18.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val gymDays = sessions.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
                        .filter { ChronoUnit.DAYS.between(it, LocalDate.now()) in 0..6 }.distinct().size
                    Ring(
                        Modifier.weight(1f).clickable { onNavigate(Tab.Gym) }, gymDays / 3f,
                        when { gymDays >= 3 -> Green; gymDays == 2 -> Yellow; else -> Red },
                        "$gymDays / 3", "en 7 días", "Entrenamientos",
                    )
                    val pf = (totals.protein / protT).toFloat()
                    Ring(
                        Modifier.weight(1f).clickable { onNavigate(Tab.Comidas) }, pf,
                        if (pf >= 1f) Green else AccentLight,
                        "${totals.protein.toInt()} g", "de $protT g", "Proteína",
                    )
                    val sf = sleepMin / 420f
                    Ring(
                        Modifier.weight(1f).clickable { onNavigate(Tab.Sueno) }, sf,
                        when { night == null -> Muted; sleepMin >= 420 -> Green; sleepMin >= 360 -> Yellow; else -> Red },
                        if (night == null) "–" else "${sleepMin / 60} h ${sleepMin % 60}",
                        night?.let { "puntaje ${SleepScorer.score(it.minutes).score}" } ?: "sin datos", "Sueño",
                    )
                }

                // ---- App recién instalada: ofrecer restaurar la copia de Drive ----
                var empty by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { empty = runCatching { com.uru2904.mtracker.backup.DriveBackup.isEmpty(context) }.getOrDefault(false) }
                if (empty) DashCard("¿Reinstalaste la app?", { onNavigate(Tab.Ajustes) }) {
                    Text("Si tenés una copia en tu Drive, restaurala desde Ajustes → Copia de seguridad.", color = Muted, fontSize = Type.Small)
                }

                // ---- Gym ----
                val dateOf = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
                val last7 = sessions.count { ChronoUnit.DAYS.between(dateOf(it.start), LocalDate.now()) in 0..6 }
                val daysSince = sessions.firstOrNull()?.let { ChronoUnit.DAYS.between(dateOf(it.start), LocalDate.now()).toInt() }
                val rotation = listOf("Full A", "Full B", "Full C")
                val next = sessions.firstOrNull { it.routine in rotation }?.routine?.let { rotation[(rotation.indexOf(it) + 1) % 3] } ?: "Full A"
                DashCard("Gym", { onNavigate(Tab.Gym) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Próxima: $next", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("$last7/3 en 7 días", color = if (last7 >= 3) Green else if (last7 == 2) Yellow else Red, fontSize = Type.Small)
                                daysSince?.let {
                                    Text(
                                        if (it == 0) "entrenaste hoy" else "hace $it ${if (it == 1) "día" else "días"}",
                                        color = if (it <= 2) Green else if (it == 3) Yellow else Red, fontSize = Type.Small,
                                    )
                                }
                            }
                        }
                        // Mini: entrenamientos de los últimos 7 días
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                            (6 downTo 0).forEach { d ->
                                val day = LocalDate.now().minusDays(d.toLong())
                                val did = sessions.any { dateOf(it.start) == day }
                                Box(Modifier.width(8.dp).height(if (did) 26.dp else 6.dp).background(if (did) AccentLight else Color(0xFF2E2E2E), RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }

                // ---- Peso ----
                DashCard("Peso", { onNavigate(Tab.Cuerpo) }) {
                    val lastW = weights.lastOrNull()
                    val target = goal?.targetWeightKg
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(lastW?.let { "${num(it.kg)} kg" } ?: "Sin pesadas", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
                            val first = weights.firstOrNull()
                            val notes = buildList {
                                if (lastW != null && first != null && first.id != lastW.id) {
                                    val d = Math.round((lastW.kg - first.kg) * 10) / 10.0
                                    add("${if (d > 0) "+" else ""}${num(d)} kg en 30 días")
                                }
                                if (lastW != null && target != null && lastW.kg > target) add("faltan ${num(Math.round((lastW.kg - target) * 10) / 10.0)} kg")
                            }
                            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = Soft, fontSize = Type.Small)
                        }
                        if (weights.size >= 2) Sparkline(weights.map { it.kg }, Modifier.width(90.dp).height(36.dp))
                    }
                }

                // ---- Sueño: últimas 7 noches con el rango 7–9 h ----
                DashCard("Sueño · 7 noches", { onNavigate(Tab.Sueno) }) {
                    val nights = data?.nights.orEmpty()
                    val withData = nights.filter { ChronoUnit.DAYS.between(it.date, LocalDate.now()) in 0..6 }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val avg = if (withData.isEmpty()) null else withData.sumOf { it.totalMin } / withData.size
                            Text(avg?.let { "${it / 60} h ${it % 60} min" } ?: if (data == null) "…" else "Sin datos", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
                            Text("promedio", color = Soft, fontSize = Type.Small)
                        }
                        val byDate = nights.associateBy { it.date }
                        Canvas(Modifier.width(120.dp).height(44.dp)) {
                            val maxH = 12f
                            fun y(h: Float) = size.height * (1 - h / maxH)
                            drawRect(OkBg, Offset(0f, y(9f)), Size(size.width, y(7f) - y(9f)))
                            val n = 7; val gap = 4.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
                            (6 downTo 0).forEachIndexed { i, d ->
                                val night = byDate[LocalDate.now().minusDays(d.toLong())] ?: return@forEachIndexed
                                val h = (night.totalMin / 60f).coerceAtMost(maxH)
                                drawRect(
                                    when { night.totalMin >= 420 -> AccentLight; night.totalMin >= 360 -> Yellow; else -> Red },
                                    Offset(i * (w + gap), y(h)), Size(w, size.height - y(h)),
                                )
                            }
                        }
                    }
                }

                // ---- Pantalla: X, Instagram, YouTube, Kick y Twitch (celular + tablet) ----
                DashCard("Pantalla hoy", { onNavigate(Tab.Uso) }) {
                    val todayMin = usage?.total(LocalDate.now())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text(todayMin?.let { hmin(it) } ?: "–", color = todayMin?.let { usageColor(it) } ?: Muted,
                            fontSize = Type.Headline, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("de ${SCREEN_LIMIT_MIN / 60} h", color = Muted, fontSize = Type.Small)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Track, RoundedCornerShape(4.dp))) {
                        Box(
                            Modifier.fillMaxWidth(((todayMin ?: 0).toFloat() / SCREEN_LIMIT_MIN).coerceIn(0f, 1f)).height(8.dp)
                                .background(usageColor(todayMin ?: 0), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

/** Aviso compacto con una acción opcional. */
@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Yellow, fontSize = Type.Small, modifier = Modifier.weight(1f))
        action?.let { Text(it, color = AccentLight, fontSize = Type.Body, modifier = Modifier.minimumInteractiveComponentSize().clickable { onAction() }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) }
    }
}

/** Tarjeta del tablero: título, contenido y flecha si lleva a otra pantalla. */
@Composable
private fun DashCard(title: String, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Muted, fontSize = Type.Small, modifier = Modifier.weight(1f))
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        content()
    }
}

/** Anillo de progreso estilo Whoop: valor al centro, etiqueta abajo. */
@Composable
private fun Ring(modifier: Modifier, fraction: Float, color: Color, value: String, sub: String, label: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                Text(sub, color = Muted, fontSize = Type.Chart, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        Text(label, color = Soft, fontSize = Type.Small)
    }
}

/** Mini línea de tendencia. */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    val min = values.min(); val max = values.max()
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    Canvas(modifier) {
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = (size.height * (1 - (v - min) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, AccentLight, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}
