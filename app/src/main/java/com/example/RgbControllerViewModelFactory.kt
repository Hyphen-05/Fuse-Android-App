package com.example

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.domain.AdbControlSink
import com.example.domain.AmbianceCommandSink
import com.example.domain.ConnectionManager
import com.example.domain.repository.AppPreferencesRepository
import com.example.domain.repository.RgbDatabaseRepository
import com.example.hardware.ble.BleGattTransport
import com.example.hardware.ble.BleScanTransport

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
