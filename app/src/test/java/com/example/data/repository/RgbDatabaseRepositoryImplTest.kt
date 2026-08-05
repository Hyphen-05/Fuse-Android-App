package com.example.data.repository

import com.example.db.ColorCalibration
import com.example.db.CustomMode
import com.example.db.RgbDao
import com.example.db.RgbDeviceAlias
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

    // saveDeviceAlias is the one repository method that isn't a straight pass-through — it builds
    // the RgbDeviceAlias from two loose arguments — so it's the useful delegation case to pin.
    @Test
    fun testSaveAndGetDeviceAliases() = runBlocking {
        classUnderTest.saveDeviceAlias("AA:BB:CC:DD:EE:FF", "Bedroom Strip")
        assertEquals(1, fakeDao.aliases.size)
        assertEquals("AA:BB:CC:DD:EE:FF", fakeDao.aliases[0].macAddress)
        assertEquals("Bedroom Strip", fakeDao.aliases[0].aliasName)
    }

    @Test
    fun testDeleteDeviceAlias() = runBlocking {
        fakeDao.aliases.add(RgbDeviceAlias("AA:BB:CC:DD:EE:FF", "Bedroom Strip"))
        classUnderTest.deleteDeviceAlias("AA:BB:CC:DD:EE:FF")
        assertEquals(0, fakeDao.aliases.size)
    }

    // Fake DAO implementation for testing
    class FakeRgbDao : RgbDao {
        val aliases = mutableListOf<RgbDeviceAlias>()

        override fun getAllDeviceAliases(): Flow<List<RgbDeviceAlias>> = flowOf(aliases)
        override suspend fun insertDeviceAlias(alias: RgbDeviceAlias) { aliases.add(alias) }
        override suspend fun deleteDeviceAlias(macAddress: String) { aliases.removeIf { it.macAddress == macAddress } }

        // Stubbed implementations for the rest (not strictly needed for the basic delegation test)
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
