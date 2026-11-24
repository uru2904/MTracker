package com.uru2904.mtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FoodItem::class, MealEntry::class, WorkoutSession::class, WorkoutSet::class,
        WeightEntry::class, MeasurementType::class, MeasurementEntry::class, GoalPeriod::class, DayStatus::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun foods(): FoodDao
    abstract fun workouts(): WorkoutDao
    abstract fun weights(): WeightDao
    abstract fun measurements(): MeasurementDao
    abstract fun goals(): GoalDao
    abstract fun days(): DayStatusDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meal ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE meal ADD COLUMN pendingOp TEXT")
                db.execSQL("ALTER TABLE food ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                // Lo que vino de la planilla se vuelve a traer; lo cargado a mano se sube.
                db.execSQL("DELETE FROM meal WHERE source = 'planilla'")
                db.execSQL("UPDATE meal SET source = 'app', pendingOp = 'add', remoteId = lower(hex(randomblob(16)))")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS day_status (date TEXT NOT NULL, complete INTEGER NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(date))")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "mtracker.db")
                .addCallback(Seed)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { db ->
                    instance = db
                    // Cada cambio programa una copia en Drive unos minutos después.
                    val app = context.applicationContext
                    db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(
                        arrayOf("meal", "food", "workout", "workout_set", "weight", "measurement_type", "measurement", "goal_period", "day_status")
                    ) {
                        override fun onInvalidated(tables: Set<String>) {
                            runCatching { com.uru2904.mtracker.backup.DriveBackup.onChange(app) }
                            runCatching { com.uru2904.mtracker.widget.WidgetUpdater.request(app) }
                        }
                    })
                }
        }

        /** Cierra la base para reemplazar el archivo al restaurar una copia. */
        fun closeForRestore() = synchronized(this) { instance?.close(); instance = null }
    }

    /** Datos iniciales: medidas por defecto y el periodo de Río. */
    private object Seed : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            listOf("Cintura", "Pecho", "Brazo contraído", "Muslo", "Cadera", "Gemelos")
                .forEachIndexed { i, name ->
                    db.execSQL(
                        "INSERT INTO measurement_type (name, active, sortOrder) VALUES (?, 1, ?)",
                        arrayOf<Any>(name, i),
                    )
                }
            db.execSQL(
                "INSERT INTO goal_period (name, startDate, endDate, targetWeightKg, kcalTarget, proteinTarget, carbsTarget, fatTarget) " +
                    "VALUES ('Río 2027', '2026-11-05', '2027-02-13', 70.5, 2250, 150, 265, 65)"
            )
        }
    }
}
