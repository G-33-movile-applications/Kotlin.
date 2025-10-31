package com.example.mymeds.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.mymeds.data.local.room.converters.Converters
import com.example.mymeds.data.local.room.dao.GlobalMedicationDao
import com.example.mymeds.data.local.room.dao.MedicationDao
import com.example.mymeds.data.local.room.entitites.MedicationEntity
import com.example.mymeds.models.GlobalMedication

/**
 * Clase main para la app, es Singleton para evitar tener varias instancias
 * al mismo tiempo
 */
@Database(
    entities = [
        GlobalMedication::class, // GlobalMedication cache
        MedicationEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun globalMedicationDao(): GlobalMedicationDao
    abstract fun medicationDao(): MedicationDao

    companion object {
        // @Volatile para que la instancia esté al día
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mymeds-application"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
