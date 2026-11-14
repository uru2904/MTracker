package com.uru2904.mtracker.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Detalle de una pesada (estilo Renpho): peso + composición corporal + medidas.
 * Si ese día no hay composición o medidas, muestra las últimas anteriores y aclara de cuándo son.
 */
@Composable
fun WeightDetailDialog(db: AppDatabase, w: WeightEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val health = remember { HealthRepository(context) }
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(w.timestamp)
    val day = at.atZone(zone).toLocalDate()
    val es = Locale("es", "AR")

    var loading by remember { mutableStateOf(true) }
    var comp by remember { mutableStateOf<Composition?>(null) }
    var measures by remember { mutableStateOf<List<MeasurementEntry>>(emptyList()) }
    var measuresDate by remember { mutableStateOf<String?>(null) }
    var types by remember { mutableStateOf<List<MeasurementType>>(emptyList()) }

    LaunchedEffect(w.id) {
        // Composición: la última hasta 2 horas después de la pesada (se mide justo después de pesarse).
        comp = runCatching { health.compositionUntil(at.plus(Duration.ofHours(2))) }.getOrNull()
        measuresDate = db.measurements().lastDateUntil(day.toString())
        measures = measuresDate?.let { db.measurements().onDate(it) }.orEmpty()
        types = db.measurements().allTypes()
        loading = false
    }

    fun whenText(d: LocalDate): String? = if (d == day) null else "Del " + DateTimeFormatter.ofPattern("EEE d/M", es).format(d)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, HH:mm", es).format(at.atZone(zone)), color = Muted, fontSize = 14.sp)
                Text("Cerrar", color = AccentLight, fontSize = 15.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Text("${num(w.kg)} kg", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)

            if (loading) {
                CircularProgressIndicator(color = Soft, strokeWidth = 2.dp)
                return@Column
            }

            // Composición corporal
            Section("Composición corporal", comp?.let { whenText(it.at.atZone(zone).toLocalDate()) }) {
                val c = comp
                if (c == null) Text("Todavía no hay mediciones con el reloj.", color = Muted, fontSize = 14.sp)
                else {
                    Metric("Grasa corporal", c.bodyFatPct?.let { "${num(Math.round(it * 10) / 10.0)} %" })
                    c.leanKg?.let { Metric("Masa magra", "${num(Math.round(it * 10) / 10.0)} kg") }
                }
            }

            // Medidas
            Section("Medidas", measuresDate?.let { whenText(LocalDate.parse(it)) }) {
                if (measures.isEmpty()) Text("Todavía no hay medidas cargadas.", color = Muted, fontSize = 14.sp)
                else types.filter { t -> measures.any { it.typeId == t.id } }.forEach { t ->
                    Metric(t.name, measures.first { it.typeId == t.id }.cm.let { "${num(it)} cm" })
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, note: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, color = Color(0xFFF2C14E), fontSize = 12.sp) }
        }
        content()
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Soft, fontSize = 15.sp)
        Text(value ?: "–", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F1F1F)))
}
