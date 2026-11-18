package com.uru2904.mtracker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class SessionStats(val sessionId: Long, val volume: Double, val sets: Int, val exercises: Int)

data class DayTotals(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)

@Dao
interface MealDao {
    @Insert suspend fun insert(meal: MealEntry): Long
    @Update suspend fun update(meal: MealEntry)
    @Delete suspend fun delete(meal: MealEntry)

    @Insert suspend fun insertAll(meals: List<MealEntry>)

    @Query("SELECT logicalDate, SUM(kcal) AS kcal FROM meal WHERE pendingOp IS NULL OR pendingOp != 'delete' GROUP BY logicalDate")
    suspend fun kcalByDay(): List<DayKcal>

    @Query("SELECT * FROM meal WHERE pendingOp IS NOT NULL ORDER BY timestamp")
    suspend fun pending(): List<MealEntry>

    /** Marca todo lo sincronizado para volver a subirlo (al pasar a una planilla nueva). */
    @Query("UPDATE meal SET pendingOp = 'add' WHERE pendingOp IS NULL")
    suspend fun markAllForUpload()

    @Query("DELETE FROM meal WHERE pendingOp IS NULL")
    suspend fun deleteSynced()

    /** Reemplaza lo sincronizado por lo que hay en la planilla; los cambios pendientes se conservan. */
    @Transaction
    suspend fun replaceSynced(meals: List<MealEntry>) {
        deleteSynced()
        // Si hay un cambio pendiente de esa fila (edición o borrado), manda el local.
        val pend = pending().mapNotNull { it.remoteId }.toSet()
        insertAll(meals.filter { it.remoteId !in pend })
    }

    @Query("SELECT * FROM meal WHERE logicalDate = :date AND (pendingOp IS NULL OR pendingOp != 'delete') ORDER BY timestamp")
    fun forDay(date: String): Flow<List<MealEntry>>

    @Query("SELECT * FROM meal WHERE logicalDate BETWEEN :from AND :to AND (pendingOp IS NULL OR pendingOp != 'delete') ORDER BY timestamp")
    suspend fun between(from: String, to: String): List<MealEntry>

    @Query("SELECT MIN(logicalDate) FROM meal WHERE pendingOp IS NULL OR pendingOp != 'delete'")
    suspend fun firstDate(): String?

    @Query(
        "SELECT COALESCE(SUM(kcal),0) kcal, COALESCE(SUM(protein),0) protein, " +
            "COALESCE(SUM(carbs),0) carbs, COALESCE(SUM(fat),0) fat FROM meal WHERE logicalDate = :date " +
            "AND (pendingOp IS NULL OR pendingOp != 'delete')"
    )
    fun totals(date: String): Flow<DayTotals>
}

@Dao
interface FoodDao {
    @Insert suspend fun insertAll(items: List<FoodItem>)
    @Insert suspend fun insert(item: FoodItem): Long

    @Query("SELECT COUNT(*) FROM food")
    suspend fun count(): Int

    @Query("DELETE FROM food WHERE source = :source AND pinned = 0")
    suspend fun deleteUnpinnedBySource(source: String)

    @Query("SELECT name FROM food WHERE source = :source")
    suspend fun namesBySource(source: String): List<String>

    @Update suspend fun update(item: FoodItem)
    @Delete suspend fun delete(item: FoodItem)

    @Query("SELECT * FROM food ORDER BY pinned DESC, name COLLATE NOCASE")
    fun all(): Flow<List<FoodItem>>

    @Query("SELECT * FROM food WHERE name = :name AND portion = :portion LIMIT 1")
    suspend fun find(name: String, portion: String): FoodItem?

    @Query("SELECT * FROM food WHERE name LIKE '%' || :q || '%' ORDER BY source = 'propio' DESC, name LIMIT 50")
    suspend fun search(q: String): List<FoodItem>
}

@Dao
interface WorkoutDao {
    @Insert suspend fun insertSession(s: WorkoutSession): Long
    @Insert suspend fun insertSets(sets: List<WorkoutSet>)

    @Transaction
    suspend fun insertWithSets(s: WorkoutSession, sets: List<WorkoutSet>): Long {
        val id = insertSession(s)
        insertSets(sets.map { it.copy(sessionId = id) })
        return id
    }

    @Query("SELECT * FROM workout ORDER BY start DESC LIMIT 1")
    fun last(): Flow<WorkoutSession?>

    @Query("SELECT * FROM workout WHERE routine = :routine ORDER BY start DESC LIMIT 1")
    suspend fun lastOfRoutine(routine: String): WorkoutSession?

    @Query("SELECT * FROM workout_set WHERE sessionId = :sessionId ORDER BY exercise, setIndex")
    suspend fun setsOf(sessionId: Long): List<WorkoutSet>

    @Query("SELECT * FROM workout ORDER BY start")
    suspend fun all(): List<WorkoutSession>

    @Query("SELECT * FROM workout ORDER BY start DESC")
    fun allFlow(): Flow<List<WorkoutSession>>

    /** Volumen (kg × reps) por sesión. */
    @Query("SELECT sessionId, COALESCE(SUM(COALESCE(weightKg,0) * COALESCE(reps,0)),0) AS volume, COUNT(*) AS sets, COUNT(DISTINCT exercise) AS exercises FROM workout_set GROUP BY sessionId")
    fun statsFlow(): Flow<List<SessionStats>>

    @Query("SELECT * FROM workout_set")
    suspend fun allSets(): List<WorkoutSet>

    @Query("SELECT COUNT(*) FROM workout WHERE start = :start AND source = :source")
    suspend fun countAt(start: Long, source: String): Int
}

@Dao
interface WeightDao {
    @Insert suspend fun insert(w: WeightEntry): Long
    @Delete suspend fun delete(w: WeightEntry)

    @Query("SELECT * FROM weight ORDER BY timestamp DESC LIMIT 1")
    fun latest(): Flow<WeightEntry?>

    @Query("SELECT * FROM weight ORDER BY timestamp")
    suspend fun all(): List<WeightEntry>

    @Insert suspend fun insertAll(w: List<WeightEntry>)

    @Query("SELECT * FROM weight WHERE timestamp >= :from ORDER BY timestamp")
    fun since(from: Long): Flow<List<WeightEntry>>
}

@Dao
interface MeasurementDao {
    @Insert suspend fun insertType(t: MeasurementType): Long
    @Update suspend fun updateType(t: MeasurementType)
    @Insert suspend fun insert(m: MeasurementEntry): Long

    @Query("SELECT * FROM measurement_type WHERE active = 1 ORDER BY sortOrder")
    fun activeTypes(): Flow<List<MeasurementType>>

    @Query("SELECT * FROM measurement WHERE typeId = :typeId ORDER BY date")
    fun history(typeId: Long): Flow<List<MeasurementEntry>>

    @Query("SELECT * FROM measurement_type ORDER BY sortOrder")
    suspend fun allTypes(): List<MeasurementType>

    @Query("SELECT * FROM measurement ORDER BY date")
    suspend fun all(): List<MeasurementEntry>

    @Query("SELECT * FROM measurement ORDER BY date")
    fun allFlow(): Flow<List<MeasurementEntry>>

    @Insert suspend fun insertAll(m: List<MeasurementEntry>)

    @Query("SELECT MAX(date) FROM measurement")
    suspend fun lastDate(): String?

    /** Última fecha con medidas hasta [date] inclusive. */
    @Query("SELECT MAX(date) FROM measurement WHERE date <= :date")
    suspend fun lastDateUntil(date: String): String?

    @Query("SELECT * FROM measurement WHERE date = :date")
    suspend fun onDate(date: String): List<MeasurementEntry>
}

data class DayKcal(val logicalDate: String, val kcal: Double)

@Dao
interface DayStatusDao {
    @androidx.room.Upsert suspend fun upsert(d: DayStatus)
    @androidx.room.Upsert suspend fun upsertAll(d: List<DayStatus>)

    @Query("SELECT * FROM day_status WHERE date = :date")
    fun on(date: String): Flow<DayStatus?>

    @Query("SELECT * FROM day_status")
    suspend fun all(): List<DayStatus>

    @Query("SELECT * FROM day_status WHERE pending = 1")
    suspend fun pending(): List<DayStatus>

    @Query("UPDATE day_status SET pending = 0 WHERE date IN (:dates)")
    suspend fun markSynced(dates: List<String>)
}

@Dao
interface GoalDao {
    @Insert suspend fun insert(g: GoalPeriod): Long
    @Update suspend fun update(g: GoalPeriod)

    @Query("SELECT * FROM goal_period WHERE :today BETWEEN startDate AND endDate ORDER BY startDate DESC LIMIT 1")
    suspend fun activeNow(today: String): GoalPeriod?

    @Query("SELECT * FROM goal_period WHERE :today BETWEEN startDate AND endDate ORDER BY startDate DESC LIMIT 1")
    fun activeOn(today: String): Flow<GoalPeriod?>
}
