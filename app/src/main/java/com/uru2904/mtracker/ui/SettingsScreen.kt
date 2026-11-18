package com.uru2904.mtracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.data.AppSettings
import com.uru2904.mtracker.data.GoalPeriod
import com.uru2904.mtracker.data.Live
import com.uru2904.mtracker.domain.EnergyModel
import com.uru2904.mtracker.domain.Shift
import com.uru2904.mtracker.domain.ShiftConfig
import com.uru2904.mtracker.domain.SleepWindow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale("es", "AR")
private val dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val Dark = darkColorScheme(primary = AccentLight, onPrimary = Color.Black, surface = Color(0xFF161616), surfaceContainerHigh = Color(0xFF161616))

private val shiftNames = mapOf(Shift.TM to "Mañana (TM)", Shift.TT to "Tarde (TT)", Shift.TN to "Noche (TN)")
private val rotations = listOf(listOf(Shift.TT, Shift.TM, Shift.TN), listOf(Shift.TT, Shift.TN, Shift.TM))
private fun rotationLabel(r: List<Shift>) = r.joinToString(" → ") { it.name }
private fun range(w: SleepWindow) = "${w.start} – ${w.end}"

/** Qué editor está abierto. */
private sealed interface Editor {
    data class Number(val title: String, val initial: Double, val suffix: String, val decimals: Boolean, val min: Double, val max: Double,
                      val hint: String? = null, val save: (Double) -> Unit) : Editor
    data class Text(val title: String, val initial: String, val save: (String) -> Unit) : Editor
    data class Date(val title: String, val initial: LocalDate, val save: (LocalDate) -> Unit) : Editor
    data class Choice(val title: String, val options: List<String>, val selected: Int, val save: (Int) -> Unit) : Editor
    data class Time(val title: String, val initial: LocalTime, val save: (LocalTime) -> Unit) : Editor
    data class Range(val title: String, val initial: SleepWindow, val save: (SleepWindow) -> Unit) : Editor
}

/** Ajustes estilo MacroFactor/Whoop: lista por secciones; cada fila abre un editor. */
@Composable
fun SettingsScreen(db: AppDatabase, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val goal by Live.goal(db, today.toString()).collectAsState()
    var shifts by remember { mutableStateOf(AppSettings.shifts) }
    var loss by remember { mutableStateOf(AppSettings.lossKgPerWeek) }
    var limit by remember { mutableStateOf(AppSettings.screenLimitMin) }
    var editor by remember { mutableStateOf<Editor?>(null) }

    fun saveShifts(c: ShiftConfig) { AppSettings.shifts = c; shifts = c }
    fun saveGoal(f: (GoalPeriod) -> GoalPeriod) {
        val g = goal ?: return
        scope.launch {
            val n = f(g)
            db.goals().update(n.copy(carbsTarget = EnergyModel.carbsFor(n.kcalTarget, n.proteinTarget, n.fatTarget)))
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Encabezado fijo
        Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White) }
            Text("Ajustes", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            val g = goal
            if (g != null) {
                Section("Objetivo") {
                    Item("Nombre", g.name) { editor = Editor.Text("Nombre del objetivo", g.name) { v -> saveGoal { it.copy(name = v) } } }
                    Item("Peso objetivo", g.targetWeightKg?.let { "${num(it)} kg" } ?: "—") {
                        editor = Editor.Number("Peso objetivo", g.targetWeightKg ?: 70.0, "kg", true, 40.0, 150.0) { v -> saveGoal { it.copy(targetWeightKg = v) } }
                    }
                    Item("Fecha", LocalDate.parse(g.endDate).format(dmy)) {
                        editor = Editor.Date("Fecha del objetivo", LocalDate.parse(g.endDate)) { d -> saveGoal { it.copy(endDate = d.toString()) } }
                    }
                    Item("Ritmo de bajada", "${num(loss)} kg/sem") {
                        editor = Editor.Number("Ritmo de bajada", loss, "kg por semana", true, 0.1, 1.0,
                            hint = "Define las calorías que propone el check-in de los lunes. Más de 0,5 kg/sem hace más difícil sumar músculo.") { v ->
                            AppSettings.lossKgPerWeek = v; loss = AppSettings.lossKgPerWeek
                        }
                    }
                }
                Section("Nutrición (por día)") {
                    Item("Calorías", "%,d kcal".format(es, g.kcalTarget)) {
                        editor = Editor.Number("Calorías", g.kcalTarget.toDouble(), "kcal", false, 1200.0, 5000.0,
                            hint = "El check-in de los lunes la puede ajustar. Los carbohidratos se recalculan solos.") { v -> saveGoal { it.copy(kcalTarget = v.toInt()) } }
                    }
                    Item("Proteína", "${g.proteinTarget} g") {
                        editor = Editor.Number("Proteína", g.proteinTarget.toDouble(), "g", false, 50.0, 300.0) { v -> saveGoal { it.copy(proteinTarget = v.toInt()) } }
                    }
                    Item("Grasas", "${g.fatTarget} g") {
                        editor = Editor.Number("Grasas", g.fatTarget.toDouble(), "g", false, 20.0, 200.0) { v -> saveGoal { it.copy(fatTarget = v.toInt()) } }
                    }
                    Item("Carbohidratos", "${g.carbsTarget} g", sub = "Automático: lo que queda de las calorías", onClick = null)
                }
            }

            val cal = remember(shifts) { com.uru2904.mtracker.domain.ShiftCalendar(shifts) }
            val current = cal.shiftForWeekOf(today)
            Section("Turnos") {
                Item("Esta semana", shiftNames.getValue(current)) {
                    val opts = Shift.values().toList()
                    editor = Editor.Choice("Turno de esta semana", opts.map { shiftNames.getValue(it) }, opts.indexOf(current)) { i ->
                        saveShifts(shifts.withCurrent(today, opts[i]))
                    }
                }
                Item("Rotación", rotationLabel(shifts.rotation)) {
                    editor = Editor.Choice("Orden de la rotación", rotations.map(::rotationLabel), rotations.indexOf(shifts.rotation).coerceAtLeast(0)) { i ->
                        // Se mantiene el turno de esta semana; cambia lo que viene después.
                        saveShifts(shifts.copy(rotation = rotations[i]).withCurrent(today, current))
                    }
                }
            }
            Section("Horario de cada turno") {
                Shift.values().forEach { s ->
                    Item(shiftNames.getValue(s), range(cal.hours(s))) {
                        editor = Editor.Range("Horario ${shiftNames.getValue(s)}", cal.hours(s)) { w -> saveShifts(shifts.copy(hours = shifts.hours + (s to w))) }
                    }
                }
            }
            Section("Sueño esperado por turno") {
                Shift.values().forEach { s ->
                    val w = shifts.sleep.getValue(s)
                    Item(shiftNames.getValue(s), range(w)) {
                        editor = Editor.Range("Sueño en ${shiftNames.getValue(s)}", w) { n -> saveShifts(shifts.copy(sleep = shifts.sleep + (s to n))) }
                    }
                }
            }
            Section("Cambio de día") {
                Item("Semana de noche", shifts.cutoffNight.toString(), sub = "Lo que comés antes de esta hora cuenta para el día anterior") {
                    editor = Editor.Time("Cambio de día (semana TN)", shifts.cutoffNight) { t -> saveShifts(shifts.copy(cutoffNight = t)) }
                }
                Item("Otras semanas", shifts.cutoffOther.toString()) {
                    editor = Editor.Time("Cambio de día (TM y TT)", shifts.cutoffOther) { t -> saveShifts(shifts.copy(cutoffOther = t)) }
                }
            }
            Section("Pantalla") {
                Item("Límite diario", "${num(limit / 60.0)} h", sub = "X, Instagram, YouTube, Kick y Twitch (celular + tablet)") {
                    editor = Editor.Number("Límite diario de pantalla", limit / 60.0, "horas", true, 0.5, 12.0) { v ->
                        AppSettings.screenLimitMin = Math.round(v * 60); limit = AppSettings.screenLimitMin
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editor?.let { e -> MaterialTheme(colorScheme = Dark) { EditorDialog(e) { editor = null } } }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp))) { content() }
    }
}

@Composable
private fun Item(label: String, value: String, sub: String? = null, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 12.sp)
        }
        Text(value, color = Soft, fontSize = 15.sp)
        Text(if (onClick != null) "  ›" else "   ", color = Muted, fontSize = 18.sp)
    }
    HorizontalDivider(color = Color(0xFF1C1C1C), thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDialog(e: Editor, close: () -> Unit) {
    when (e) {
        is Editor.Number -> {
            var text by remember { mutableStateOf(if (e.decimals) num(e.initial) else e.initial.toInt().toString()) }
            val value = text.replace(',', '.').toDoubleOrNull()
            val ok = value != null && value >= e.min && value <= e.max
            SimpleDialog(e.title, ok, onSave = { e.save(value!!); close() }, close) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, suffix = { Text(e.suffix) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (e.decimals) KeyboardType.Decimal else KeyboardType.Number),
                    isError = !ok, colors = fieldColors(),
                    supportingText = { Text(if (!ok) "Entre ${num(e.min)} y ${num(e.max)}" else e.hint ?: "") },
                )
            }
        }
        is Editor.Text -> {
            var text by remember { mutableStateOf(e.initial) }
            SimpleDialog(e.title, text.isNotBlank(), onSave = { e.save(text.trim()); close() }, close) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, colors = fieldColors())
            }
        }
        is Editor.Choice -> {
            var sel by remember { mutableStateOf(e.selected) }
            SimpleDialog(e.title, true, onSave = { e.save(sel); close() }, close) {
                Column {
                    e.options.forEachIndexed { i, o ->
                        Row(Modifier.fillMaxWidth().clickable { sel = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sel == i, onClick = { sel = i }, colors = RadioButtonDefaults.colors(selectedColor = AccentLight))
                            Text(o, color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
        is Editor.Date -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = e.initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                selectableDates = object : androidx.compose.material3.SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now())
                },
            )
            DatePickerDialog(
                onDismissRequest = close,
                confirmButton = {
                    TextButton(enabled = state.selectedDateMillis != null, onClick = {
                        state.selectedDateMillis?.let { e.save(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; close()
                    }) { Text("Guardar") }
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar", color = Color.White) } },
            ) { DatePicker(state = state, title = null) }
        }
        is Editor.Time -> TimeDialog(e.title, e.initial, { e.save(it); close() }, close)
        is Editor.Range -> {
            var start by remember { mutableStateOf(e.initial.start) }
            var end by remember { mutableStateOf(e.initial.end) }
            var picking by remember { mutableStateOf<Int?>(null) }
            SimpleDialog(e.title, start != end, onSave = { e.save(SleepWindow(start, end)); close() }, close) {
                Column {
                    listOf("Desde" to start, "Hasta" to end).forEachIndexed { i, (l, t) ->
                        Row(Modifier.fillMaxWidth().clickable { picking = i }.padding(vertical = 12.dp)) {
                            Text(l, color = Soft, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(t.toString(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            picking?.let { i ->
                TimeDialog(if (i == 0) "Desde" else "Hasta", if (i == 0) start else end, { t -> if (i == 0) start = t else end = t; picking = null }) { picking = null }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initial: LocalTime, onSave: (LocalTime) -> Unit, close: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    SimpleDialog(title, true, onSave = { onSave(LocalTime.of(state.hour, state.minute)) }, close) { TimePicker(state = state) }
}

@Composable
private fun SimpleDialog(title: String, canSave: Boolean, onSave: () -> Unit, close: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        containerColor = Color(0xFF161616), titleContentColor = Color.White, textContentColor = Soft,
        title = { Text(title) },
        text = content,
        confirmButton = { TextButton(enabled = canSave, onClick = onSave) { Text("Guardar", color = if (canSave) AccentLight else Muted) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancelar", color = Color.White) } },
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = AccentLight, unfocusedBorderColor = Muted,
    cursorColor = AccentLight, focusedSuffixColor = Soft, unfocusedSuffixColor = Soft,
)
