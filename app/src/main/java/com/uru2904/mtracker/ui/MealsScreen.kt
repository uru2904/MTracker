package com.uru2904.mtracker.ui

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
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
    val calendar = remember { com.uru2904.mtracker.data.AppSettings.calendar() }
    val today = remember { calendar.logicalDate(LocalDateTime.now()) }
    var date by remember { mutableStateOf(today) }
    val key = date.toString()
    val meals by com.uru2904.mtracker.data.Live.meals(db, key).collectAsState()
    val totals by com.uru2904.mtracker.data.Live.totals(db, key).collectAsState()
    val goal by com.uru2904.mtracker.data.Live.goal(db, today.toString()).collectAsState()
    var adding by remember { mutableStateOf(false) }
    // El botón "+ Comida" del widget abre directo la carga.
    val addReq = com.uru2904.mtracker.data.Nav.addMeal.intValue
    LaunchedEffect(addReq) {
        if (addReq > com.uru2904.mtracker.data.Nav.addMealHandled) { com.uru2904.mtracker.data.Nav.addMealHandled = addReq; adding = true }
    }
    var showFoods by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<MealEntry?>(null) }
    var editingMeal by remember { mutableStateOf<MealEntry?>(null) }
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()

    // Sincronización con la planilla (buzón del chat)
    val context = LocalContext.current
    val sync = remember { SheetsSync(context, db) }
    val health = remember { com.uru2904.mtracker.health.HealthRepository(context) }
    suspend fun fullSync(token: String) = sync.syncAll(token, health)
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
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
    // Al volver de Claude (u otra app) trae lo cargado por chat.
    var resumedOnce by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (resumedOnce && sync.sheetId != null) scope.launch { doSync(interactive = false) }
        resumedOnce = true
        onPauseOrDispose { }
    }

    val kcalT = goal?.kcalTarget ?: 2250
    val protT = goal?.proteinTarget ?: 150
    val carbT = goal?.carbsTarget ?: 265
    val fatT = goal?.fatTarget ?: 65
    val drinks = meals.sumOf { it.drinks ?: 0.0 }

    // El encabezado queda fijo; al deslizar para actualizar solo baja la lista.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenHeader("Comidas")
        DateSelector(date, min = null, max = today, onChange = { date = it })

        // Aviso de planilla y totales del día: fijos, no se mueven al deslizar para actualizar.
        Column(
            Modifier.fillMaxWidth()
                .swipeDays(onPrev = { date = date.minusDays(1) }, onNext = { if (date < today) date = date.plusDays(1) })
                .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        if (sync.sheetId == null || syncMsg != null) Row(
            Modifier.fillMaxWidth().background(WarnBg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (sync.sheetId == null) "Planilla sin conectar" else syncMsg.orEmpty(),
                color = Warn, fontSize = Type.Small, modifier = Modifier.weight(1f),
            )
            if (sync.sheetId == null) Text(
                "Conectar", color = AccentLight, fontSize = Type.Small,
                modifier = Modifier.clickable { scope.launch { doSync(interactive = true) } }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }

        Column(
            Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Total del día", color = Muted, fontSize = Type.Small)
            Text("%,d / %,d kcal".format(es, totals.kcal.toInt(), kcalT), color = Color.White, fontSize = Type.Title, fontWeight = FontWeight.Bold)
            Bar((totals.kcal / kcalT).toFloat())
            Text("Proteína ${num(totals.protein)} / $protT g", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
            Bar((totals.protein / protT).toFloat())
            Text("Carbohidratos ${num(totals.carbs)} / $carbT g · Grasas ${num(totals.fat)} / $fatT g", color = Soft, fontSize = Type.Small)
            if (drinks > 0) Text("Alcohol: ${num(drinks)} tragos", color = Soft, fontSize = Type.Small)
            // Día completo: solo estos días entran en el cálculo del gasto real (check-in semanal).
            val status by com.uru2904.mtracker.data.Live.dayStatus(db, key).collectAsState()
            val complete = status?.complete == true
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        db.days().upsert(com.uru2904.mtracker.data.DayStatus(key, !complete, pending = true))
                        doSync(interactive = false)
                    }
                }.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = complete, onCheckedChange = null,
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Muted, checkmarkColor = Color.White),
                )
                Text(if (complete) "Día completo" else "Marcar día completo (todo cargado)", color = if (complete) Color.White else Soft,
                    fontSize = Type.Body, modifier = Modifier.padding(start = 8.dp))
            }
        }
        }

        // Solo el deslizar manual muestra la ruedita; la sincronización al entrar es silenciosa.
        RefreshBox(refreshing = pulling, onRefresh = { scope.launch { pulling = true; doSync(interactive = true); pulling = false } }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {


        // Línea de tiempo (estilo MacroFactor): hora · comida + barra de macros · kcal. Deslizar a la izquierda borra.
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))) {
            meals.forEachIndexed { idx, m ->
                if (idx > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(Track))
                androidx.compose.runtime.key(m.id, m.remoteId) {
                    MealRow(m, timeFmt.format(Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault())), onClick = { editingMeal = m }) { toDelete = m }
                }
            }
        }
        if (meals.isEmpty()) Text("Sin comidas cargadas.", color = Muted, fontSize = Type.Body, modifier = Modifier.padding(start = 4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { adding = true },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("+ Agregar", color = Color.White, fontSize = Type.Large) }
            // Abre el chat de Claude donde se cargan las comidas; al volver, la pantalla sincroniza sola.
            Button(
                onClick = { if (!openClaudeChat(context)) snack.say("No se pudo abrir Claude") },
                colors = ButtonDefaults.buttonColors(containerColor = Surface2),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(androidx.compose.material.icons.Icons.Filled.AutoAwesome, null, tint = AccentLight, modifier = Modifier.size(18.dp))
                Text("  Con Claude", color = Color.White, fontSize = Type.Large)
            }
        }
        Button(
            onClick = { showFoods = true },
            colors = ButtonDefaults.buttonColors(containerColor = Card),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Mis alimentos", color = Color.White, fontSize = Type.Body) }
    }
    }
    }

    editingMeal?.let { m ->
        MealEditor(
            m, calendar,
            onSave = { updated ->
                editingMeal = null
                scope.launch {
                    db.meals().update(if (m.pendingOp == "add") updated else updated.copy(pendingOp = "update"))
                    doSync(interactive = false)
                }
            },
            onDelete = { editingMeal = null; toDelete = m },
            onDismiss = { editingMeal = null },
        )
    }

    toDelete?.let { m ->
        ConfirmDialog(
            title = "¿Borrar esta comida?",
            text = "\"${m.description}\" se borra de la app y de la planilla.",
            onConfirm = {
                // Se borra en la app al toque; en la planilla recién cuando pasa el "Deshacer".
                AppScope.launch {
                    if (m.pendingOp == "add") db.meals().delete(m)
                    else db.meals().update(m.copy(pendingOp = "delete"))
                    snack.undoable(
                        "Comida borrada",
                        undo = {
                            // Si otra sincronización ya subió el borrado, se vuelve a agregar.
                            val still = db.meals().pending().any { it.id == m.id }
                            when {
                                m.pendingOp == "add" -> db.meals().insert(m.copy(id = 0))
                                still -> db.meals().update(m)
                                else -> db.meals().insert(m.copy(id = 0, pendingOp = "add"))
                            }
                        },
                        commit = { runCatching { doSync(interactive = false) } },
                    )
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
    Box(Modifier.fillMaxWidth().height(8.dp).background(Track, RoundedCornerShape(4.dp))) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .background(if (fraction > 1.05f) Color(0xFFB45309) else Accent, RoundedCornerShape(4.dp))
        )
    }
}

private fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** Resultado de los 4 campos: valores finales, cuál se calculó solo (0 kcal, 1 P, 2 C, 3 G) y si no cierran. */
data class MacroInput(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val auto: Int?, val mismatchKcal: Double?)

/**
 * Si dejás vacío uno de los 4 (kcal, P, C, G), lo calcula con los otros 3 (P y C 4 kcal/g, G 9 kcal/g).
 * Si están los 4 y no cierran (más de 15% o 25 kcal de diferencia), avisa. Sin kcal ni forma de calcularlas: null.
 */
fun macroInput(k: String, p: String, c: String, f: String): MacroInput? {
    val v = listOf(parse(k), parse(p), parse(c), parse(f))
    val blanks = v.indices.filter { v[it] == null }
    fun fromMacros(pp: Double, cc: Double, ff: Double) = pp * 4 + cc * 4 + ff * 9
    return when {
        blanks.isEmpty() -> {
            val m = fromMacros(v[1]!!, v[2]!!, v[3]!!)
            val off = Math.abs(m - v[0]!!) > maxOf(25.0, v[0]!! * 0.15)
            MacroInput(v[0]!!, v[1]!!, v[2]!!, v[3]!!, null, if (off) m else null)
        }
        blanks.size == 1 -> {
            val i = blanks[0]
            val kk = v[0]; val pp = v[1]; val cc = v[2]; val ff = v[3]
            when (i) {
                0 -> MacroInput(fromMacros(pp!!, cc!!, ff!!), pp, cc, ff, 0, null)
                1 -> MacroInput(kk!!, maxOf(0.0, (kk - cc!! * 4 - ff!! * 9) / 4), cc, ff, 1, null)
                2 -> MacroInput(kk!!, pp!!, maxOf(0.0, (kk - pp * 4 - ff!! * 9) / 4), ff, 2, null)
                else -> MacroInput(kk!!, pp!!, cc!!, maxOf(0.0, (kk - pp * 4 - cc * 4) / 9), 3, null)
            }
        }
        // Faltan 2 o más: hacen falta las kcal; los macros vacíos quedan en 0.
        v[0] != null -> MacroInput(v[0]!!, v[1] ?: 0.0, v[2] ?: 0.0, v[3] ?: 0.0, null, null)
        else -> null
    }
}

/** Los 4 campos de macros + la línea que muestra el valor calculado o el aviso de que no cierran. */
@Composable
private fun MacroFields(
    kcal: String, prot: String, carbs: String, fat: String,
    onKcal: (String) -> Unit, onProt: (String) -> Unit, onCarbs: (String) -> Unit, onFat: (String) -> Unit,
    short: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field(kcal, "kcal", onKcal, true, Modifier.weight(1f))
        field(prot, if (short) "Prot" else "Proteína g", onProt, true, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field(carbs, if (short) "Carbos" else "Carbos g", onCarbs, true, Modifier.weight(1f))
        field(fat, if (short) "Grasas" else "Grasas g", onFat, true, Modifier.weight(1f))
    }
    val r = macroInput(kcal, prot, carbs, fat)
    when {
        r?.auto != null -> {
            val (name, value) = when (r.auto) {
                0 -> "kcal" to "${r.kcal.toInt()}"
                1 -> "Proteína" to "${num(Math.round(r.protein * 10) / 10.0)} g"
                2 -> "Carbos" to "${num(Math.round(r.carbs * 10) / 10.0)} g"
                else -> "Grasas" to "${num(Math.round(r.fat * 10) / 10.0)} g"
            }
            Text("Calculado: $name = $value", color = AccentLight, fontSize = Type.Small)
        }
        r?.mismatchKcal != null -> Text(
            "Ojo: los macros suman ${r.mismatchKcal.toInt()} kcal, no ${r.kcal.toInt()}. ¿Están cruzados? Dejá uno vacío y se calcula solo.",
            color = Warn, fontSize = Type.Small,
        )
        else -> Text("Dejá uno vacío y se calcula con los otros tres.", color = Muted, fontSize = Type.Small)
    }
}

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
    /** ARGENFOOD: valores cada 100 g y la cantidad se carga en gramos (qty = gramos / 100). */
    val grams: Boolean = false,
)

private fun itemOf(f: FoodItem) =
    Item(f.name, f.portion, f.kcal, f.protein, f.carbs, f.fat, qty = 1.0, grams = f.source == "argenfood")

/** Busca por cada palabra de 3+ letras y ordena por cuántas palabras coinciden. */
private suspend fun searchFoods(db: AppDatabase, q: String): List<FoodItem> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
    if (words.isEmpty()) return emptyList()
    val hits = words.flatMap { db.foods().search(it) }
    return hits.groupBy { it.id }.values.sortedByDescending { it.size }.map { it.first() }
}

@Composable
private fun AddMealDialog(db: AppDatabase, onDismiss: () -> Unit, onSave: (MealEntry, Boolean) -> Unit) {
    val addSnack = remember { androidx.compose.material3.SnackbarHostState() }
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
    val allFoods by com.uru2904.mtracker.data.Live.foods(db).collectAsState()
    var editingFood by remember { mutableStateOf<FoodItem?>(null) }

    val tKcal = items.sumOf { it.kcal * it.qty }
    val tProt = items.sumOf { it.protein * it.qty }
    val tCarbs = items.sumOf { it.carbs * it.qty }
    val tFat = items.sumOf { it.fat * it.qty }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { DialogWithSnack(addSnack) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Agregar comida", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)

            MealTypes.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        val sel = t == type
                        Text(
                            t, color = if (sel) Color.White else Soft, fontSize = Type.Body,
                            modifier = Modifier.weight(1f)
                                .background(if (sel) Accent else Card, RoundedCornerShape(10.dp))
                                .clickable { type = t }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        )
                    }
                }
            }

            // Lista de alimentos de la comida
            Text(if (items.isEmpty()) "Sumá alimentos a esta comida" else "En esta comida", color = Muted, fontSize = Type.Small)
            items.forEachIndexed { idx, it ->
                Row(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(it.name, color = Color.White, fontSize = Type.Body)
                        val amount = if (it.grams) "${num(it.qty * 100)} g" else "${num(it.qty)} × ${it.portion}"
                        Text("$amount · ${(it.kcal * it.qty).toInt()} kcal · P ${num(it.protein * it.qty)}", color = Muted, fontSize = Type.Small)
                    }
                    if (it.grams) {
                        // ARGENFOOD: gramos (pesados en crudo)
                        var g by remember(idx, it.name) { mutableStateOf(num(it.qty * 100)) }
                        OutlinedTextField(
                            value = g,
                            onValueChange = { v ->
                                g = v
                                parse(v)?.let { grams -> items[idx] = it.copy(qty = grams / 100.0) }
                            },
                            suffix = { Text("g", color = Muted) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = AccentLight, unfocusedBorderColor = Muted, cursorColor = Color.White,
                            ),
                            modifier = Modifier.width(96.dp),
                        )
                        Icon(
                            Icons.Outlined.Delete, contentDescription = "Quitar", tint = Muted,
                            modifier = Modifier.padding(start = 8.dp).minimumInteractiveComponentSize().clickable { items.removeAt(idx) },
                        )
                    } else {
                        androidx.compose.material3.IconButton(onClick = {
                            if (it.qty <= 0.5) items.removeAt(idx) else items[idx] = it.copy(qty = it.qty - 0.5)
                        }) { Icon(androidx.compose.material.icons.Icons.Filled.Remove, "Menos", tint = Color.White) }
                        androidx.compose.material3.IconButton(onClick = {
                            items[idx] = it.copy(qty = it.qty + 0.5)
                        }) { Icon(androidx.compose.material.icons.Icons.Filled.Add, "Más", tint = Color.White) }
                    }
                }
            }

            // Buscador estilo MacroFactor: fijados arriba, tocar suma a la comida, mantener apretado = menú
            field(query, "Buscar alimento (ej: huevo, pan, leche)", { query = it })
            val shownFoods = if (query.isBlank()) allFoods.filter { it.source != "argenfood" || it.pinned }.take(40)
                else results.sortedWith(compareByDescending<FoodItem> { it.pinned }.thenByDescending { it.source != "argenfood" }).take(20)
            if (query.isBlank() && allFoods.none { it.pinned }) {
                Text("Tip: mantené apretado un alimento para fijarlo arriba.", color = Muted, fontSize = Type.Small)
            }
            if (shownFoods.isNotEmpty()) FoodList(
                db = db, foods = shownFoods,
                onTap = { f -> items.add(itemOf(f)); query = "" },
                showAdd = true,
                onEdit = { editingFood = it },
                onDelete = null,
            )
            if (query.trim().length >= 3 && results.isEmpty()) {
                Text("No está en tus alimentos. Cargalo a mano abajo.", color = Muted, fontSize = Type.Small)
            }

            // Carga manual
            if (!manual) {
                Text(
                    "+ Cargar a mano", color = AccentLight, fontSize = Type.Body,
                    modifier = Modifier.clickable { manual = true; if (mName.isBlank()) mName = query }.padding(vertical = 6.dp),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Alimento nuevo (queda guardado para la próxima)", color = Soft, fontSize = Type.Small)
                    field(mName, "Nombre", { mName = it })
                    field(mPortion, "Porción (ej: 1 plato, 1 vaso)", { mPortion = it })
                    PortionChips { mPortion = it }
                    MacroFields(mKcal, mProt, mCarbs, mFat, { mKcal = it }, { mProt = it }, { mCarbs = it }, { mFat = it })
                    val mi = macroInput(mKcal, mProt, mCarbs, mFat)
                    val ok = mName.isNotBlank() && mi != null
                    Button(
                        onClick = {
                            val f = FoodItem(
                                name = mName.trim(), portion = mPortion.ifBlank { "1 porción" },
                                kcal = mi!!.kcal, protein = mi.protein, carbs = mi.carbs, fat = mi.fat, source = "propio",
                            )
                            scope.launch { db.foods().insert(f) }
                            items.add(Item(f.name, f.portion, f.kcal, f.protein, f.carbs, f.fat))
                            mName = ""; mPortion = ""; mKcal = ""; mProt = ""; mCarbs = ""; mFat = ""; manual = false
                        },
                        enabled = ok,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Track),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (ok) "Sumar a la comida" else "Falta nombre y kcal", color = if (ok) Color.White else Muted) }
                }
            }

            if (type == "Alcohol") field(drinks, "Tragos", { drinks = it }, numeric = true)

            // Totales
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Total: ${tKcal.toInt()} kcal · P ${num(tProt)} g", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                Text("Carbos ${num(tCarbs)} g · Grasas ${num(tFat)} g", color = Muted, fontSize = Type.Small)
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
                        val desc = items.joinToString(" + ") {
                            when {
                                it.grams -> "${num(it.qty * 100)} g ${it.name}"
                                it.qty == 1.0 -> it.name
                                else -> "${num(it.qty)} ${it.name}"
                            }
                        }
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
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Track),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (valid) "Guardar" else "Sumá un alimento", color = if (valid) Color.White else Muted) }
            }
        }
    }}
    editingFood?.let { f -> FoodEditor(db, f, addSnack) { editingFood = null } }
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
                    p, color = Soft, fontSize = Type.Small,
                    modifier = Modifier.weight(1f)
                        .background(Surface2, RoundedCornerShape(8.dp))
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
    val all by com.uru2904.mtracker.data.Live.foods(db).collectAsState()
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<FoodItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FoodItem?>(null) }
    val scope = rememberCoroutineScope()
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val shown = all.filter { it.source != "argenfood" || it.pinned }
        .filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { DialogWithSnack(snack) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Mis alimentos (${shown.size})", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
                Text("Cerrar", color = AccentLight, fontSize = Type.Body, modifier = Modifier.minimumInteractiveComponentSize().clickable { onDismiss() }.padding(8.dp))
            }
            field(filter, "Filtrar", { filter = it })
            Button(
                onClick = { creating = true },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Nuevo alimento", color = Color.White) }
            if (shown.isNotEmpty()) FoodList(
                db = db, foods = shown,
                onTap = { editing = it },
                showAdd = false,
                onEdit = { editing = it },
                onDelete = { deleting = it },
            )
            if (shown.isEmpty()) Text("No hay alimentos que coincidan.", color = Muted, fontSize = Type.Body)
        }
    }}

    editing?.let { f -> FoodEditor(db, f, snack) { editing = null } }
    deleting?.let { f ->
        ConfirmDialog(
            title = "¿Borrar este alimento?",
            text = "\"${f.name}\" se borra de tus alimentos.",
            onConfirm = {
                AppScope.launch { db.foods().delete(f); snack.undoable("Alimento borrado", undo = { db.foods().insert(f) }) }
            },
            onDismiss = { deleting = null },
        )
    }
    if (creating) FoodEditor(db, null, snack) { creating = false }
}

/** Editor de un alimento: crear (initial = null), modificar o borrar. */
@Composable
private fun FoodEditor(db: AppDatabase, initial: FoodItem?, snack: androidx.compose.material3.SnackbarHostState, onClose: () -> Unit) {
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
            Text(if (initial == null) "Nuevo alimento" else "Editar alimento", color = Color.White, fontSize = Type.Headline, fontWeight = FontWeight.Bold)
            field(name, "Nombre", { name = it })
            field(portion, "Porción (ej: 1 vaso, 1 plato)", { portion = it })
            PortionChips { portion = it }
            Text("Valores por porción", color = Muted, fontSize = Type.Small)
            MacroFields(kcal, prot, carbs, fat, { kcal = it }, { prot = it }, { carbs = it }, { fat = it })
            val mi = macroInput(kcal, prot, carbs, fat)
            val ok = name.isNotBlank() && mi != null
            Button(
                onClick = {
                    val item = FoodItem(
                        id = initial?.id ?: 0, name = name.trim(), portion = portion.ifBlank { "1 porción" },
                        kcal = mi!!.kcal, protein = mi.protein, carbs = mi.carbs, fat = mi.fat,
                        source = initial?.source ?: "propio",
                    )
                    scope.launch {
                        if (initial == null) db.foods().insert(item) else db.foods().update(item)
                        onClose()
                    }
                },
                enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Track),
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
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = Danger, modifier = Modifier.size(20.dp))
                    Text("Borrar alimento", color = Danger, fontSize = Type.Body)
                }
                if (confirmDelete) ConfirmDialog(
                    title = "¿Borrar este alimento?",
                    text = "\"${initial.name}\" se borra de tus alimentos.",
                    onConfirm = {
                        AppScope.launch { db.foods().delete(initial); snack.undoable("Alimento borrado", undo = { db.foods().insert(initial) }) }
                        onClose()
                    },
                    onDismiss = { confirmDelete = false },
                )
                Text("Borrar un alimento no cambia las comidas que ya cargaste con él.", color = Muted, fontSize = Type.Small)
            }
        }
    }
}


private val ProtColor = AccentLight
private val CarbColor = Warn
private val FatColor = Color(0xFFE879A6)

/** Fila de la línea de tiempo. Deslizar hacia la izquierda pide confirmación para borrar. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MealRow(m: MealEntry, time: String, onClick: () -> Unit, onDelete: () -> Unit) {
    val current by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) current()
            false // vuelve a su lugar; el borrado lo confirma el diálogo
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(DangerBg).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(Icons.Outlined.Delete, contentDescription = "Borrar", tint = Danger) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().background(Card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Compacta (estilo MacroFactor): hora y tipo a la izquierda; descripción en 1 línea; barra y macros en la misma línea.
            Column(Modifier.width(62.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, color = Soft, fontSize = Type.Small)
                    if (m.pendingOp != null) Box(Modifier.padding(start = 4.dp).size(5.dp).background(Color(0xFFB45309), RoundedCornerShape(3.dp)))
                }
                Text(m.mealType, color = Muted, fontSize = Type.Chart, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.description, color = Color.White, fontSize = Type.Body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(48.dp)) { MacroBar(m.protein, m.carbs, m.fat) }
                    Text("P ${num(m.protein)}", color = ProtColor, fontSize = Type.Chart)
                    Text("C ${num(m.carbs)}", color = CarbColor, fontSize = Type.Chart)
                    Text("G ${num(m.fat)}", color = FatColor, fontSize = Type.Chart)
                }
            }
            Text("${m.kcal.toInt()}", color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** Barra fina con la proporción de calorías que aporta cada macro (P y C 4 kcal/g, G 9 kcal/g). */
@Composable
private fun MacroBar(p: Double, c: Double, f: Double) {
    val kp = p * 4; val kc = c * 4; val kf = f * 9
    val total = (kp + kc + kf).takeIf { it > 0 } ?: return
    Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
        listOf(kp to ProtColor, kc to CarbColor, kf to FatColor).forEach { (v, col) ->
            if (v > 0) Box(Modifier.weight((v / total).toFloat()).fillMaxHeight().background(col))
        }
    }
}


/**
 * Lista de alimentos (estilo MacroFactor). Los fijados van arriba con un pin.
 * Tocar = acción principal; mantener apretado = menú con Fijar/Desfijar, Editar y Borrar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FoodList(
    db: AppDatabase,
    foods: List<FoodItem>,
    onTap: (FoodItem) -> Unit,
    showAdd: Boolean,
    onEdit: (FoodItem) -> Unit,
    onDelete: ((FoodItem) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp))) {
        foods.forEachIndexed { idx, f ->
            if (idx > 0) Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp).background(Track))
            var menu by remember(f.id) { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onTap(f) }, onLongClick = { menu = true })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (f.pinned) Icon(
                        androidx.compose.material.icons.Icons.Filled.PushPin, contentDescription = "Fijado",
                        tint = AccentLight, modifier = Modifier.padding(end = 8.dp).size(16.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Color.White, fontSize = Type.Body)
                        Text(
                            (if (f.source == "argenfood") "ARGENFOOD · cada 100 g" else f.portion) +
                                " · ${f.kcal.toInt()} kcal · P ${num(f.protein)}",
                            color = Muted, fontSize = Type.Small,
                        )
                    }
                    if (showAdd) Icon(
                        androidx.compose.material.icons.Icons.Outlined.AddCircle, contentDescription = "Sumar",
                        tint = AccentLight, modifier = Modifier.size(26.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    containerColor = Surface2,
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (f.pinned) "Desfijar" else "Fijar", color = Color.White) },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.PushPin, null, tint = Soft) },
                        onClick = { menu = false; scope.launch { db.foods().update(f.copy(pinned = !f.pinned)) } },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Editar", color = Color.White) },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Edit, null, tint = Soft) },
                        onClick = { menu = false; onEdit(f) },
                    )
                    if (onDelete != null) androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Borrar", color = Danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Danger) },
                        onClick = { menu = false; onDelete(f) },
                    )
                }
            }
        }
    }
}


/** Abre el chat de Claude configurado en Ajustes (en la app de Claude si está instalada). */
fun openClaudeChat(context: android.content.Context): Boolean {
    val uri = android.net.Uri.parse(com.uru2904.mtracker.data.AppSettings.claudeChatUrl)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val inApp = android.content.Intent(intent).setPackage("com.anthropic.claude")
    return runCatching { context.startActivity(inApp) }.recoverCatching { context.startActivity(intent) }.isSuccess
}


/** Editar una comida cargada: tipo, hora, descripción, macros y escalar la porción. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MealEditor(
    m: MealEntry,
    calendar: com.uru2904.mtracker.domain.ShiftCalendar,
    onSave: (MealEntry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val date = java.time.LocalDate.parse(m.logicalDate)
    var type by remember { mutableStateOf(m.mealType) }
    var time by remember { mutableStateOf(Instant.ofEpochMilli(m.timestamp).atZone(zone).toLocalTime().withSecond(0).withNano(0)) }
    var desc by remember { mutableStateOf(m.description) }
    var kcal by remember { mutableStateOf(num(m.kcal)) }
    var prot by remember { mutableStateOf(num(m.protein)) }
    var carbs by remember { mutableStateOf(num(m.carbs)) }
    var fat by remember { mutableStateOf(num(m.fat)) }
    var factor by remember { mutableStateOf(1.0) }
    var pickTime by remember { mutableStateOf(false) }
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull()
    fun scale(f: Double) {
        // Siempre sobre los valores originales, para no acumular redondeos.
        factor = f
        kcal = num(Math.round(m.kcal * f).toDouble()); prot = num(Math.round(m.protein * f * 10) / 10.0)
        carbs = num(Math.round(m.carbs * f * 10) / 10.0); fat = num(Math.round(m.fat * f * 10) / 10.0)
    }
    val mi = macroInput(kcal, prot, carbs, fat)
    val ok = desc.isNotBlank() && mi != null

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2, titleContentColor = Color.White, textContentColor = Soft,
        title = { Text("Editar comida") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealTypes.forEach { t ->
                        androidx.compose.material3.FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { pickTime = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hora", color = Soft, fontSize = Type.Body, modifier = Modifier.weight(1f))
                    Text(time.toString(), color = Color.White, fontSize = Type.Large, fontWeight = FontWeight.SemiBold)
                }
                field(desc, "Descripción", { desc = it })
                Text("Porción", color = Muted, fontSize = Type.Small)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { f ->
                        androidx.compose.material3.FilterChip(selected = factor == f, onClick = { scale(f) }, label = { Text("×" + num(f)) })
                    }
                }
                MacroFields(kcal, prot, carbs, fat, { kcal = it }, { prot = it }, { carbs = it }, { fat = it }, short = true)
                androidx.compose.material3.TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, null, tint = Danger, modifier = Modifier.size(18.dp))
                    Text("  Borrar comida", color = Danger)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = ok, onClick = {
                // La hora queda dentro del mismo día lógico (ej.: 01:00 en semana de noche = madrugada del día siguiente).
                var at = java.time.LocalDateTime.of(date, time)
                if (calendar.logicalDate(at) != date) at = at.plusDays(1)
                onSave(m.copy(
                    mealType = type, description = desc.trim(), timestamp = at.atZone(zone).toInstant().toEpochMilli(),
                    kcal = mi!!.kcal, protein = mi.protein, carbs = mi.carbs, fat = mi.fat,
                    confidence = if (factor != 1.0) (m.confidence?.let { "$it · porción ×${num(factor)}" } ?: "Porción ×${num(factor)}") else m.confidence,
                ))
            }) { Text("Guardar", color = if (ok) AccentLight else Muted) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } },
    )
    if (pickTime) {
        val st = androidx.compose.material3.rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = Surface2,
            text = { androidx.compose.material3.TimePicker(state = st) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { time = java.time.LocalTime.of(st.hour, st.minute); pickTime = false }) { Text("Listo", color = AccentLight) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pickTime = false }) { Text("Cancelar", color = Color.White) } },
        )
    }
}
