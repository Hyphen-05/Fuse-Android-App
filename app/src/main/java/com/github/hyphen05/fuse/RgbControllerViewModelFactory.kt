package com.github.hyphen05.fuse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.hyphen05.fuse.domain.AdbControlSink
import com.github.hyphen05.fuse.domain.AmbianceCommandSink
import com.github.hyphen05.fuse.domain.ConnectionManager
import com.github.hyphen05.fuse.domain.repository.AppPreferencesRepository
import com.github.hyphen05.fuse.domain.repository.RgbDatabaseRepository
import com.github.hyphen05.fuse.hardware.ble.BleGattTransport
import com.github.hyphen05.fuse.hardware.ble.BleScanTransport

class RgbControllerViewModelFactory(
    private val applicationContext: Context,
    private val prefsRepo: AppPreferencesRepository,
    private val dbRepo: RgbDatabaseRepository,
    private val connectionManager: ConnectionManager,
    private val bleScanTransport: BleScanTransport,
    private val bleGattTransport: BleGattTransport,
    private val ambianceCommandSink: AmbianceCommandSink,
    private val adbControlSink: AdbControlSink
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RgbControllerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RgbControllerViewModel(
                applicationContext,
                prefsRepo,
                dbRepo,
                connectionManager,
                bleScanTransport,
                bleGattTransport,
                ambianceCommandSink,
                adbControlSink
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
