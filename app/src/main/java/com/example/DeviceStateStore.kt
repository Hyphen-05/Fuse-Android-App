package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/**
 * Represents the last known deliberate manual state of a device.
 * Stores power status, RGB color channels, CCT warmth, and brightness.
 */
data class DeviceState(
    val power: Boolean,
    val red: Int,
    val green: Int,
    val blue: Int,
    val warmth: Int,
    val brightness: Int
)

/**
 * Storage manager for preserving the manual state of individual BLE RGB devices.
 * Uses Jetpack Preferences DataStore to provide asynchronous, non-blocking, and thread-safe persistence.
 *
 * Why Preferences DataStore was chosen over Proto DataStore:
 * 1. Simplicity & Maintenance: Preferences DataStore operates directly on primitive key-value pairs,
 *    eliminating the overhead of defining a Protocol Buffer (.proto) schema and dealing with the
 *    Protobuf Gradle compilation step.
 * 2. Dynamic Keying: Since we want to save manual states per device MAC address, Preferences DataStore
 *    allows us to dynamically prefix/parametrize keys by MAC address (e.g. "${macAddress}_power"),
 *    reusing a single DataStore instance dynamically for any number of devices.
 */
class DeviceStateStore(private val context: Context) {

    companion object {
        // Singleton delegate extension to guarantee a single instance of DataStore per app context
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "device_manual_states")

        private const val AUTOMATION_SUFFIX = "_automation"
    }

    // Dynamic Keys based on device MAC Address
    private fun powerKey(mac: String) = booleanPreferencesKey("${mac}_power")
    private fun redKey(mac: String) = intPreferencesKey("${mac}_red")
    private fun greenKey(mac: String) = intPreferencesKey("${mac}_green")
    private fun blueKey(mac: String) = intPreferencesKey("${mac}_blue")
    private fun warmthKey(mac: String) = intPreferencesKey("${mac}_warmth")
    private fun brightnessKey(mac: String) = intPreferencesKey("${mac}_brightness")

    /**
     * Which automation (if any) currently owns this device, and therefore owes it a restore.
     *
     * This lives beside the colours on purpose. The colours were always persisted, but the "is this
     * device under automation" flag used to exist only in a `ConcurrentHashMap` in the ViewModel —
     * and ambiance runs as a foreground service while the user is in another app, exactly when
     * Android is most likely to destroy the Activity and the ViewModel with it. The map came back
     * empty, the restore loop iterated nothing, and the strip silently kept its automation colour.
     * Persisting the flag is what makes the restore survive that.
     */
    private fun automationKey(mac: String) = stringPreferencesKey("${mac}_automation")


    /**
     * Saves the deliberate manual state of a device.
     */
    suspend fun saveState(
        macAddress: String,
        power: Boolean,
        red: Int,
        green: Int,
        blue: Int,
        warmth: Int,
        brightness: Int
    ) {
        context.dataStore.edit { preferences ->
            preferences[powerKey(macAddress)] = power
            preferences[redKey(macAddress)] = red.coerceIn(0, 255)
            preferences[greenKey(macAddress)] = green.coerceIn(0, 255)
            preferences[blueKey(macAddress)] = blue.coerceIn(0, 255)
            preferences[warmthKey(macAddress)] = warmth.coerceIn(0, 100)
            preferences[brightnessKey(macAddress)] = brightness.coerceIn(0, 100)
        }
    }

    /**
     * Overloaded saveState accepting a RGB Triple representing the color.
     */
    suspend fun saveState(
        macAddress: String,
        power: Boolean,
        color: Triple<Int, Int, Int>,
        warmth: Int,
        brightness: Int
    ) {
        saveState(
            macAddress = macAddress,
            power = power,
            red = color.first,
            green = color.second,
            blue = color.third,
            warmth = warmth,
            brightness = brightness
        )
    }

    /**
     * Retrieves the last known manual state of a device.
     * Returns null if no state has been saved yet.
     */
    suspend fun getState(macAddress: String): DeviceState? {
        val preferences = context.dataStore.data.firstOrNull() ?: return null
        return mapPreferencesToDeviceState(macAddress, preferences)
    }

    /**
     * Provides a cold Flow of the device manual state for reactive updates.
     * Emits null if no state has been saved yet.
     */
    fun observeState(macAddress: String): Flow<DeviceState?> {
        return context.dataStore.data.map { preferences ->
            mapPreferencesToDeviceState(macAddress, preferences)
        }
    }

    /**
     * Marks a device as owned by an automation, so a restore is still owed after a process or
     * ViewModel death. Does not touch the saved colours.
     */
    suspend fun setAutomation(macAddress: String, mode: String) {
        context.dataStore.edit { preferences -> preferences[automationKey(macAddress)] = mode }
    }

    /** Clears the automation marker once the restore has actually been performed. */
    suspend fun clearAutomation(macAddress: String) {
        context.dataStore.edit { preferences -> preferences.remove(automationKey(macAddress)) }
    }

    suspend fun getAutomation(macAddress: String): String? {
        val preferences = context.dataStore.data.firstOrNull() ?: return null
        return preferences[automationKey(macAddress)]
    }

    /**
     * Every device still marked as under automation, as MAC to mode name. Used at startup to
     * rebuild the in-memory ownership map so a restore survives ViewModel recreation.
     */
    suspend fun allAutomations(): Map<String, String> {
        val preferences = context.dataStore.data.firstOrNull() ?: return emptyMap()
        return preferences.asMap().mapNotNull { (key, value) ->
            if (key.name.endsWith(AUTOMATION_SUFFIX) && value is String) {
                key.name.removeSuffix(AUTOMATION_SUFFIX) to value
            } else {
                null
            }
        }.toMap()
    }

    /**
     * Clears all saved manual state for a device when forgotten or removed.
     */
    suspend fun clearState(macAddress: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(powerKey(macAddress))
            preferences.remove(redKey(macAddress))
            preferences.remove(greenKey(macAddress))
            preferences.remove(blueKey(macAddress))
            preferences.remove(warmthKey(macAddress))
            preferences.remove(brightnessKey(macAddress))
            preferences.remove(automationKey(macAddress))
        }
    }

    /**
     * Helper to map DataStore Preferences to a typed DeviceState if it exists.
     */
    private fun mapPreferencesToDeviceState(macAddress: String, preferences: Preferences): DeviceState? {
        val power = preferences[powerKey(macAddress)] ?: return null
        val red = preferences[redKey(macAddress)] ?: 255
        val green = preferences[greenKey(macAddress)] ?: 0
        val blue = preferences[blueKey(macAddress)] ?: 128
        val warmth = preferences[warmthKey(macAddress)] ?: 50
        val brightness = preferences[brightnessKey(macAddress)] ?: 80

        return DeviceState(
            power = power,
            red = red,
            green = green,
            blue = blue,
            warmth = warmth,
            brightness = brightness
        )
    }
}
