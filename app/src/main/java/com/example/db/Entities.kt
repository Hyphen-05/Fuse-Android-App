package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rgb_presets")
data class RgbPreset(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val red: Int,
    val green: Int,
    val blue: Int,
    val brightness: Int,
    val modeIndex: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "device_aliases")
data class RgbDeviceAlias(
    @PrimaryKey val macAddress: String,
    val aliasName: String
)

@Entity(tableName = "saved_devices")
data class SavedDevice(
    @PrimaryKey val macAddress: String,
    val customName: String,
    val isAutoConnectEnabled: Boolean = true,
    val isActiveControlEnabled: Boolean = true
)

@Entity(tableName = "custom_modes")
data class CustomMode(
    @PrimaryKey val byteValue: Int,
    val name: String,
    val category: String,
    val direction: String, // "up", "down", or "none"
    val colors: String // Comma-separated list of colors e.g. "Red,Blue"
)

@Entity(tableName = "color_calibrations")
data class ColorCalibration(
    @PrimaryKey val macAddress: String,
    val timestamp: Long,
    val version: Int,
    val m11: Float, val m12: Float, val m13: Float,
    val m21: Float, val m22: Float, val m23: Float,
    val m31: Float, val m32: Float, val m33: Float,
    val samplesJson: String,
    val exposureTimeNs: Long,
    val sensitivityIso: Int,
    val whiteBalanceLocked: Boolean
)

