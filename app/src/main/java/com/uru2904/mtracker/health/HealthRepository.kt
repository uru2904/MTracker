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
data class Composition(val bodyFatPct: Double?, val leanKg: Double?, val at: Instant) {
    val any get() = bodyFatPct != null || leanKg != null
}

/** Una fase dentro de una sesión de sueño (tipos de SleepSessionRecord.STAGE_TYPE_*). */
data class SleepStage(val start: Instant, val end: Instant, val type: Int)

/** Una sesión de sueño (principal o siesta). */
data class SleepBlock(val start: Instant, val end: Instant, val stages: List<SleepStage>) {
    val minutes get() = Duration.between(start, end).toMinutes()
}

/** Todo lo dormido que terminó en un mismo día. La sesión más larga es el sueño principal. */
data class SleepNight(val date: LocalDate, val blocks: List<SleepBlock>) {
    val main get() = blocks.maxBy { it.minutes }
    val naps get() = blocks.filter { it !== main }
    val totalMin get() = blocks.sumOf { it.minutes }
    val napMin get() = naps.sumOf { it.minutes }
    private fun stageMin(vararg types: Int) = blocks.flatMap { it.stages }.filter { it.type in types }
        .sumOf { Duration.between(it.start, it.end).toMinutes() }
    val minutes get() = com.uru2904.mtracker.domain.SleepMinutes(
        total = totalMin,
        awake = stageMin(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED),
        light = stageMin(SleepSessionRecord.STAGE_TYPE_LIGHT),
        deep = stageMin(SleepSessionRecord.STAGE_TYPE_DEEP),
        rem = stageMin(SleepSessionRecord.STAGE_TYPE_REM),
    )
}

/** Signos durante el sueño principal. */
data class SleepVitals(val hrAvg: Long?, val hrMin: Long?, val spo2Avg: Double?, val spo2Min: Double?, val rrAvg: Double?)

/** Promedio de sueño por día (total y siesta, en horas) sobre los días con datos. */
data class SleepAverage(val totalH: Double, val napH: Double, val days: Int)

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
        HealthPermission.getReadPermission(androidx.health.connect.client.records.RespiratoryRateRecord::class),
    )

    /** Permiso para leer Health Connect cuando la app no está abierta (Android 15+). */
    val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** true si este teléfono soporta (y por lo tanto necesita) el permiso de lectura en segundo plano. */
    @OptIn(androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi::class)
    fun backgroundReadSupported(): Boolean = runCatching {
        client.features.getFeatureStatus(
            androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == androidx.health.connect.client.HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** Permiso para leer datos de más de 30 días atrás. */
    val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    @OptIn(androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi::class)
    fun historyReadSupported(): Boolean = runCatching {
        client.features.getFeatureStatus(
            androidx.health.connect.client.HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
        ) == androidx.health.connect.client.HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** Todos los pesos de Health Connect de otras apps (Samsung Health, etc.): (momento en ms, kg, origen). */
    suspend fun weightHistory(years: Long = 5): List<Triple<Long, Double, String>> {
        val out = mutableListOf<Triple<Long, Double, String>>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(
                    WeightRecord::class,
                    TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(365 * years))),
                    pageToken = token,
                )
            )
            res.records.filter { it.metadata.dataOrigin.packageName != context.packageName }
                .forEach { out += Triple(it.time.toEpochMilli(), it.weight.inKilograms, it.metadata.dataOrigin.packageName) }
            token = res.pageToken
        } while (token != null)
        return out
    }

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

    /** Promedio de los últimos [days] días: sesiones agrupadas por el día en que terminaron; la más larga es el sueño principal. */
    suspend fun sleepAverage(days: Int = 7): SleepAverage? {
        val start = LocalDate.now().minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val recs = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofHours(18)), Instant.now()))
        ).records.filter { !it.endTime.isBefore(start) }
        if (recs.isEmpty()) return null
        val perDay = recs.groupBy { it.endTime.atZone(zone).toLocalDate() }.values.map { s ->
            val mins = s.map { Duration.between(it.startTime, it.endTime).toMinutes() }
            mins.sum() to (mins.sum() - mins.max())
        }
        return SleepAverage(perDay.map { it.first }.average() / 60, perDay.map { it.second }.average() / 60, perDay.size)
    }

    /** Noches de los últimos [days] días, más reciente primero. */
    suspend fun sleepNights(days: Int = 30): List<SleepNight> {
        val start = LocalDate.now().minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val out = mutableListOf<SleepSessionRecord>()
        var token: String? = null
        do {
            val res = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofHours(18)), Instant.now()), pageToken = token)
            )
            out += res.records
            token = res.pageToken
        } while (token != null)
        return out.filter { !it.endTime.isBefore(start) }
            .map { r -> SleepBlock(r.startTime, r.endTime, r.stages.map { SleepStage(it.startTime, it.endTime, it.stage) }) }
            .groupBy { it.end.atZone(zone).toLocalDate() }
            .map { (d, b) -> SleepNight(d, b.sortedBy { it.start }) }
            .sortedByDescending { it.date }
    }

    /** Ritmo cardíaco, oxígeno y respiración durante un período. */
    suspend fun sleepVitals(start: Instant, end: Instant): SleepVitals {
        val range = TimeRangeFilter.between(start, end)
        val hr = runCatching {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records.flatMap { it.samples }.map { it.beatsPerMinute }
        }.getOrDefault(emptyList())
        val spo2 = runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records.map { it.percentage.value }
        }.getOrDefault(emptyList())
        val rr = runCatching {
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.RespiratoryRateRecord::class, range)).records.map { it.rate }
        }.getOrDefault(emptyList())
        return SleepVitals(
            hrAvg = hr.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it) },
            hrMin = hr.minOrNull(),
            spo2Avg = spo2.takeIf { it.isNotEmpty() }?.average(),
            spo2Min = spo2.minOrNull(),
            rrAvg = rr.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    suspend fun recentExercises(days: Long = 7): List<ExerciseSessionRecord> =
        client.readRecords(
            ReadRecordsRequest(
                ExerciseSessionRecord::class,
                TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(days))),
            )
        ).records.sortedByDescending { it.startTime }

    /** Última composición corporal registrada desde [since] (o null si no hay). */
    suspend fun compositionSince(since: Instant): Composition? = compositionIn(TimeRangeFilter.after(since))

    /** Última composición corporal hasta [until] (busca hasta 1 año atrás). */
    suspend fun compositionUntil(until: Instant): Composition? =
        compositionIn(TimeRangeFilter.between(until.minus(Duration.ofDays(365)), until))

    private suspend fun compositionIn(range: TimeRangeFilter): Composition? {
        val fat = runCatching {
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.BodyFatRecord::class, range)).records.maxByOrNull { it.time }
        }.getOrNull()
        val lean = runCatching {
            client.readRecords(ReadRecordsRequest(androidx.health.connect.client.records.LeanBodyMassRecord::class, range)).records.maxByOrNull { it.time }
        }.getOrNull()
        val c = Composition(
            bodyFatPct = fat?.percentage?.value,
            leanKg = lean?.mass?.inKilograms,
            at = listOfNotNull(fat?.time, lean?.time).maxOrNull() ?: Instant.now(),
        )
        return c.takeIf { it.any }
    }

    /** % de grasa medidos desde [since]: (momento en ms, %). */
    suspend fun bodyFatSince(since: Instant): List<Pair<Long, Double>> =
        client.readRecords(
            ReadRecordsRequest(androidx.health.connect.client.records.BodyFatRecord::class, TimeRangeFilter.after(since))
        ).records.map { it.time.toEpochMilli() to it.percentage.value }

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
