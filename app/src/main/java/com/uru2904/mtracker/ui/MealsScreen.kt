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
    val scope = rememberCoroutineScope()

    val kcalT = goal?.kcalTarget ?: 2250
    val protT = goal?.proteinTarget ?: 150
    val carbT = goal?.carbsTarget ?: 265
    val fatT = goal?.fatTarget ?: 65
    val drinks = meals.sumOf { it.drinks ?: 0.0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        meals.forEach { m ->
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${m.mealType} · ${timeFmt.format(Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault()))}",
                        color = Muted, fontSize = 12.sp,
                    )
                    Text("Borrar", color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { scope.launch { db.meals().delete(m) } })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(m.description, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${m.kcal.toInt()}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "P ${num(m.protein)} · C ${num(m.carbs)} · G ${num(m.fat)}" + (m.drinks?.let { " · ${num(it)} tragos" } ?: ""),
                    color = Muted, fontSize = 12.sp,
                )
            }
        }
        if (meals.isEmpty()) Text("Sin comidas cargadas.", color = Muted, fontSize = 14.sp)

        Button(
            onClick = { adding = true },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("+ Agregar comida", color = Color.White, fontSize = 16.sp) }
    }

    if (adding) {
        AddMealDialog(
            db = db,
            onDismiss = { adding = false },
            onSave = { meal, saveAsFood ->
                scope.launch {
                    val ts = if (date == today) System.currentTimeMillis()
                    else date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    db.meals().insert(meal.copy(timestamp = ts, logicalDate = date.toString()))
                    if (saveAsFood) {
                        db.foods().insert(
                            FoodItem(
                                name = meal.description, portion = "1 porción", kcal = meal.kcal,
                                protein = meal.protein, carbs = meal.carbs, fat = meal.fat, source = "propio",
                            )
                        )
                    }
                    adding = false
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

@Composable
private fun AddMealDialog(db: AppDatabase, onDismiss: () -> Unit, onSave: (MealEntry, Boolean) -> Unit) {
    var type by remember { mutableStateOf(defaultType()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoodItem>>(emptyList()) }
    var food by remember { mutableStateOf<FoodItem?>(null) }
    var portions by remember { mutableStateOf("1") }
    var desc by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var prot by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var drinks by remember { mutableStateOf("") }
    var saveAsFood by remember { mutableStateOf(false) }

    fun applyFood() {
        val f = food ?: return
        val n = parse(portions) ?: return
        desc = if (n == 1.0) f.name else "${f.name} ×${num(n)}"
        kcal = num(f.kcal * n); prot = num(f.protein * n); carbs = num(f.carbs * n); fat = num(f.fat * n)
    }

    LaunchedEffect(query) {
        results = if (query.trim().length >= 2) db.foods().search(query.trim()) else emptyList()
    }

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

            field(query, "Buscar en mis alimentos", { query = it })
            results.take(8).forEach { f ->
                Column(
                    Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp))
                        .clickable { food = f; portions = "1"; query = ""; applyFood() }
                        .padding(12.dp)
                ) {
                    Text(f.name, color = Color.White, fontSize = 15.sp)
                    Text("${f.portion} · ${f.kcal.toInt()} kcal · P ${num(f.protein)}", color = Muted, fontSize = 12.sp)
                }
            }
            food?.let { f ->
                Text("Alimento: ${f.name} (${f.portion})", color = AccentLight, fontSize = 13.sp)
                field(portions, "Porciones", { portions = it; applyFood() }, numeric = true)
            }

            field(desc, "Descripción", { desc = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(kcal, "kcal", { kcal = it }, true, Modifier.weight(1f))
                field(prot, "Proteína g", { prot = it }, true, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(carbs, "Carbos g", { carbs = it }, true, Modifier.weight(1f))
                field(fat, "Grasas g", { fat = it }, true, Modifier.weight(1f))
            }
            if (type == "Alcohol") field(drinks, "Tragos", { drinks = it }, numeric = true)

            if (food == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(
                        checked = saveAsFood, onCheckedChange = { saveAsFood = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                    )
                    Text("Guardar en mis alimentos", color = Soft, fontSize = 14.sp)
                }
            }

            val valid = desc.isNotBlank() && parse(kcal) != null
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Card),
                    modifier = Modifier.weight(1f),
                ) { Text("Cancelar", color = Color.White) }
                Button(
                    onClick = {
                        onSave(
                            MealEntry(
                                timestamp = 0, logicalDate = "", mealType = type, description = desc.trim(),
                                kcal = parse(kcal) ?: 0.0, protein = parse(prot) ?: 0.0,
                                carbs = parse(carbs) ?: 0.0, fat = parse(fat) ?: 0.0,
                                drinks = if (type == "Alcohol") parse(drinks) else null,
                            ),
                            saveAsFood && food == null,
                        )
                    },
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f),
                ) { Text("Guardar", color = Color.White) }
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
