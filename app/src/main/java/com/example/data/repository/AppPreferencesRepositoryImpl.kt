package com.example.data.repository

import android.content.Context
import com.example.domain.repository.AppPreferencesRepository
import com.example.domain.model.AppScene
import org.json.JSONArray
import org.json.JSONObject

class AppPreferencesRepositoryImpl(private val context: Context) : AppPreferencesRepository {
    private val statePrefs = context.getSharedPreferences("rgb_state_prefs", Context.MODE_PRIVATE)
    private val ambiancePrefs = context.getSharedPreferences("ambiance_settings_prefs", Context.MODE_PRIVATE)
    private val pacingPrefs = context.getSharedPreferences("ble_pacing_prefs", Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("rgb_prefs", Context.MODE_PRIVATE)
    private val calibrationPrefs = context.getSharedPreferences("ble_audio_calibration_prefs", Context.MODE_PRIVATE)
    private val cctCalibrationPrefs = context.getSharedPreferences("cct_calibration_prefs", Context.MODE_PRIVATE)

    override fun getAppStatePrefBoolean(key: String, defValue: Boolean) = statePrefs.getBoolean(key, defValue)
    override fun putAppStatePrefBoolean(key: String, value: Boolean) { statePrefs.edit().putBoolean(key, value).apply() }
    override fun getAppStatePrefInt(key: String, defValue: Int) = statePrefs.getInt(key, defValue)
    override fun putAppStatePrefInt(key: String, value: Int) { statePrefs.edit().putInt(key, value).apply() }
    override fun getAppStatePrefFloat(key: String, defValue: Float) = statePrefs.getFloat(key, defValue)
    override fun putAppStatePrefFloat(key: String, value: Float) { statePrefs.edit().putFloat(key, value).apply() }
    override fun getAppStatePrefLong(key: String, defValue: Long) = statePrefs.getLong(key, defValue)
    override fun putAppStatePrefLong(key: String, value: Long) { statePrefs.edit().putLong(key, value).apply() }
    override fun getAppStatePrefString(key: String, defValue: String?) = statePrefs.getString(key, defValue)
    override fun putAppStatePrefString(key: String, value: String) { statePrefs.edit().putString(key, value).apply() }
    override fun removeAppStatePref(key: String) { statePrefs.edit().remove(key).apply() }

    override fun getAmbiancePrefInt(key: String, defValue: Int) = ambiancePrefs.getInt(key, defValue)
    override fun putAmbiancePrefInt(key: String, value: Int) { ambiancePrefs.edit().putInt(key, value).apply() }
    override fun getAmbiancePrefFloat(key: String, defValue: Float) = ambiancePrefs.getFloat(key, defValue)
    override fun putAmbiancePrefFloat(key: String, value: Float) { ambiancePrefs.edit().putFloat(key, value).apply() }
    override fun getAmbiancePrefString(key: String, defValue: String?) = ambiancePrefs.getString(key, defValue)
    override fun putAmbiancePrefString(key: String, value: String) { ambiancePrefs.edit().putString(key, value).apply() }

    override fun getPacingPrefInt(key: String, defValue: Int) = pacingPrefs.getInt(key, defValue)
    override fun putPacingPrefInt(key: String, value: Int) { pacingPrefs.edit().putInt(key, value).apply() }
    override fun clearPacingPrefs() { pacingPrefs.edit().clear().apply() }

    override fun getProtocolOverrideAll() = prefs.all
    override fun removeProtocolOverride(key: String) { prefs.edit().remove(key).apply() }
    override fun putProtocolOverrideString(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    override fun getCalibrationDelayPrefInt(key: String, defValue: Int) = calibrationPrefs.getInt(key, defValue)
    override fun putCalibrationDelayPrefInt(key: String, value: Int) { calibrationPrefs.edit().putInt(key, value).apply() }
    override fun clearCalibrationDelayPrefs() { calibrationPrefs.edit().clear().apply() }

    override fun getCctCalibrationAll() = cctCalibrationPrefs.all
    override fun removeCctCalibration(key: String) { cctCalibrationPrefs.edit().remove(key).apply() }
    override fun putCctCalibrationString(key: String, value: String) { cctCalibrationPrefs.edit().putString(key, value).apply() }
    override fun clearCctCalibrationPrefs() { cctCalibrationPrefs.edit().clear().apply() }

    private fun saveScenesToPrefs(scenes: List<AppScene>) {
        val prefs = context.getSharedPreferences("scenes_prefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (s in scenes) {
            jsonArray.put(s.toJson())
        }
        prefs.edit().putString("scenes_json", jsonArray.toString()).apply()
    }

    private fun loadScenesFromPrefs(): List<AppScene> {
        val prefs = context.getSharedPreferences("scenes_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("scenes_json", null) ?: return emptyList()
        val list = mutableListOf<AppScene>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                try {
                    val item = jsonArray.getJSONObject(i)
                    list.add(AppScene.fromJson(item))
                } catch (e: Exception) {
                    android.util.Log.e("SceneManager", "Error parsing AppScene at index $i", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SceneManager", "Error loading scenes from SharedPreferences", e)
        }
        return list
    }

    override fun loadScenes() = loadScenesFromPrefs()
    override fun saveScenes(scenes: List<AppScene>) = saveScenesToPrefs(scenes)
}
