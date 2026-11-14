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
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    suspend fun stepsToday(): Long {
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
        val result = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now()))
        )
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    /** Diagnóstico: pasos de hoy por app de origen y hora del último dato de cada una. */
    suspend fun stepsBySourceToday(): List<String> {
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
        val records = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, Instant.now()))
        ).records
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return records.groupBy { it.metadata.dataOrigin.packageName }.map { (pkg, recs) ->
            val last = recs.maxOf { it.endTime }.atZone(zone)
            "$pkg: ${recs.sumOf { it.count }} pasos (último dato ${fmt.format(last)})"
        }
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
