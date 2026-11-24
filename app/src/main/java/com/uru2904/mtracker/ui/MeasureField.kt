package com.uru2904.mtracker.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.uru2904.mtracker.data.MeasurementType

/** Cómo medir cada perímetro (siempre al despertar, sin apretar la piel, dos veces y promedio). */
fun measureHelp(name: String): String {
    val n = name.lowercase()
    val base = "\n\nSiempre al despertarte, en ayunas y después de ir al baño. Cinta horizontal, apoyada sin apretar la piel. Medí dos veces y anotá el promedio."
    return when {
        "cintura" in n -> "A la altura del ombligo. Parado y relajado, sin meter panza, al final de una exhalación normal. Chequeá en el espejo de costado que la cinta quede horizontal." + base
        "cadera" in n -> "En la parte más ancha de los glúteos, vista de costado. Parado, con los pies juntos y los glúteos relajados." + base
        "muslo" in n -> "En la parte más gruesa del muslo, unos centímetros debajo del glúteo. Parado, con el peso repartido en las dos piernas y la pierna relajada. Usá una referencia fija (por ejemplo, 15 cm arriba del borde de la rodilla) y siempre la misma pierna." + base
        "gemelo" in n || "pantorrilla" in n -> "En la parte más gruesa de la pantorrilla, en el tercio superior entre la rodilla y el tobillo. Parado, con el peso repartido y sin ponerte en puntas de pie. Siempre la misma pierna que el muslo." + base
        "pecho" in n -> "A la altura de los pezones, con los brazos relajados al costado del cuerpo, al final de una exhalación normal. La cinta pasa por debajo de las axilas y horizontal por la espalda." + base
        "brazo" in n -> "Con el codo doblado a 90° y el bíceps apretado, en la parte más gruesa del brazo. Siempre el mismo brazo." + base
        "cuello" in n -> "Justo debajo de la nuez, con la cabeza derecha y mirando al frente." + base
        else -> "En la parte más gruesa, siempre en el mismo punto y en las mismas condiciones." + base
    }
}

/** Valor en cm a partir de lo que escribió el usuario ("80,5", "80.5", "80,5 cm"). */
fun parseCm(v: String): Double? = v.trim().replace(',', '.').replace(Regex("[^0-9.]"), "").toDoubleOrNull()?.takeIf { it in 5.0..250.0 }

/** Campo de una medida con el botón "?" que explica cómo medirla. */
@Composable
fun MeasureField(t: MeasurementType, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var help by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(t.name) },
            suffix = { Text("cm", color = Muted) },
            singleLine = true,
            isError = value.isNotBlank() && parseCm(value) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = AccentLight, unfocusedBorderColor = Muted,
                focusedLabelColor = AccentLight, unfocusedLabelColor = Muted, cursorColor = Color.White,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { help = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Cómo medir ${t.name}", tint = Muted)
        }
    }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        containerColor = Card,
        title = { Text("Cómo medir: ${t.name.lowercase()}", color = Color.White) },
        text = { Text(measureHelp(t.name), color = Soft) },
        confirmButton = { TextButton(onClick = { help = false }) { Text("Entendido", color = AccentLight) } },
    )
}
