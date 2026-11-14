package com.uru2904.mtracker.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Resumen de un día para la pestaña Diario de la planilla. */
data class DailySummary(
    val date: LocalDate,
    val steps: Long,
    val sleepHours: Double,
    val napHours: Double,
    val weightKg: Double?,
    val restingHr: Long?,
    val exercises: String,
    val bodyFatPct: Double? = null,
    val leanKg: Double? = null,
)

/** Composición corporal medida con el reloj (Samsung la pasa a Health Connect). */
data class Composition(val bodyFatPct: Double?, val leanKg: Double?, val boneKg: Double?, val at: Instant) {
    val any get() = bodyFatPct != null || leanKg != null || boneKg != null
}

/** Resumen de sueño de las últimas 24 horas: total y cuánto fue siesta. */
data class SleepSummary(val total: Duration, val nap: Duration)

/** Acceso a Health Connect: disponibilidad, permisos y lecturas. */
class HealthRepository(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.BoneMassRecord::class),
    )

    /** Permiso mínimo para el resumen diario (los demás se leen si están). */
    val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /**
     * Pasos del día. Se pide el día completo (00:00 a 00:00) porque Samsung Health guarda
     * un registro que cubre todo el día; con un rango parcial, Health Connect lo prorratea.
     */
    suspend fun stepsToday(): Long {
        val today = LocalDate.now()
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val result = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
        )
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    /** Sueño que terminó en las últimas 24 h. La sesión más larga es el sueño principal; el resto, siesta. */
    suspend fun sleepLast24h(): SleepSummary {
        val sessions = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.after(Instant.now().minus(Duration.ofHours(36))),
            )
        ).records.filter { it.endTime.isAfter(Instant.now().minus(Duration.ofHours(24))) }
        val durations = sessions.map { Duration.between(it.startTime, it.endTime) }
        val total = durations.fold(Duration.ZERO, Duration::plus)
        val main = durations.maxOrNull() ?: Duration.ZERO
        return SleepSummary(total = total, nap = total.minus(main))
    }

    suspend fun recentExercises(days: Long = 7): List<ExerciseSessionRecord> =
        client.readRecords(
            ReadRecordsRequest(
                ExerciseSessionRecord::class,
                TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(days))),
            )
        ).records.sortedByDescending { it.startTime }

    /** Última composición corporal registrada desde [since] (o null si no hay). */
    suspend fun compositionSince(since: Instant): Composition? {
        val range = TimeRangeFilter.after(since)
        val fat = runCatching {
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.BodyFatRecord::class, range)).records.maxByOrNull { it.time }
        }.getOrNull()
        val lean = runCatching {
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.LeanBodyMassRecord::class, range)).records.maxByOrNull { it.time }
        }.getOrNull()
        val bone = runCatching {
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.BoneMassRecord::class, range)).records.maxByOrNull { it.time }
        }.getOrNull()
        val c = Composition(
            bodyFatPct = fat?.percentage?.value,
            leanKg = lean?.mass?.inKilograms,
            boneKg = bone?.mass?.inKilograms,
            at = listOfNotNull(fat?.time, lean?.time, bone?.time).maxOrNull() ?: Instant.now(),
        )
        return c.takeIf { it.any }
    }

    /** Guarda un peso en Health Connect (así lo ven Samsung Health y el resumen diario). */
    suspend fun writeWeight(kg: Double, at: Instant = Instant.now()) {
        client.insertRecords(
            listOf(
                WeightRecord(
                    time = at,
                    zoneOffset = zone.rules.getOffset(at),
                    weight = androidx.health.connect.client.units.Mass.kilograms(kg),
                )
            )
        )
    }

    /** Borra de Health Connect el peso que guardó la app en ese momento (solo puede borrar los suyos). */
    suspend fun deleteWeight(at: Instant) {
        client.deleteRecords(WeightRecord::class, TimeRangeFilter.between(at.minusSeconds(5), at.plusSeconds(5)))
    }

    /** Resumen de cada uno de los últimos [days] días (incluye hoy). */
    suspend fun dailySummaries(days: Int = 14): List<DailySummary> =
        (0 until days).map { LocalDate.now().minusDays(it.toLong()) }.map { day(it) }

    private suspend fun day(date: LocalDate): DailySummary {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)
        val steps = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL] ?: 0L
        // Sueño: sesiones que terminaron ese día; la más larga es el sueño principal.
        val sleeps = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofHours(18)), end))
        ).records.filter { !it.endTime.isBefore(start) && it.endTime.isBefore(end) }
        val durs = sleeps.map { Duration.between(it.startTime, it.endTime).toMinutes() }
        val total = durs.sum()
        val nap = total - (durs.maxOrNull() ?: 0L)
        val weight = runCatching {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records.maxByOrNull { it.time }?.weight?.inKilograms
        }.getOrNull()
        val rhr = runCatching {
            client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records
                .map { it.beatsPerMinute }.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it) }
        }.getOrNull()
        val ex = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
            .sortedBy { it.startTime }
            .joinToString("; ") { "${exerciseName(it.exerciseType)} ${Duration.between(it.startTime, it.endTime).toMinutes()} min" }
        val comp = runCatching { compositionSince(start) }.getOrNull()?.takeIf { it.at.isBefore(end) }
        return DailySummary(date, steps, total / 60.0, nap / 60.0, weight, rhr, ex, comp?.bodyFatPct, comp?.leanKg)
    }

    companion object {
        fun exerciseName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Caminata"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Gym"
            else -> "Otro"
        }
    }
}
