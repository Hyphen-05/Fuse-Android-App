package com.example.domain.repository

import com.example.domain.model.AppScene

interface AppPreferencesRepository {
    fun getAppStatePrefBoolean(key: String, defValue: Boolean): Boolean
    fun putAppStatePrefBoolean(key: String, value: Boolean)
    fun getAppStatePrefInt(key: String, defValue: Int): Int
    fun putAppStatePrefInt(key: String, value: Int)
    fun getAppStatePrefFloat(key: String, defValue: Float): Float
    fun putAppStatePrefFloat(key: String, value: Float)
    fun getAppStatePrefLong(key: String, defValue: Long): Long
    fun putAppStatePrefLong(key: String, value: Long)
    fun getAppStatePrefString(key: String, defValue: String?): String?
    fun putAppStatePrefString(key: String, value: String)
    fun removeAppStatePref(key: String)

    fun getAmbiancePrefInt(key: String, defValue: Int): Int
    fun putAmbiancePrefInt(key: String, value: Int)
    fun getAmbiancePrefFloat(key: String, defValue: Float): Float
    fun putAmbiancePrefFloat(key: String, value: Float)
    fun getAmbiancePrefString(key: String, defValue: String?): String?
    fun putAmbiancePrefString(key: String, value: String)

    fun getPacingPrefInt(key: String, defValue: Int): Int
    fun putPacingPrefInt(key: String, value: Int)
    fun clearPacingPrefs()

    fun getProtocolOverrideAll(): Map<String, *>
    fun removeProtocolOverride(key: String)
    fun putProtocolOverrideString(key: String, value: String)

    fun getCalibrationDelayPrefInt(key: String, defValue: Int): Int
    fun putCalibrationDelayPrefInt(key: String, value: Int)
    fun clearCalibrationDelayPrefs()

    fun getCctCalibrationAll(): Map<String, *>
    fun removeCctCalibration(key: String)
    fun putCctCalibrationString(key: String, value: String)
    fun clearCctCalibrationPrefs()
    
    fun loadScenes(): List<AppScene>
    fun saveScenes(scenes: List<AppScene>)
}
