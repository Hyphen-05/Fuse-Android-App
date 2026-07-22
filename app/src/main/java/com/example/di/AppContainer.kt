package com.example.di

import android.content.Context
import com.example.data.repository.AppPreferencesRepositoryImpl
import com.example.data.repository.RgbDatabaseRepositoryImpl
import com.example.db.AppDatabase
import com.example.domain.AdbControlSink
import com.example.domain.AmbianceCommandSink
import com.example.domain.ConnectionManager
import com.example.domain.repository.AppPreferencesRepository
import com.example.domain.repository.RgbDatabaseRepository
import com.example.hardware.ble.AndroidBleGattTransport
import com.example.hardware.ble.AndroidBleScanTransport
import com.example.hardware.ble.BleGattTransport
import com.example.hardware.ble.BleScanTransport

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

    // Long-lived BLE hardware transports — singletons so the raw GATT/scan state and the
    // DeviceWriteManagers survive Activity/ViewModel recreation (Phase 6, part BLE).
    val bleScanTransport: BleScanTransport by lazy {
        AndroidBleScanTransport(context)
    }

    val bleGattTransport: BleGattTransport by lazy {
        AndroidBleGattTransport(context)
    }

    val ambianceCommandSink: AmbianceCommandSink by lazy {
        AmbianceCommandSink()
    }

    // Debug-only control surface (self-driving test harness) — see AdbControlSink/AdbControlReceiver.
    val adbControlSink: AdbControlSink by lazy {
        AdbControlSink()
    }
}
