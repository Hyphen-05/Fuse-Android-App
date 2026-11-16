package com.github.hyphen05.fuse.di

import android.content.Context
import com.github.hyphen05.fuse.data.repository.AppPreferencesRepositoryImpl
import com.github.hyphen05.fuse.data.repository.RgbDatabaseRepositoryImpl
import com.github.hyphen05.fuse.db.AppDatabase
import com.github.hyphen05.fuse.domain.AdbControlSink
import com.github.hyphen05.fuse.domain.AmbianceCommandSink
import com.github.hyphen05.fuse.domain.ConnectionManager
import com.github.hyphen05.fuse.domain.repository.AppPreferencesRepository
import com.github.hyphen05.fuse.domain.repository.RgbDatabaseRepository
import com.github.hyphen05.fuse.hardware.ble.AndroidBleGattTransport
import com.github.hyphen05.fuse.hardware.ble.AndroidBleScanTransport
import com.github.hyphen05.fuse.hardware.ble.BleGattTransport
import com.github.hyphen05.fuse.hardware.ble.BleScanTransport

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
