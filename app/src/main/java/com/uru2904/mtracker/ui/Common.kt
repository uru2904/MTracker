package com.uru2904.mtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF1E3A8A)
val AccentLight = Color(0xFF93B4FF)
val Card = Color(0xFF111111)
val Muted = Color(0xFF8A8A8A)
val Soft = Color(0xFFBDBDBD)

enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    Hoy("Inicio", Icons.Filled.Home, Icons.Outlined.Home),
    Comidas("Comidas", Icons.Filled.Restaurant, Icons.Outlined.Restaurant),
    Gym("Gym", Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter),
    Cuerpo("Cuerpo", Icons.Filled.MonitorWeight, Icons.Outlined.MonitorWeight),
}

/** Barra inferior estilo Hevy: ícono + texto, fondo negro, línea fina arriba. */
@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F1F1F)))
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val sel = t == selected
                val color = if (sel) Color.White else Muted
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp),
                ) {
                    Icon(if (sel) t.on else t.off, contentDescription = t.label, tint = color, modifier = Modifier.size(24.dp))
                    Text(t.label, color = color, fontSize = 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, value: String? = null, note: String? = null) {
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

/** Número con coma decimal y sin decimales si es entero: 82 / 2,5 */
fun num(v: Double): String =
    if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(java.util.Locale("es", "AR"), v)
