package com.uru2904.mtracker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.ui.Accent
import com.uru2904.mtracker.ui.Card
import com.uru2904.mtracker.ui.InfoCard
import com.uru2904.mtracker.ui.MealsScreen
import com.uru2904.mtracker.ui.Muted
import com.uru2904.mtracker.ui.TabBar
import com.uru2904.mtracker.ui.Tab
import com.uru2904.mtracker.data.FoodSeed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.uru2904.mtracker.gym.HevyImporter
import java.time.Instant
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.uru2904.mtracker.domain.ShiftCalendar
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.health.SleepSummary
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


class MainActivity : ComponentActivity() {
    private val sharedUri: MutableState<Uri?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.uru2904.mtracker.data.AppSettings.init(this)
        val health = HealthRepository(this)
        val db = AppDatabase.get(this)
        handleIntent(intent)
        com.uru2904.mtracker.sync.SyncWorker.schedule(this)
        com.uru2904.mtracker.data.Live.warmUp(db, com.uru2904.mtracker.data.AppSettings.calendar().logicalDate(java.time.LocalDateTime.now()).toString())
        lifecycleScope.launch {
            FoodSeed.ensure(db)
            runCatching { FoodSeed.loadArgenfood(db, this@MainActivity) }
        }
        setContent { com.uru2904.mtracker.ui.MTrackerTheme {
            // La tablet solo mide y sube su uso de pantalla.
            var tablet by remember { mutableStateOf(com.uru2904.mtracker.usage.DeviceRole.isTablet(this)) }
            if (tablet) {
                Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
                    com.uru2904.mtracker.ui.TabletScreen(db) {
                        com.uru2904.mtracker.usage.DeviceRole.set(this@MainActivity, false); tablet = false
                    }
                }
                return@MTrackerTheme
            }
            var tab by remember { mutableStateOf(Tab.Hoy) }
            androidx.activity.compose.BackHandler(enabled = tab != Tab.Hoy) { tab = Tab.Hoy }
            LaunchedEffect(sharedUri.value) { if (sharedUri.value != null) tab = Tab.Hoy }
            Column(
                Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()
            ) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Hoy -> com.uru2904.mtracker.ui.HomeScreen(health, db, sharedUri) { tab = it }
                        Tab.Comidas -> MealsScreen(db)
                        Tab.Gym -> {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val scope = rememberCoroutineScope()
                            val gymPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                if (uri != null) scope.launch {
                                    val msg = try {
                                        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                            ?: error("No se pudo abrir el archivo")
                                        val r = HevyImporter(db).import(text)
                                        "Hevy: ${r.added} sesiones nuevas, ${r.skipped} ya estaban"
                                    } catch (e: Exception) { "No se pudo importar: ${e.message}" }
                                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            com.uru2904.mtracker.ui.GymScreen(db) {
                                gymPicker.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
                            }
                        }
                        Tab.Cuerpo -> com.uru2904.mtracker.ui.ScaleScreen(db)
                        Tab.Sueno -> com.uru2904.mtracker.ui.SleepScreen()
                        Tab.Uso -> com.uru2904.mtracker.ui.ScreenTimeScreen(db) { tab = Tab.Hoy }
                        Tab.Ajustes -> com.uru2904.mtracker.ui.SettingsScreen(db) { tab = Tab.Hoy }
                        else -> InfoCard("${tab.label}: próximamente")
                    }
                }
                TabBar(if (tab.inBar) tab else Tab.Hoy) { tab = it }
            }
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        sharedUri.value = uri
    }
}

