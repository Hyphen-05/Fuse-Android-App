package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.db.SavedDevice

@Database(entities = [RgbPreset::class, RgbDeviceAlias::class, SavedDevice::class, CustomMode::class, ColorCalibration::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rgbDao(): RgbDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rgb_controller_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
