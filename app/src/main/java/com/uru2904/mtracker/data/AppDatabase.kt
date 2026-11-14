package com.uru2904.mtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FoodItem::class, MealEntry::class, WorkoutSession::class, WorkoutSet::class,
        WeightEntry::class, MeasurementType::class, MeasurementEntry::class, GoalPeriod::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun foods(): FoodDao
    abstract fun workouts(): WorkoutDao
    abstract fun weights(): WeightDao
    abstract fun measurements(): MeasurementDao
    abstract fun goals(): GoalDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "mtracker.db")
                .addCallback(Seed)
                .build()
                .also { instance = it }
        }
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
