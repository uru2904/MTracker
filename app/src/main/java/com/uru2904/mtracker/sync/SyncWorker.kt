package com.uru2904.mtracker.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uru2904.mtracker.data.AppDatabase
import com.uru2904.mtracker.health.HealthRepository
import com.uru2904.mtracker.sheets.SheetsSync
import java.util.concurrent.TimeUnit

/**
 * Cada 2 horas (con internet): sube comidas pendientes, gym, medidas y el resumen diario
 * de Health Connect a la planilla, aunque la app no esté abierta.
 * Si Google pide consentimiento de nuevo, no hace nada: se resuelve la próxima vez que abras Comidas.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val sync = SheetsSync(applicationContext, db)
        if (sync.sheetId == null) return Result.success()
        val ok = sync.syncSilently(applicationContext, HealthRepository(applicationContext))
        applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE).edit()
            .putLong(if (ok) "last_bg_ok" else "last_bg_fail", System.currentTimeMillis()).apply()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
