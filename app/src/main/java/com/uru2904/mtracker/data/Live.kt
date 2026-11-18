package com.uru2904.mtracker.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.onEach

/**
 * Datos de la base que quedan "vivos" en memoria mientras la app está abierta.
 * Así, al cambiar de pestaña la pantalla aparece con los datos ya cargados (sin parpadeo).
 */
object Live {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<String, StateFlow<*>>()

    /** Claves que ya trajeron datos de la base (para distinguir "cargando" de "vacío"). */
    val loaded = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    @Suppress("UNCHECKED_CAST")
    fun <T> of(key: String, initial: T, flow: () -> Flow<T>): StateFlow<T> =
        cache.getOrPut(key) {
            flow().onEach { if (key !in loaded.value) loaded.value = loaded.value + key }.stateIn(scope, SharingStarted.Eagerly, initial)
        } as StateFlow<T>

    /** Inicio del día de hace [days] días, en ms (estable durante el día, para reutilizar la misma consulta). */
    fun daysAgo(days: Long): Long =
        LocalDate.now().minusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun gymSessions(db: AppDatabase) = of("gym.sessions", emptyList()) { db.workouts().allFlow() }
    fun gymStats(db: AppDatabase) = of("gym.stats", emptyList()) { db.workouts().statsFlow() }
    fun lastWorkout(db: AppDatabase) = of("gym.last", null) { db.workouts().last() }
    fun weightsSince(db: AppDatabase, from: Long) = of("weights.$from", emptyList()) { db.weights().since(from) }
    fun types(db: AppDatabase) = of("measure.types", emptyList()) { db.measurements().activeTypes() }
    fun measures(db: AppDatabase) = of("measure.all", emptyList()) { db.measurements().allFlow() }
    fun goal(db: AppDatabase, date: String) = of("goal.$date", null) { db.goals().activeOn(date) }
    fun foods(db: AppDatabase) = of("foods.all", emptyList()) { db.foods().all() }
    fun meals(db: AppDatabase, date: String) = of("meals.$date", emptyList()) { db.meals().forDay(date) }
    fun dayStatus(db: AppDatabase, date: String) = of("day.$date", null) { db.days().on(date) }
    fun totals(db: AppDatabase, date: String) = of("totals.$date", DayTotals(0.0, 0.0, 0.0, 0.0)) { db.meals().totals(date) }

    /** Precarga lo que usan las pantallas principales. */
    fun warmUp(db: AppDatabase, today: String) {
        gymSessions(db); gymStats(db); lastWorkout(db)
        weightsSince(db, daysAgo(30)); weightsSince(db, daysAgo(365))
        types(db); measures(db); goal(db, today); foods(db); meals(db, today); totals(db, today)
    }
}

@Composable
fun <T> StateFlow<T>.live(): State<T> = remember(this) { this }.collectAsState()

/** Últimos resultados leídos de Health Connect, para mostrarlos al volver a una pantalla mientras se actualizan. */
object UiCache {
    @Volatile var inicio: Any? = null
    @Volatile var granted: Set<String>? = null
    @Volatile var sleepNights: Any? = null
    @Volatile var fatByWeight: Map<Long, Double> = emptyMap()
    @Volatile var usage: Any? = null
}
