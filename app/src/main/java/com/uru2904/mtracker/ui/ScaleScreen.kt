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
    val raw = remember { mutableStateListOf<String>() }
    var showRaw by remember { mutableStateOf(false) }
    var weighed by remember { mutableStateOf<WeightEntry?>(null) }
    val recent by db.weights().since(System.currentTimeMillis() - 30L * 86_400_000).collectAsState(initial = emptyList())

    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // Bloqueo: después de guardar una pesada no se ofrece otra por 3 horas (evita lecturas accidentales).
    val prefs = remember { context.getSharedPreferences("scale", android.content.Context.MODE_PRIVATE) }
    var lockUntil by remember { mutableStateOf(prefs.getLong("lock_until", 0L)) }
    var pending by remember { mutableStateOf<ScaleReading?>(null) }
    var dismissedKg by remember { mutableStateOf<Double?>(null) }
    var toDelete by remember { mutableStateOf<WeightEntry?>(null) }
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

    fun startScan() {
        if (!scanner.bluetoothOn) { status = "Prendé el Bluetooth del celular"; return }
        scanner.start(
            onReading = { r ->
                live = r
                when {
                    r.removed -> status = "Te bajaste de la balanza"
                    r.stabilized -> offer(r)
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

        pending?.let { r ->
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("¿Guardar ${num(r.kg)} kg?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { dismissedKg = r.kg; pending = null; status = "Descartado" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
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
                    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(lockUntil).atZone(ZoneId.systemDefault())),
                color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            Text(
                "Desbloquear", color = AccentLight, fontSize = 13.sp,
                modifier = Modifier.clickable {
                    lockUntil = 0L; prefs.edit().putLong("lock_until", 0L).apply(); dismissedKg = null
                }.padding(start = 10.dp),
            )
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
                SwipeToDelete(onDelete = { toDelete = w }) {
                    Row(Modifier.fillMaxWidth().background(Card).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt.format(Instant.ofEpochMilli(w.timestamp).atZone(ZoneId.systemDefault())), color = Soft, fontSize = 14.sp)
                        Text("${num(w.kg)} kg", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
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

    weighed?.let { w -> WeighInDialog(db, w) { weighed = null } }

    toDelete?.let { w ->
        ConfirmDialog(
            title = "¿Borrar esta pesada?",
            text = "${num(w.kg)} kg se borra de la app y de Health Connect.",
            onConfirm = {
                scope.launch {
                    db.weights().delete(w)
                    runCatching { health.deleteWeight(Instant.ofEpochMilli(w.timestamp)) }
                }
            },
            onDismiss = { toDelete = null },
        )
    }
}
