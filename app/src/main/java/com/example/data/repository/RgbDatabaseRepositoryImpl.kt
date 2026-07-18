package com.example.data.repository

import kotlinx.coroutines.flow.Flow
import com.example.db.CustomMode
import com.example.db.RgbPreset
import com.example.db.RgbDeviceAlias
import com.example.db.SavedDevice
import com.example.db.ColorCalibration
import com.example.db.RgbDao
import com.example.domain.repository.RgbDatabaseRepository

class RgbDatabaseRepositoryImpl(private val rgbDao: RgbDao) : RgbDatabaseRepository {
    override val allPresets: Flow<List<RgbPreset>> = rgbDao.getAllPresets()
    override val allDeviceAliases: Flow<List<RgbDeviceAlias>> = rgbDao.getAllDeviceAliases()
    override val allSavedDevices: Flow<List<SavedDevice>> = rgbDao.getAllSavedDevices()
    override val allCustomModes: Flow<List<CustomMode>> = rgbDao.getAllCustomModes()
    override val allColorCalibrations: Flow<List<ColorCalibration>> = rgbDao.getAllColorCalibrations()

    override suspend fun insertPreset(preset: RgbPreset) {
        rgbDao.insertPreset(preset)
    }
    override suspend fun deletePresetById(id: Int) {
        rgbDao.deletePresetById(id)
    }
    override suspend fun saveDeviceAlias(macAddress: String, name: String) {
        rgbDao.insertDeviceAlias(RgbDeviceAlias(macAddress, name))
    }
    override suspend fun deleteDeviceAlias(macAddress: String) {
        rgbDao.deleteDeviceAlias(macAddress)
    }
    override suspend fun insertSavedDevice(device: SavedDevice) {
        rgbDao.insertSavedDevice(device)
    }
    override suspend fun deleteSavedDevice(macAddress: String) {
        rgbDao.deleteSavedDevice(macAddress)
    }
    override suspend fun updateAutoConnect(macAddress: String, enabled: Boolean) {
        rgbDao.updateAutoConnect(macAddress, enabled)
    }
    override suspend fun updateActiveControl(macAddress: String, enabled: Boolean) {
        rgbDao.updateActiveControl(macAddress, enabled)
    }
    override suspend fun insertCustomMode(customMode: CustomMode) {
        rgbDao.insertCustomMode(customMode)
    }
    override suspend fun deleteAllCustomModes() {
        rgbDao.deleteAllCustomModes()
    }
    override suspend fun insertCustomModes(customModes: List<CustomMode>) {
        rgbDao.insertCustomModes(customModes)
    }
    override suspend fun renameCategory(oldName: String, newName: String) {
        rgbDao.renameCategory(oldName, newName)
    }
    override suspend fun getColorCalibration(macAddress: String): ColorCalibration? {
        return rgbDao.getColorCalibration(macAddress)
    }
    override suspend fun insertColorCalibration(calibration: ColorCalibration) {
        rgbDao.insertColorCalibration(calibration)
    }
    override suspend fun deleteColorCalibration(macAddress: String) {
        rgbDao.deleteColorCalibration(macAddress)
    }
}
