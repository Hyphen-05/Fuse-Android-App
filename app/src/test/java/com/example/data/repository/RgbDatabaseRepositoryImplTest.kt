package com.example.data.repository

import com.example.db.ColorCalibration
import com.example.db.CustomMode
import com.example.db.RgbDao
import com.example.db.RgbDeviceAlias
import com.example.db.RgbPreset
import com.example.db.SavedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Reason for plain JUnit: RgbDatabaseRepositoryImpl delegates entirely to RgbDao, 
// which is injected via constructor. By creating a fake implementation of RgbDao, 
// we can test the repository logic without any Android framework dependencies or an actual database.
class RgbDatabaseRepositoryImplTest {

    private lateinit var fakeDao: FakeRgbDao
    private lateinit var classUnderTest: RgbDatabaseRepositoryImpl

    @Before
    fun setup() {
        fakeDao = FakeRgbDao()
        classUnderTest = RgbDatabaseRepositoryImpl(fakeDao)
    }

    @Test
    fun testInsertAndGetPresets() = runBlocking {
        val preset = RgbPreset(name = "Test", red = 255, green = 0, blue = 0, brightness = 100)
        classUnderTest.insertPreset(preset)
        assertEquals(1, fakeDao.presets.size)
        assertEquals("Test", fakeDao.presets[0].name)
    }

    @Test
    fun testDeletePreset() = runBlocking {
        val preset = RgbPreset(id = 1, name = "Test", red = 255, green = 0, blue = 0, brightness = 100)
        fakeDao.presets.add(preset)
        classUnderTest.deletePresetById(1)
        assertEquals(0, fakeDao.presets.size)
    }

    // Fake DAO implementation for testing
    class FakeRgbDao : RgbDao {
        val presets = mutableListOf<RgbPreset>()

        override fun getAllPresets(): Flow<List<RgbPreset>> = flowOf(presets)
        override suspend fun insertPreset(preset: RgbPreset) { presets.add(preset) }
        override suspend fun deletePresetById(presetId: Int) { presets.removeIf { it.id == presetId } }

        // Stubbed implementations for the rest (not strictly needed for the basic delegation test)
        override fun getAllDeviceAliases(): Flow<List<RgbDeviceAlias>> = flowOf(emptyList())
        override suspend fun insertDeviceAlias(alias: RgbDeviceAlias) {}
        override suspend fun deleteDeviceAlias(macAddress: String) {}

        override fun getAllSavedDevices(): Flow<List<SavedDevice>> = flowOf(emptyList())
        override suspend fun insertSavedDevice(device: SavedDevice) {}
        override suspend fun deleteSavedDevice(macAddress: String) {}
        override suspend fun updateAutoConnect(macAddress: String, enabled: Boolean) {}
        override suspend fun updateActiveControl(macAddress: String, enabled: Boolean) {}
        override suspend fun updateDeviceRole(macAddress: String, role: String) {}
        override suspend fun updateHueOffsetDegrees(macAddress: String, degrees: Float) {}

        override fun getAllCustomModes(): Flow<List<CustomMode>> = flowOf(emptyList())
        override suspend fun insertCustomMode(customMode: CustomMode) {}
        override suspend fun deleteAllCustomModes() {}
        override suspend fun insertCustomModes(customModes: List<CustomMode>) {}
        override suspend fun renameCategory(oldName: String, newName: String) {}

        override fun getAllColorCalibrations(): Flow<List<ColorCalibration>> = flowOf(emptyList())
        override suspend fun getColorCalibration(macAddress: String): ColorCalibration? = null
        override suspend fun insertColorCalibration(calibration: ColorCalibration) {}
        override suspend fun deleteColorCalibration(macAddress: String) {}
    }
}
