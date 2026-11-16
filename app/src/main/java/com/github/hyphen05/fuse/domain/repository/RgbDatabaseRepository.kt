package com.github.hyphen05.fuse.domain.repository

import kotlinx.coroutines.flow.Flow
import com.github.hyphen05.fuse.db.CustomMode
import com.github.hyphen05.fuse.db.RgbDeviceAlias
import com.github.hyphen05.fuse.db.SavedDevice
import com.github.hyphen05.fuse.db.ColorCalibration

interface RgbDatabaseRepository {
    val allDeviceAliases: Flow<List<RgbDeviceAlias>>
    val allSavedDevices: Flow<List<SavedDevice>>
    val allCustomModes: Flow<List<CustomMode>>
    val allColorCalibrations: Flow<List<ColorCalibration>>

    suspend fun saveDeviceAlias(macAddress: String, name: String)
    suspend fun deleteDeviceAlias(macAddress: String)
    suspend fun insertSavedDevice(device: SavedDevice)
    suspend fun deleteSavedDevice(macAddress: String)
    suspend fun updateAutoConnect(macAddress: String, enabled: Boolean)
    suspend fun updateActiveControl(macAddress: String, enabled: Boolean)
    suspend fun updateDeviceRole(macAddress: String, role: String)
    suspend fun updateHueOffsetDegrees(macAddress: String, degrees: Float)
    suspend fun insertCustomMode(customMode: CustomMode)
    suspend fun deleteAllCustomModes()
    suspend fun insertCustomModes(customModes: List<CustomMode>)
    suspend fun renameCategory(oldName: String, newName: String)
    suspend fun getColorCalibration(macAddress: String): ColorCalibration?
    suspend fun insertColorCalibration(calibration: ColorCalibration)
    suspend fun deleteColorCalibration(macAddress: String)
}
