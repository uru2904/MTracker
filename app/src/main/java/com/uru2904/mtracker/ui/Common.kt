package com.uru2904.mtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
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
    Sueno("Sueño", Icons.Filled.Bedtime, Icons.Outlined.Bedtime),
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


/** Confirmación para acciones destructivas (borrar). */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Borrar", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161616),
        titleContentColor = Color.White,
        textContentColor = Soft,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel, color = Color(0xFFF87171)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } },
    )
}

/**
 * Deslizar hacia abajo para actualizar: el contenido baja y la ruedita aparece en el hueco
 * de arriba (sin círculo flotante); al terminar, el contenido sube y la tapa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val gap = 64.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val target = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1.5f)
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = target, label = "refresh",
        animationSpec = if (state.distanceFraction > 0f && !refreshing) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(250),
    )
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            Box(Modifier.fillMaxWidth().height(gap * fraction.coerceAtMost(1f)), contentAlignment = Alignment.Center) {
                if (fraction > 0.15f) {
                    if (refreshing) androidx.compose.material3.CircularProgressIndicator(
                        color = Soft, strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                    ) else androidx.compose.material3.CircularProgressIndicator(
                        progress = { fraction.coerceAtMost(1f) },
                        color = Soft, strokeWidth = 2.dp, modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = with(density) { (gap * fraction.coerceAtMost(1.2f)).toPx() } }) {
            content()
        }
    }
}


/** Fila que se desliza hacia la izquierda para borrar (vuelve a su lugar; el borrado lo confirma un diálogo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    // Siempre la acción de la fila actual (si no, después de borrar una, la de abajo apunta a la borrada).
    val current by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) current()
            false
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(Color(0xFF3A1212)).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(androidx.compose.material.icons.Icons.Outlined.Delete, contentDescription = "Borrar", tint = Color(0xFFF87171)) }
        },
    ) { content() }
}
