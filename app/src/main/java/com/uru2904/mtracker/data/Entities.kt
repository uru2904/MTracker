package com.uru2904.mtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Alimento de la base (propia o ARGENFOOD), valores por porción. */
@Entity(tableName = "food", indices = [Index("name")])
data class FoodItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val portion: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val source: String, // "propio" | "argenfood"
    val pinned: Boolean = false, // fijado arriba en la lista
)

/** Comida registrada. [logicalDate] es el día lógico (yyyy-MM-dd) según el corte del turno. */
@Entity(tableName = "meal", indices = [Index("logicalDate")])
data class MealEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long, // epoch millis
    val logicalDate: String,
    val mealType: String, // Desayuno, Almuerzo, Merienda, Cena, Snack, Alcohol
    val description: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val drinks: Double? = null, // solo para alcohol, en tragos
    val confidence: String? = null, // alta | media | baja (estimaciones por chat)
    val source: String = "app", // app | planilla
    val remoteId: String? = null, // columna ID de la planilla
    val pendingOp: String? = null, // add | delete: cambio local que falta subir a la planilla
)

/** Sesión de gym. La de Hevy CSV reemplaza a la parcial de Health Connect cuando se importa. */
@Entity(tableName = "workout", indices = [Index("start")])
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long,
    val routine: String, // Full A | Full B | Full C | otro
    val durationMin: Int,
    val source: String, // hevy | health_connect | manual
)

@Entity(
    tableName = "workout_set",
    foreignKeys = [ForeignKey(
        entity = WorkoutSession::class,
        parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index("exercise")],
)
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exercise: String,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
)

@Entity(tableName = "weight", indices = [Index("timestamp")])
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kg: Double,
    val source: String, // balanza | manual
)

/** Tipo de medida corporal. Se pueden agregar o desactivar. */
@Entity(tableName = "measurement_type")
data class MeasurementType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val active: Boolean = true,
    val sortOrder: Int,
)

@Entity(
    tableName = "measurement",
    foreignKeys = [ForeignKey(
        entity = MeasurementType::class,
        parentColumns = ["id"], childColumns = ["typeId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("typeId"), Index("date")],
)
data class MeasurementEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val typeId: Long,
    val date: String, // yyyy-MM-dd
    val cm: Double,
)

/** Periodo de meta (por ejemplo, Río 2027). */
@Entity(tableName = "goal_period")
data class GoalPeriod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startDate: String,
    val endDate: String,
    val targetWeightKg: Double? = null,
    val kcalTarget: Int,
    val proteinTarget: Int,
    val carbsTarget: Int,
    val fatTarget: Int,
)


/** Día marcado como completo (todo lo comido está cargado). Solo esos días entran en el cálculo del gasto. */
@Entity(tableName = "day_status")
data class DayStatus(
    @PrimaryKey val date: String, // yyyy-MM-dd (día lógico)
    val complete: Boolean,
    val pending: Boolean = true, // falta subirlo a la planilla
)
