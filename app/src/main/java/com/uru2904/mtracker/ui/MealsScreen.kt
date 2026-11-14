package com.uru2904.mtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uru2904.mtracker.data.AppDatabase
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import com.uru2904.mtracker.sheets.AuthStep
import com.uru2904.mtracker.sheets.SheetMealParser
import com.uru2904.mtracker.sheets.SheetsSync
import com.uru2904.mtracker.data.DayTotals
import com.uru2904.mtracker.data.FoodItem
import com.uru2904.mtracker.data.MealEntry
import com.uru2904.mtracker.domain.ShiftCalendar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale("es", "AR")
private val MealTypes = listOf("Desayuno", "Almuerzo", "Merienda", "Cena", "Snack", "Alcohol")

@Composable
fun MealsScreen(db: AppDatabase) {
    val calendar = remember { ShiftCalendar() }
    val today = remember { calendar.logicalDate(LocalDateTime.now()) }
    var date by remember { mutableStateOf(today) }
    val key = date.toString()
    val meals by db.meals().forDay(key).collectAsState(initial = emptyList())
    val totals by db.meals().totals(key).collectAsState(initial = DayTotals(0.0, 0.0, 0.0, 0.0))
    val goal by db.goals().activeOn(today.toString()).collectAsState(initial = null)
    var adding by remember { mutableStateOf(false) }
    var showFoods by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<MealEntry?>(null) }
    val scope = rememberCoroutineScope()

    // Sincronización con la planilla (buzón del chat)
    val context = LocalContext.current
    val sync = remember { SheetsSync(context, db) }
    val health = remember { com.uru2904.mtracker.health.HealthRepository(context) }
    suspend fun fullSync(token: String) {
        sync.sync(token)
        runCatching { sync.writeGym(token) }
        // Resumen diario de Health Connect a la planilla (si hay permisos); un error acá no frena las comidas.
        runCatching {
            if (health.isAvailable() && health.grantedPermissions().containsAll(health.permissions)) {
                sync.writeDaily(token, health.dailySummaries(14))
            }
        }
    }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            syncMsg = try {
                fullSync(sync.tokenFromConsent(res.data)); null
            } catch (e: Exception) { "No se pudo sincronizar: ${e.message}" }
            syncing = false
        }
    }
    suspend fun doSync(interactive: Boolean) {
        syncing = true
        try {
            when (val st = sync.authorize(context as Activity)) {
                is AuthStep.Token -> { fullSync(st.token); syncMsg = null; syncing = false }
                is AuthStep.NeedsConsent ->
                    if (interactive) consent.launch(IntentSenderRequest.Builder(st.intent.intentSender).build())
                    else { syncMsg = "Deslizá hacia abajo para dar permiso a Google"; syncing = false }
            }
        } catch (e: Exception) {
            syncMsg = "No se pudo sincronizar: ${e.message}"; syncing = false
        }
    }
    LaunchedEffect(Unit) { if (sync.sheetId != null) doSync(interactive = false) }

    val kcalT = goal?.kcalTarget ?: 2250
    val protT = goal?.proteinTarget ?: 150
    val carbT = goal?.carbsTarget ?: 265
    val fatT = goal?.fatTarget ?: 65
    val drinks = meals.sumOf { it.drinks ?: 0.0 }

    // El encabezado queda fijo; al deslizar para actualizar solo baja la lista.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Soft, fontSize = 28.sp, modifier = Modifier.clickable { date = date.minusDays(1) }.padding(12.dp))
            Text(
                if (date == today) "Hoy" else DateTimeFormatter.ofPattern("EEE d MMM", es).format(date),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                "›", color = if (date < today) Soft else Color(0xFF333333), fontSize = 28.sp,
                modifier = Modifier.clickable(enabled = date < today) { date = date.plusDays(1) }.padding(12.dp),
            )
        }

        RefreshBox(refreshing = syncing, onRefresh = { scope.launch { doSync(interactive = true) } }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

        if (sync.sheetId == null || syncMsg != null) Row(
            Modifier.fillMaxWidth().background(Color(0xFF1A1205), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (sync.sheetId == null) "Planilla sin conectar" else syncMsg.orEmpty(),
                color = Color(0xFFF2C14E), fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            if (sync.sheetId == null) Text(
                "Conectar", color = AccentLight, fontSize = 13.sp,
                modifier = Modifier.clickable { scope.launch { doSync(interactive = true) } }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }

        Column(
            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Total del día", color = Muted, fontSize = 13.sp)
            Text("%,d / %,d kcal".format(es, totals.kcal.toInt(), kcalT), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Bar((totals.kcal / kcalT).toFloat())
            Text("Proteína ${num(totals.protein)} / $protT g", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Bar((totals.protein / protT).toFloat())
            Text("Carbohidratos ${num(totals.carbs)} / $carbT g · Grasas ${num(totals.fat)} / $fatT g", color = Soft, fontSize = 13.sp)
            if (drinks > 0) Text("Alcohol: ${num(drinks)} tragos", color = Soft, fontSize = 13.sp)
        }

        // Línea de tiempo (estilo MacroFactor): hora · comida + barra de macros · kcal. Deslizar a la izquierda borra.
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
            meals.forEachIndexed { idx, m ->
                if (idx > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(Color(0xFF1F1F1F)))
                MealRow(m, timeFmt.format(Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault()))) { toDelete = m }
            }
        }
        if (meals.isEmpty()) Text("Sin comidas cargadas.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))

        Button(
            onClick = { adding = true },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("+ Agregar comida", color = Color.White, fontSize = 16.sp) }
        Button(
            onClick = { showFoods = true },
            colors = ButtonDefaults.buttonColors(containerColor = Card),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Mis alimentos", color = Color.White, fontSize = 15.sp) }
    }
    }
    }

    toDelete?.let { m ->
        ConfirmDialog(
            title = "¿Borrar esta comida?",
            text = "\"${m.description}\" se borra de la app y de la planilla.",
            onConfirm = {
                scope.launch {
                    if (m.pendingOp == "add") db.meals().delete(m)
                    else db.meals().update(m.copy(pendingOp = "delete"))
                    doSync(interactive = false)
                }
            },
            onDismiss = { toDelete = null },
        )
    }

    if (showFoods) FoodsDialog(db) { showFoods = false }

    if (adding) {
        AddMealDialog(
            db = db,
            onDismiss = { adding = false },
            onSave = { meal, saveAsFood ->
                scope.launch {
                    val ts = if (date == today) System.currentTimeMillis()
                    else date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    db.meals().insert(
                        meal.copy(
                            timestamp = ts, logicalDate = date.toString(), source = "app",
                            remoteId = java.util.UUID.randomUUID().toString(), pendingOp = "add",
                        )
                    )
                    if (saveAsFood) {
                        db.foods().insert(
                            FoodItem(
                                name = meal.description, portion = "1 porción", kcal = meal.kcal,
                                protein = meal.protein, carbs = meal.carbs, fat = meal.fat, source = "propio",
                            )
                        )
                    }
                    adding = false
                    doSync(interactive = false)
                }
            },
        )
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF262626), RoundedCornerShape(4.dp))) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .background(if (fraction > 1.05f) Color(0xFFB45309) else Accent, RoundedCornerShape(4.dp))
        )
    }
}

private fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun field(value: String, label: String, onChange: (String) -> Unit, numeric: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = AccentLight, unfocusedBorderColor = Muted,
            focusedLabelColor = AccentLight, unfocusedLabelColor = Muted, cursorColor = Color.White,
        ),
        modifier = modifier,
    )
}

/** Un alimento dentro de la comida que se está armando. Valores por porción. */
private data class Item(
    val name: String, val portion: String,
    val kcal: Double, val protein: Double, val carbs: Double, val fat: Double,
    val qty: Double = 1.0,
)

/** Busca por cada palabra de 3+ letras y ordena por cuántas palabras coinciden. */
private suspend fun searchFoods(db: AppDatabase, q: String): List<FoodItem> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
    if (words.isEmpty()) return emptyList()
    val hits = words.flatMap { db.foods().search(it) }
    return hits.groupBy { it.id }.values.sortedByDescending { it.size }.map { it.first() }
}

@Composable
private fun AddMealDialog(db: AppDatabase, onDismiss: () -> Unit, onSave: (MealEntry, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(defaultType()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoodItem>>(emptyList()) }
    val items = remember { androidx.compose.runtime.mutableStateListOf<Item>() }
    var manual by remember { mutableStateOf(false) }
    var mName by remember { mutableStateOf("") }
    var mPortion by remember { mutableStateOf("") }
    var mKcal by remember { mutableStateOf("") }
    var mProt by remember { mutableStateOf("") }
    var mCarbs by remember { mutableStateOf("") }
    var mFat by remember { mutableStateOf("") }
    var drinks by remember { mutableStateOf("") }

    LaunchedEffect(query) { results = searchFoods(db, query) }

    val tKcal = items.sumOf { it.kcal * it.qty }
    val tProt = items.sumOf { it.protein * it.qty }
    val tCarbs = items.sumOf { it.carbs * it.qty }
    val tFat = items.sumOf { it.fat * it.qty }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Agregar comida", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

            MealTypes.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        val sel = t == type
                        Text(
                            t, color = if (sel) Color.White else Soft, fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                                .background(if (sel) Accent else Card, RoundedCornerShape(10.dp))
                                .clickable { type = t }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        )
                    }
                }
            }

            // Lista de alimentos de la comida
            Text(if (items.isEmpty()) "Sumá alimentos a esta comida" else "En esta comida", color = Muted, fontSize = 13.sp)
            items.forEachIndexed { idx, it ->
                Row(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(it.name, color = Color.White, fontSize = 15.sp)
                        Text("${num(it.qty)} × ${it.portion} · ${(it.kcal * it.qty).toInt()} kcal · P ${num(it.protein * it.qty)}", color = Muted, fontSize = 12.sp)
                    }
                    Text("−", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable {
                        if (it.qty <= 0.5) items.removeAt(idx) else items[idx] = it.copy(qty = it.qty - 0.5)
                    }.padding(horizontal = 12.dp, vertical = 4.dp))
                    Text("+", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable {
                        items[idx] = it.copy(qty = it.qty + 0.5)
                    }.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }

            // Buscador
            field(query, "Buscar alimento (ej: huevo, pan, leche)", { query = it })
            results.take(6).forEach { f ->
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp))
                        .clickable {
                            items.add(Item(f.name, f.portion, f.kcal, f.protein, f.carbs, f.fat))
                            query = ""
                        }
                        .padding(12.dp)
                ) {
                    Text("+ ${f.name}", color = Color.White, fontSize = 15.sp)
                    Text("${f.portion} · ${f.kcal.toInt()} kcal · P ${num(f.protein)}", color = Muted, fontSize = 12.sp)
                }
            }
            if (query.trim().length >= 3 && results.isEmpty()) {
                Text("No está en tus alimentos. Cargalo a mano abajo.", color = Muted, fontSize = 13.sp)
            }

            // Carga manual
            if (!manual) {
                Text(
                    "+ Cargar a mano", color = AccentLight, fontSize = 15.sp,
                    modifier = Modifier.clickable { manual = true; if (mName.isBlank()) mName = query }.padding(vertical = 6.dp),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Alimento nuevo (queda guardado para la próxima)", color = Soft, fontSize = 13.sp)
                    field(mName, "Nombre", { mName = it })
                    field(mPortion, "Porción (ej: 1 plato, 1 vaso)", { mPortion = it })
                    PortionChips { mPortion = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(mKcal, "kcal", { mKcal = it }, true, Modifier.weight(1f))
                        field(mProt, "Proteína g", { mProt = it }, true, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(mCarbs, "Carbos g", { mCarbs = it }, true, Modifier.weight(1f))
                        field(mFat, "Grasas g", { mFat = it }, true, Modifier.weight(1f))
                    }
                    val ok = mName.isNotBlank() && parse(mKcal) != null
                    Button(
                        onClick = {
                            val f = FoodItem(
                                name = mName.trim(), portion = mPortion.ifBlank { "1 porción" },
                                kcal = parse(mKcal) ?: 0.0, protein = parse(mProt) ?: 0.0,
                                carbs = parse(mCarbs) ?: 0.0, fat = parse(mFat) ?: 0.0, source = "propio",
                            )
                            scope.launch { db.foods().insert(f) }
                            items.add(Item(f.name, f.portion, f.kcal, f.protein, f.carbs, f.fat))
                            mName = ""; mPortion = ""; mKcal = ""; mProt = ""; mCarbs = ""; mFat = ""; manual = false
                        },
                        enabled = ok,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Color(0xFF222222)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (ok) "Sumar a la comida" else "Falta nombre y kcal", color = if (ok) Color.White else Muted) }
                }
            }

            if (type == "Alcohol") field(drinks, "Tragos", { drinks = it }, numeric = true)

            // Totales
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Total: ${tKcal.toInt()} kcal · P ${num(tProt)} g", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Carbos ${num(tCarbs)} g · Grasas ${num(tFat)} g", color = Muted, fontSize = 13.sp)
            }

            val valid = items.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Card),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Cancelar", color = Color.White) }
                Button(
                    onClick = {
                        val desc = items.joinToString(" + ") { if (it.qty == 1.0) it.name else "${num(it.qty)} ${it.name}" }
                        onSave(
                            MealEntry(
                                timestamp = 0, logicalDate = "", mealType = type, description = desc,
                                kcal = tKcal, protein = tProt, carbs = tCarbs, fat = tFat,
                                drinks = if (type == "Alcohol") parse(drinks) else null,
                            ),
                            false,
                        )
                    },
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Color(0xFF222222)),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (valid) "Guardar" else "Sumá un alimento", color = if (valid) Color.White else Muted) }
            }
        }
    }
}

/** Tipo de comida sugerido según la hora. */
private fun defaultType(): String = when (LocalTime.now().hour) {
    in 5..10 -> "Desayuno"
    in 11..14 -> "Almuerzo"
    in 15..18 -> "Merienda"
    in 19..23 -> "Cena"
    else -> "Snack"
}


private val PortionPresets = listOf("1 unidad", "1 vaso", "1 taza", "1 plato", "1 cucharada", "100 g")

/** Unidades caseras para cargar porciones sin pesar. */
@Composable
private fun PortionChips(onPick: (String) -> Unit) {
    PortionPresets.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { p ->
                Text(
                    p, color = Soft, fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                        .clickable { onPick(p) }
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                )
            }
        }
    }
}

/** Lista de todos los alimentos de la base, con búsqueda, edición y borrado. */
@Composable
private fun FoodsDialog(db: AppDatabase, onDismiss: () -> Unit) {
    val all by db.foods().all().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FoodItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val shown = all.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Mis alimentos (${all.size})", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Cerrar", color = AccentLight, fontSize = 15.sp, modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
            }
            field(filter, "Filtrar", { filter = it })
            Button(
                onClick = { creating = true },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Nuevo alimento", color = Color.White) }
            shown.forEach { f ->
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp))
                        .clickable { editing = f }
                        .padding(12.dp)
                ) {
                    Text(f.name, color = Color.White, fontSize = 15.sp)
                    Text(
                        "${f.portion} · ${f.kcal.toInt()} kcal · P ${num(f.protein)} · C ${num(f.carbs)} · G ${num(f.fat)}",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
            if (shown.isEmpty()) Text("No hay alimentos que coincidan.", color = Muted, fontSize = 14.sp)
        }
    }

    editing?.let { f -> FoodEditor(db, f) { editing = null } }
    if (creating) FoodEditor(db, null) { creating = false }
}

/** Editor de un alimento: crear (initial = null), modificar o borrar. */
@Composable
private fun FoodEditor(db: AppDatabase, initial: FoodItem?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var portion by remember { mutableStateOf(initial?.portion ?: "") }
    var kcal by remember { mutableStateOf(initial?.kcal?.let { num(it) } ?: "") }
    var prot by remember { mutableStateOf(initial?.protein?.let { num(it) } ?: "") }
    var carbs by remember { mutableStateOf(initial?.carbs?.let { num(it) } ?: "") }
    var fat by remember { mutableStateOf(initial?.fat?.let { num(it) } ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (initial == null) "Nuevo alimento" else "Editar alimento", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            field(name, "Nombre", { name = it })
            field(portion, "Porción (ej: 1 vaso, 1 plato)", { portion = it })
            PortionChips { portion = it }
            Text("Valores por porción", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(kcal, "kcal", { kcal = it }, true, Modifier.weight(1f))
                field(prot, "Proteína g", { prot = it }, true, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(carbs, "Carbos g", { carbs = it }, true, Modifier.weight(1f))
                field(fat, "Grasas g", { fat = it }, true, Modifier.weight(1f))
            }
            val ok = name.isNotBlank() && parse(kcal) != null
            Button(
                onClick = {
                    val item = FoodItem(
                        id = initial?.id ?: 0, name = name.trim(), portion = portion.ifBlank { "1 porción" },
                        kcal = parse(kcal) ?: 0.0, protein = parse(prot) ?: 0.0,
                        carbs = parse(carbs) ?: 0.0, fat = parse(fat) ?: 0.0,
                        source = initial?.source ?: "propio",
                    )
                    scope.launch {
                        if (initial == null) db.foods().insert(item) else db.foods().update(item)
                        onClose()
                    }
                },
                enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Color(0xFF222222)),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (ok) "Guardar" else "Falta nombre y kcal", color = if (ok) Color.White else Muted) }
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Card),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancelar", color = Color.White) }
            if (initial != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable { confirmDelete = true }.padding(vertical = 10.dp),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(20.dp))
                    Text("Borrar alimento", color = Color(0xFFF87171), fontSize = 15.sp)
                }
                if (confirmDelete) ConfirmDialog(
                    title = "¿Borrar este alimento?",
                    text = "\"${initial.name}\" se borra de tus alimentos.",
                    onConfirm = { scope.launch { db.foods().delete(initial); onClose() } },
                    onDismiss = { confirmDelete = false },
                )
                Text("Borrar un alimento no cambia las comidas que ya cargaste con él.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}


private val ProtColor = Color(0xFF93B4FF)
private val CarbColor = Color(0xFFF2C14E)
private val FatColor = Color(0xFFE879A6)

/** Fila de la línea de tiempo. Deslizar hacia la izquierda pide confirmación para borrar. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MealRow(m: MealEntry, time: String, onDelete: () -> Unit) {
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) onDelete()
            false // vuelve a su lugar; el borrado lo confirma el diálogo
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(Color(0xFF3A1212)).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(Icons.Outlined.Delete, contentDescription = "Borrar", tint = Color(0xFFF87171)) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().background(Card).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(50.dp)) {
                Text(time, color = Soft, fontSize = 13.sp)
                if (m.pendingOp == "add") Box(Modifier.padding(top = 6.dp).size(6.dp).background(Color(0xFFB45309), RoundedCornerShape(3.dp)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(m.mealType, color = Muted, fontSize = 11.sp)
                Text(m.description, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                MacroBar(m.protein, m.carbs, m.fat)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("P ${num(m.protein)}", color = ProtColor, fontSize = 11.sp)
                    Text("C ${num(m.carbs)}", color = CarbColor, fontSize = 11.sp)
                    Text("G ${num(m.fat)}", color = FatColor, fontSize = 11.sp)
                }
            }
            Text("${m.kcal.toInt()}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** Barra fina con la proporción de calorías que aporta cada macro (P y C 4 kcal/g, G 9 kcal/g). */
@Composable
private fun MacroBar(p: Double, c: Double, f: Double) {
    val kp = p * 4; val kc = c * 4; val kf = f * 9
    val total = (kp + kc + kf).takeIf { it > 0 } ?: return
    Row(Modifier.fillMaxWidth().height(4.dp)) {
        listOf(kp to ProtColor, kc to CarbColor, kf to FatColor).forEach { (v, col) ->
            if (v > 0) Box(Modifier.weight((v / total).toFloat()).fillMaxHeight().background(col))
        }
    }
}
