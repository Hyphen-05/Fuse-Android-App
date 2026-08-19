package com.example.ambiance

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A saved set of ambiance tuning values. Built-ins carry [isCustom] false; saved ones true. */
data class AmbiancePreset(
    val id: String,
    val name: String,
    val description: String,
    val isCustom: Boolean = false,
    val responseSpeed: Float,
    val smoothnessMs: Int,
    val saturationBoost: Float,
    val brightnessCompensation: Float,
    val sceneCutSensitivity: Float,
    val noiseDeadband: Float
)

/**
 * The single source of truth for the user's saved ambiance presets.
 *
 * ## Why this exists
 *
 * The list used to be read straight out of SharedPreferences by the UI, once, into a
 * `remember { loadCustomPresetsFromPrefs(context) }` on the Ambiance tab — while the *fine-tune
 * panel* saved new presets by its own load-modify-save against the same prefs file. The two never
 * spoke, so **saving a preset did not make it appear in the list** until something else forced that
 * composable to reload. That is the bug this fixes (IMPROVEMENT_PLAN 3.3); relocating the `org.json`
 * out of the UI layer is the tidying that comes with it.
 *
 * A [StateFlow] rather than a repository read: the point is that every screen observes the same
 * list, so a save anywhere shows up everywhere immediately.
 *
 * Deliberately an object with an explicit [Context] per call rather than an `AppContainer`
 * dependency — the two callers are composables reading `LocalContext`, and threading a constructed
 * repository down to them would be a bigger change than the bug warrants. The prefs file and key are
 * unchanged, so presets saved by older builds load as they always did.
 */
object AmbiancePresetStore {

    private const val PREFS_NAME = "ambiance_presets_prefs"
    private const val KEY = "custom_presets_json"

    private val _presets = MutableStateFlow<List<AmbiancePreset>>(emptyList())

    /** The saved custom presets. Empty until [load] has run once. */
    val presets: StateFlow<List<AmbiancePreset>> = _presets.asStateFlow()

    @Volatile private var loaded = false

    /** Reads prefs into the flow on first call; later calls are free. Safe to call from composition. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _presets.value = readFromPrefs(context)
            loaded = true
        }
    }

    /**
     * Adds [preset], replacing any existing one with the same name (case-insensitive) — which is the
     * rule the fine-tune panel's save button already applied, kept so re-saving a name overwrites
     * rather than accumulating duplicates.
     */
    fun save(context: Context, preset: AmbiancePreset) = mutate(context) { current ->
        current.filter { !it.name.equals(preset.name, ignoreCase = true) } + preset
    }

    fun delete(context: Context, id: String) = mutate(context) { current ->
        current.filter { it.id != id }
    }

    fun rename(context: Context, id: String, newName: String) = mutate(context) { current ->
        current.map { if (it.id == id) it.copy(name = newName) else it }
    }

    private fun mutate(context: Context, transform: (List<AmbiancePreset>) -> List<AmbiancePreset>) {
        load(context)
        val updated = transform(_presets.value)
        _presets.value = updated
        writeToPrefs(context, updated)
    }

    private fun readFromPrefs(context: Context): List<AmbiancePreset> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return decode(json)
    }

    private fun writeToPrefs(context: Context, presets: List<AmbiancePreset>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(presets)).apply()
    }

    /** Pure, so the round-trip can be tested without touching prefs. */
    fun encode(presets: List<AmbiancePreset>): String {
        val array = JSONArray()
        for (p in presets) {
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("description", p.description)
                    put("responseSpeed", p.responseSpeed.toDouble())
                    put("smoothnessMs", p.smoothnessMs)
                    put("saturationBoost", p.saturationBoost.toDouble())
                    put("brightnessCompensation", p.brightnessCompensation.toDouble())
                    put("sceneCutSensitivity", p.sceneCutSensitivity.toDouble())
                    put("noiseDeadband", p.noiseDeadband.toDouble())
                }
            )
        }
        return array.toString()
    }

    /**
     * Tolerant by design: a malformed blob returns what parsed rather than throwing, and the two
     * fields added after this format shipped keep their defaults when absent. Losing the whole list
     * because one entry is bad would be a worse failure than losing the bad entry.
     */
    fun decode(json: String): List<AmbiancePreset> {
        val list = mutableListOf<AmbiancePreset>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AmbiancePreset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", "Custom sliders config preset"),
                        isCustom = true,
                        responseSpeed = obj.getDouble("responseSpeed").toFloat(),
                        smoothnessMs = obj.getInt("smoothnessMs"),
                        saturationBoost = obj.getDouble("saturationBoost").toFloat(),
                        brightnessCompensation = obj.getDouble("brightnessCompensation").toFloat(),
                        sceneCutSensitivity = obj.optDouble("sceneCutSensitivity", 110.0).toFloat(),
                        noiseDeadband = obj.optDouble("noiseDeadband", 0.10).toFloat()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("AmbiancePresetStore", "Error decoding custom presets", e)
        }
        return list
    }
}
