package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RgbDao {
    @Query("SELECT * FROM rgb_presets ORDER BY timestamp DESC")
    fun getAllPresets(): Flow<List<RgbPreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: RgbPreset)

    @Query("DELETE FROM rgb_presets WHERE id = :id")
    suspend fun deletePresetById(id: Int)

    @Query("SELECT * FROM device_aliases")
    fun getAllDeviceAliases(): Flow<List<RgbDeviceAlias>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeviceAlias(alias: RgbDeviceAlias)

    @Query("DELETE FROM device_aliases WHERE macAddress = :macAddress")
    suspend fun deleteDeviceAlias(macAddress: String)

    @Query("SELECT * FROM saved_devices")
    fun getAllSavedDevices(): Flow<List<SavedDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedDevice(device: SavedDevice)

    @Query("DELETE FROM saved_devices WHERE macAddress = :macAddress")
    suspend fun deleteSavedDevice(macAddress: String)

    @Query("UPDATE saved_devices SET isAutoConnectEnabled = :enabled WHERE macAddress = :macAddress")
    suspend fun updateAutoConnect(macAddress: String, enabled: Boolean)

    @Query("UPDATE saved_devices SET isActiveControlEnabled = :enabled WHERE macAddress = :macAddress")
    suspend fun updateActiveControl(macAddress: String, enabled: Boolean)

    @Query("SELECT * FROM custom_modes ORDER BY byteValue ASC")
    fun getAllCustomModes(): Flow<List<CustomMode>>

    @Query("DELETE FROM custom_modes")
    suspend fun deleteAllCustomModes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomMode(customMode: CustomMode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomModes(customModes: List<CustomMode>)

    @Query("UPDATE custom_modes SET category = :newName WHERE category = :oldName")
    suspend fun renameCategory(oldName: String, newName: String)

    @Query("SELECT * FROM color_calibrations WHERE macAddress = :macAddress")
    suspend fun getColorCalibration(macAddress: String): ColorCalibration?

    @Query("SELECT * FROM color_calibrations")
    fun getAllColorCalibrations(): Flow<List<ColorCalibration>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertColorCalibration(calibration: ColorCalibration)

    @Query("DELETE FROM color_calibrations WHERE macAddress = :macAddress")
    suspend fun deleteColorCalibration(macAddress: String)
}
