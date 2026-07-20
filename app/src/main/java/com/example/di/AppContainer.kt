package com.example.di

import android.content.Context
import com.example.data.repository.AppPreferencesRepositoryImpl
import com.example.data.repository.RgbDatabaseRepositoryImpl
import com.example.db.AppDatabase
import com.example.domain.AmbianceCommandSink
import com.example.domain.ConnectionManager
import com.example.domain.repository.AppPreferencesRepository
import com.example.domain.repository.RgbDatabaseRepository

class AppContainer(private val context: Context) {
    val appPreferencesRepository: AppPreferencesRepository by lazy {
        AppPreferencesRepositoryImpl(context)
    }

    private val appDatabase: AppDatabase by lazy {
        AppDatabase.getDatabase(context)
    }

    val rgbDatabaseRepository: RgbDatabaseRepository by lazy {
        RgbDatabaseRepositoryImpl(appDatabase.rgbDao())
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager()
    }

    val ambianceCommandSink: AmbianceCommandSink by lazy {
        AmbianceCommandSink()
    }
}
