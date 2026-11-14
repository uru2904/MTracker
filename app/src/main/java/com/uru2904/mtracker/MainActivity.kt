package com.uru2904.mtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Accent = Color(0xFF1E3A8A)
private val Card = Color(0xFF111111)
private val Muted = Color(0xFF8A8A8A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val health = HealthRepository(this)
        setContent { HealthCheckScreen(health) }
    }
}

private data class HealthData(
    val steps: Long,
    val sleep: SleepSummary,
    val exercises: List<String>,
)

@Composable
private fun HealthCheckScreen(health: HealthRepository) {
    val scope = rememberCoroutineScope()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val today = LocalDate.now()
        val shift = calendar.shiftForWeekOf(today)
        val sleepWin = calendar.expectedSleep(today)
        Text("MTracker", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Turno de la semana: ${shift.name} · Sueño esperado ${sleepWin.start}–${sleepWin.end}",
            color = Muted, fontSize = 13.sp,
        )

        if (!available) {
            Box("Health Connect no está disponible en este teléfono.")
            return@Column
        }

        val missing = granted?.let { health.permissions - it }.orEmpty()
        if (granted == null || missing.isNotEmpty()) {
            Box(
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
            Box("Pasos hoy", "%,d".format(Locale("es", "AR"), d.steps))
            Box(
                "Sueño últimas 24 h",
                hm(d.sleep.total),
                "Siesta: " + "%.1f h".format(Locale("es", "AR"), d.sleep.nap.toMinutes() / 60.0),
            )
            Box(
                "Entrenamientos últimos 7 días",
                if (d.exercises.isEmpty()) "Sin datos" else d.exercises.joinToString("\n"),
            )
        }

        error?.let { Box("Error al leer Health Connect", it) }

        Button(
            onClick = { scope.launch { refresh() } },
            colors = ButtonDefaults.buttonColors(containerColor = Card),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Actualizar", color = Color.White) }
    }
}

@Composable
private fun Box(title: String, value: String? = null, note: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = if (value == null) Color.White else Muted, fontSize = 13.sp)
        value?.let { Text(it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        note?.let { Text(it, color = Muted, fontSize = 13.sp) }
    }
}

private fun hm(d: Duration): String = "${d.toHours()} h ${d.toMinutesPart()} min"
