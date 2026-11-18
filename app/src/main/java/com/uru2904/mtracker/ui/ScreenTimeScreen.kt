package com.uru2904.mtracker.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.UiCache
import com.uru2904.mtracker.sheets.AuthStep
import com.uru2904.mtracker.sheets.SheetsSync
import com.uru2904.mtracker.usage.TrackedApp
import com.uru2904.mtracker.usage.UsageReader
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val SCREEN_LIMIT_MIN: Long get() = com.uru2904.mtracker.data.AppSettings.screenLimitMin
private val es = Locale("es", "AR")
private val Over = Danger
private val Near = Warn
private val Ok = Ok

/** Uso por día: celular y tablet, minutos por app. */
data class UsageData(
    val phone: Map<LocalDate, Map<TrackedApp, Long>>,
    val tablet: Map<LocalDate, Map<TrackedApp, Long>>,
    val tabletLinked: Boolean,
) {
    fun total(d: LocalDate) = (phone[d]?.values?.sum() ?: 0) + (tablet[d]?.values?.sum() ?: 0)
    fun app(d: LocalDate, a: TrackedApp) = (phone[d]?.get(a) ?: 0) + (tablet[d]?.get(a) ?: 0)
}

fun hmin(min: Long) = if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
fun usageColor(min: Long) = when { min > SCREEN_LIMIT_MIN -> Over; min > SCREEN_LIMIT_MIN * 0.8 -> Near; else -> Ok }

/** Lee el uso del celular (en vivo) y el de la tablet (de la planilla), y sube el del celular. */
suspend fun loadUsage(context: android.content.Context, db: AppDatabase, token: String?): UsageData {
    val local = UsageReader(context).daily(7)
    if (token == null) return UsageData(local, emptyMap(), false)
    val sync = SheetsSync(context, db)
    runCatching { sync.writeUsage(token, false, local) }
    val phoneSheet = runCatching { sync.readUsage(token, false) }.getOrDefault(emptyMap())
    val tablet = runCatching { sync.readUsage(token, true) }.getOrDefault(emptyMap())
    return UsageData(phoneSheet + local, tablet, tablet.isNotEmpty())
}

/** Pantalla de uso (estilo StayFree): barras de 14 días apiladas por app, límite de 3 h y detalle del día. */
@Composable
fun ScreenTimeScreen(db: AppDatabase, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { UsageReader(context) }
    var hasPerm by remember { mutableStateOf(reader.hasPermission()) }
    var data by remember { mutableStateOf(UiCache.usage as? UsageData) }
    var needsGoogle by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    val sync = remember { SheetsSync(context, db) }

    suspend fun load() {
        hasPerm = reader.hasPermission()
        val token = runCatching { (sync.authorize(context) as? AuthStep.Token)?.token }.getOrNull()
        needsGoogle = token == null
        data = loadUsage(context, db, token)
        UiCache.usage = data
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { scope.launch { load() } }
    LaunchedEffect(Unit) { load() }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (!hasPerm && reader.hasPermission()) scope.launch { load() }
        onPauseOrDispose { }
    }

    val days = (13 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }
    val d = data

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White,
                modifier = Modifier.clickable { onBack() }.padding(12.dp))
            Text("Pantalla", color = Color.White, fontSize = Type.Title, fontWeight = FontWeight.Bold)
        }

        // ---- Fijo: total del día seleccionado y gráfico ----
        val total = d?.total(selected) ?: 0
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!hasPerm) Row(
                Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Falta el permiso de acceso al uso", color = Near, fontSize = Type.Small, modifier = Modifier.weight(1f))
                Text("Activar", color = AccentLight, fontSize = Type.Body, modifier = Modifier.clickable {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }.padding(start = 12.dp))
            }
            if (needsGoogle) Row(
                Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sin conexión con la planilla: falta el uso de la tablet", color = Near, fontSize = Type.Small, modifier = Modifier.weight(1f))
                Text("Conectar", color = AccentLight, fontSize = Type.Body, modifier = Modifier.clickable {
                    scope.launch {
                        (runCatching { sync.authorize(context) }.getOrNull() as? AuthStep.NeedsConsent)?.let {
                            consent.launch(IntentSenderRequest.Builder(it.intent.intentSender).build())
                        } ?: load()
                    }
                }.padding(start = 12.dp))
            }

            Text(
                if (selected == LocalDate.now()) "Hoy" else DateTimeFormatter.ofPattern("EEEE d/M", es).format(selected),
                color = Muted, fontSize = Type.Small,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hmin(total), color = usageColor(total), fontSize = Type.Number, fontWeight = FontWeight.Bold)
                Text("  de ${SCREEN_LIMIT_MIN / 60} h", color = Muted, fontSize = Type.Body, modifier = Modifier.padding(bottom = 6.dp))
            }

            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val maxMin = maxOf(SCREEN_LIMIT_MIN * 4 / 3, days.maxOf { d?.total(it) ?: 0 })
                Row(Modifier.fillMaxWidth().height(140.dp)) {
                    Column(Modifier.fillMaxHeight().width(30.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                        Text("${maxMin / 60}h", color = Muted, fontSize = Type.Chart)
                        Text("0", color = Muted, fontSize = Type.Chart)
                    }
                    Canvas(
                        Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                            detectTapGestures { pos -> selected = days[(pos.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.lastIndex)] }
                        }
                    ) {
                        fun y(m: Long) = size.height * (1 - m.toFloat() / maxMin)
                        val n = days.size; val gap = 5.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
                        days.forEachIndexed { i, day ->
                            var acc = 0L
                            val alpha = if (day == selected) 1f else 0.55f
                            TrackedApp.entries.forEach { app ->
                                val m = d?.app(day, app) ?: 0
                                if (m > 0) {
                                    drawRect(Color(app.color).copy(alpha = alpha), Offset(i * (w + gap), y(acc + m)), Size(w, y(acc) - y(acc + m)))
                                    acc += m
                                }
                            }
                            if (day == selected) drawRoundRect(Color.White, Offset(i * (w + gap), size.height + 3), Size(w, 3f), CornerRadius(2f, 2f))
                        }
                        // Límite diario punteado
                        val ly = y(SCREEN_LIMIT_MIN); var xx = 0f
                        while (xx < size.width) { drawLine(Color(0xFFBBBBBB), Offset(xx, ly), Offset(xx + 6f, ly), 2f); xx += 12f }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 30.dp)) {
                    days.forEachIndexed { i, day ->
                        Text(if (i % 2 == 1) "${day.dayOfMonth}" else "", color = if (day == selected) Color.White else Muted,
                            fontSize = Type.Chart, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrackedApp.entries.forEach { app ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(Color(app.color), RoundedCornerShape(2.dp)))
                            Text(app.label, color = Muted, fontSize = Type.Chart, modifier = Modifier.padding(start = 3.dp))
                        }
                    }
                }
            }
        }

        // ---- Se desplaza: detalle por app ----
        RefreshBox(refreshing = pulling, onRefresh = { scope.launch { pulling = true; load(); pulling = false } }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
                    TrackedApp.entries.sortedByDescending { d?.app(selected, it) ?: 0 }.forEachIndexed { i, app ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Track))
                        val p = d?.phone?.get(selected)?.get(app) ?: 0
                        val t = d?.tablet?.get(selected)?.get(app) ?: 0
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(Color(app.color), RoundedCornerShape(3.dp)))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(app.label, color = Color.White, fontSize = Type.Body)
                                if (t > 0) Text("celular ${hmin(p)} · tablet ${hmin(t)}", color = Muted, fontSize = Type.Small)
                            }
                            Text(hmin(p + t), color = if (p + t > 0) Color.White else Muted, fontSize = Type.Body, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (d != null && !d.tabletLinked) Text(
                    "Para sumar la tablet: instalá MTracker en la tablet; se configura sola como tablet y sube su uso a la planilla.",
                    color = Muted, fontSize = Type.Small,
                )
                Text("Solo cuenta las apps (no el navegador). Android guarda el detalle unos 7 días; lo anterior sale de la planilla.",
                    color = Muted, fontSize = Type.Chart)
            }
        }
    }
}

/** Modo tablet: solo mide el uso de estas apps y lo sube a la planilla. */
@Composable
fun TabletScreen(db: AppDatabase, onUseAsPhone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { UsageReader(context) }
    val sync = remember { SheetsSync(context, db) }
    var hasPerm by remember { mutableStateOf(reader.hasPermission()) }
    var today by remember { mutableStateOf<Map<TrackedApp, Long>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun upload(token: String) {
        val daily = reader.daily(7)
        today = daily[LocalDate.now()].orEmpty()
        status = try { sync.writeUsage(token, true, daily); "Subido a la planilla a las " + java.time.LocalTime.now().withNano(0).withSecond(0) }
            catch (e: Exception) { "No se pudo subir: ${e.message}" }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch { runCatching { upload(sync.tokenFromConsent(res.data)) }.onFailure { status = "Google no dio permiso" }; busy = false }
    }
    suspend fun connectAndUpload() {
        busy = true
        hasPerm = reader.hasPermission()
        today = reader.daily(1)[LocalDate.now()].orEmpty()
        when (val st = runCatching { sync.authorize(context) }.getOrNull()) {
            is AuthStep.Token -> { upload(st.token); busy = false }
            is AuthStep.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(st.intent.intentSender).build())
            null -> { status = "No se pudo conectar con Google"; busy = false }
        }
    }
    LaunchedEffect(Unit) { connectAndUpload() }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (!hasPerm && reader.hasPermission()) scope.launch { connectAndUpload() }
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("MTracker · tablet", color = Color.White, fontSize = Type.Title, fontWeight = FontWeight.Bold)
        Text("En la tablet solo se mide el uso de X, Instagram, YouTube, Kick y Twitch. Se sube a la planilla cada 2 horas y el celular lo suma.",
            color = Muted, fontSize = Type.Body)
        if (!hasPerm) Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.fillMaxWidth(),
        ) { Text("Dar permiso de acceso al uso", color = Color.White) }
        val total = today.values.sum()
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hoy en la tablet", color = Muted, fontSize = Type.Small)
            Text(hmin(total), color = Color.White, fontSize = Type.Title, fontWeight = FontWeight.Bold)
            TrackedApp.entries.forEach { app ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(app.label, color = Soft, fontSize = Type.Body)
                    Text(hmin(today[app] ?: 0), color = Color.White, fontSize = Type.Body)
                }
            }
        }
        status?.let { Text(it, color = Muted, fontSize = Type.Small) }
        Button(
            onClick = { scope.launch { connectAndUpload() } }, enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Card), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Subiendo…" else "Subir ahora", color = Color.White) }
        Text("Usar como celular", color = AccentLight, fontSize = Type.Body, modifier = Modifier.clickable { onUseAsPhone() }.padding(vertical = 8.dp))
    }
}
