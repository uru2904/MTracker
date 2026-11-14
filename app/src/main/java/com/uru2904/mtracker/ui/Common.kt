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

enum class Tab(val label: String) { Hoy("Hoy"), Comidas("Comidas"), Gym("Gym"), Cuerpo("Cuerpo"), Actividad("Actividad") }

@Composable
fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).background(Color.Black),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            Text(
                t.label,
                color = if (t == selected) Color.White else Muted,
                fontWeight = if (t == selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onSelect(t) }.padding(horizontal = 6.dp, vertical = 12.dp),
            )
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
