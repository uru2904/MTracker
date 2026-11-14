package com.uru2904.mtracker.ui

import android.Manifest
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
    val scope = rememberCoroutineScope()
    val scanner = remember { ScaleScanner(context) }
    val health = remember { HealthRepository(context) }
    var live by remember { mutableStateOf<ScaleReading?>(null) }
    var status by remember { mutableStateOf("Subite a la balanza") }
    var scanning by remember { mutableStateOf(false) }
    var lastSavedKg by remember { mutableStateOf<Double?>(null) }
    var lastSavedAt by remember { mutableStateOf(0L) }
    val raw = remember { mutableStateListOf<String>() }
    var showRaw by remember { mutableStateOf(false) }
    val recent by db.weights().since(System.currentTimeMillis() - 30L * 86_400_000).collectAsState(initial = emptyList())

    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun save(r: ScaleReading) {
        val now = System.currentTimeMillis()
        // Una sola vez por pesada: ignora repeticiones del mismo valor dentro de 2 minutos.
        if (lastSavedKg == r.kg && now - lastSavedAt < 120_000) return
        lastSavedKg = r.kg; lastSavedAt = now
        scope.launch {
            db.weights().insert(WeightEntry(timestamp = now, kg = r.kg, source = "balanza"))
            val hc = runCatching { health.writeWeight(r.kg) }.isSuccess
            status = "Guardado: ${num(r.kg)} kg" + if (hc) " (también en Health Connect)" else ""
        }
    }

    fun startScan() {
        if (!scanner.bluetoothOn) { status = "Prendé el Bluetooth del celular"; return }
        scanner.start(
            onReading = { r ->
                live = r
                when {
                    r.removed -> status = "Te bajaste de la balanza"
                    r.stabilized -> save(r)
                    else -> status = "Midiendo…"
                }
            },
            onRaw = { line -> if (raw.size > 30) raw.removeAt(0); raw.add(line) },
        )
        scanning = true
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) startScan() else status = "Sin permiso de dispositivos cercanos no puedo leer la balanza"
    }

    LaunchedEffect(Unit) { launcher.launch(perms) }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Peso", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Column(
            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                live?.let { "${num(it.kg)} kg" } ?: "– – kg",
                color = if (live?.stabilized == true) Color.White else Soft,
                fontSize = 52.sp, fontWeight = FontWeight.Bold,
            )
            Text(status, color = Muted, fontSize = 14.sp)
            if (!scanning) Text("Buscando la balanza…", color = Muted, fontSize = 12.sp)
        }

        if (!scanning) Button(
            onClick = { launcher.launch(perms) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Empezar a escuchar la balanza", color = Color.White) }

        Text("Últimos 30 días", color = Muted, fontSize = 13.sp)
        val fmt = DateTimeFormatter.ofPattern("EEE d/M HH:mm", Locale("es", "AR"))
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp))) {
            recent.reversed().take(10).forEachIndexed { i, w ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(Color(0xFF1F1F1F)))
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt.format(Instant.ofEpochMilli(w.timestamp).atZone(ZoneId.systemDefault())), color = Soft, fontSize = 14.sp)
                    Text("${num(w.kg)} kg", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (recent.isEmpty()) Text("Todavía no hay pesadas.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
        }

        // Diagnóstico: datos crudos de Bluetooth, para ajustar si la balanza no se reconoce.
        Text(
            if (showRaw) "Ocultar datos técnicos" else "Ver datos técnicos (${raw.size})",
            color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp).clickable { showRaw = !showRaw },
        )
        if (showRaw) raw.reversed().forEach { Text(it, color = Color(0xFF6B6B6B), fontSize = 11.sp) }
    }
}
