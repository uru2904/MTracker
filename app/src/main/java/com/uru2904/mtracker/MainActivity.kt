package com.uru2904.mtracker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.ui.Accent
import com.uru2904.mtracker.ui.Card
import com.uru2904.mtracker.ui.InfoCard
import com.uru2904.mtracker.ui.MealsScreen
import com.uru2904.mtracker.ui.Muted
import com.uru2904.mtracker.ui.TabBar
import com.uru2904.mtracker.ui.Tab
import com.uru2904.mtracker.data.FoodSeed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.uru2904.mtracker.gym.HevyImporter
import java.time.Instant
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.uru2904.mtracker.domain.ShiftCalendar
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.health.SleepSummary
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


class MainActivity : ComponentActivity() {
    private val sharedUri: MutableState<Uri?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val health = HealthRepository(this)
        val db = AppDatabase.get(this)
        handleIntent(intent)
        lifecycleScope.launch {
            FoodSeed.ensure(db)
            runCatching { FoodSeed.loadArgenfood(db, this@MainActivity) }
        }
        setContent {
            var tab by remember { mutableStateOf(Tab.Hoy) }
            LaunchedEffect(sharedUri.value) { if (sharedUri.value != null) tab = Tab.Hoy }
            Column(
                Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()
            ) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Hoy -> HealthCheckScreen(health, db, sharedUri)
                        Tab.Comidas -> MealsScreen(db)
                        Tab.Cuerpo -> com.uru2904.mtracker.ui.ScaleScreen(db)
                        else -> InfoCard("${tab.label}: próximamente")
                    }
                }
                TabBar(tab) { tab = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        sharedUri.value = uri
    }
}

private data class HealthData(
    val steps: Long,
    val sleep: SleepSummary,
    val exercises: List<String>,
)

@Composable
private fun HealthCheckScreen(health: HealthRepository, db: AppDatabase, sharedUri: MutableState<Uri?>) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var importMsg by remember { mutableStateOf<String?>(null) }
    val lastWorkout by db.workouts().last().collectAsState(initial = null)

    suspend fun importUri(uri: Uri) {
        importMsg = try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("No se pudo abrir el archivo")
            val r = HevyImporter(db).import(text)
            "Hevy: ${r.added} sesiones nuevas, ${r.skipped} ya estaban."
        } catch (e: Exception) {
            "No se pudo importar: ${e.message}"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { importUri(uri) }
    }

    LaunchedEffect(sharedUri.value) {
        sharedUri.value?.let { importUri(it); sharedUri.value = null }
    }
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    var data by remember { mutableStateOf<HealthData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val available = remember { health.isAvailable() }
    val calendar = remember { ShiftCalendar() }

    suspend fun refresh() {
        try {
            granted = health.grantedPermissions()
            if (granted!!.isNotEmpty()) {
                val fmt = DateTimeFormatter.ofPattern("EEE d/M HH:mm", Locale("es", "AR"))
                data = HealthData(
                    steps = health.stepsToday(),
                    sleep = health.sleepLast24h(),
                    exercises = health.recentExercises().take(5).map {
                        val start = it.startTime.atZone(ZoneId.systemDefault())
                        val min = Duration.between(it.startTime, it.endTime).toMinutes()
                        "${fmt.format(start)} · ${HealthRepository.exerciseName(it.exerciseType)} · $min min"
                    },
                )
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { scope.launch { refresh() } }

    LaunchedEffect(Unit) { if (available) refresh() }
    var refreshing by remember { mutableStateOf(false) }

    // El encabezado queda fijo; al deslizar para actualizar solo bajan las tarjetas.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 8.dp)) {
        val today = LocalDate.now()
        val shift = calendar.shiftForWeekOf(today)
        val sleepWin = calendar.expectedSleep(today)
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text("MTracker", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("  v$version", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(
            "Turno de la semana: ${shift.name} · Sueño esperado ${sleepWin.start}–${sleepWin.end}",
            color = Muted, fontSize = 13.sp,
        )
    }
    com.uru2904.mtracker.ui.RefreshBox(
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; refresh(); refreshing = false } },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        if (!available) {
            InfoCard("Health Connect no está disponible en este teléfono.")
            return@Column
        }

        val missing = granted?.let { health.permissions - it }.orEmpty()
        if (granted == null || missing.isNotEmpty()) {
            InfoCard(
                if (granted.isNullOrEmpty()) "Falta dar permisos de Health Connect."
                else "Faltan ${missing.size} permisos de Health Connect."
            )
            Button(
                onClick = { launcher.launch(health.permissions) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Conectar Health Connect", color = Color.White) }
        }

        data?.let { d ->
            InfoCard("Pasos hoy", "%,d".format(Locale("es", "AR"), d.steps))
            InfoCard(
                "Sueño últimas 24 h",
                hm(d.sleep.total),
                "Siesta: " + "%.1f h".format(Locale("es", "AR"), d.sleep.nap.toMinutes() / 60.0),
            )
            InfoCard(
                "Entrenamientos últimos 7 días",
                if (d.exercises.isEmpty()) "Sin datos" else d.exercises.joinToString("\n"),
            )
        }

        error?.let { InfoCard("Error al leer Health Connect", it) }

        val fmtDay = DateTimeFormatter.ofPattern("EEE d/M", Locale("es", "AR"))
        InfoCard(
            "Último gym (Hevy)",
            lastWorkout?.let { it.routine } ?: "Sin datos todavía",
            lastWorkout?.let {
                fmtDay.format(Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault())) + " · ${it.durationMin} min"
            },
        )
        importMsg?.let { InfoCard(it) }
        Button(
            onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Importar CSV de Hevy", color = Color.White) }

    }
    }
    }
}

private fun hm(d: Duration): String = "${d.toHours()} h ${d.toMinutesPart()} min"
