package com.example.domain.model

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

typealias ProceduralSceneParams = com.example.core.animation.ProceduralSceneParams

data class DeviceSceneState(
    val groupASelection: String? = null,
    val colorR: Int? = null,
    val colorG: Int? = null,
    val colorB: Int? = null,
    val cctWarmth: Int? = null,
    val modeIndex: Int? = null,
    val modeSpeed: Int? = null,
    val audioPreset: String? = null,
    val audioAttack: Float? = null,
    val audioDecay: Float? = null,
    val audioFlash: Float? = null,
    val musicMode: String? = null,
    val ambianceIsOn: Boolean? = null,
    val ambiancePreset: String? = null,
    val ambianceResponseSpeed: Float? = null,
    val ambianceSmoothnessMs: Int? = null,
    val ambianceSaturationBoost: Float? = null,
    val ambianceBrightnessCompensation: Float? = null,
    val ambianceUpdateRateCapFps: Int? = null,
    val ambianceSceneCutSensitivity: Float? = null,
    val ambianceNoiseDeadband: Float? = null,
    val brightness: Int? = null,
    val isPowerOn: Boolean? = null,
    val noiseGateThreshold: Float? = null,
    val bassGain: Float? = null,
    val midGain: Float? = null,
    val highGain: Float? = null,
    val isAutoGainEnabled: Boolean? = null,
    val isPaletteCyclingEnabled: Boolean? = null,
    val isLogarithmicScalingEnabled: Boolean? = null,
    val audioGammaExponent: Float? = null,
    val visualizerMinBrightness: Float? = null,
    val visualizerColorSpeed: Float? = null,
    val bluetoothDelayMs: Int? = null,
    val animatedSequence: ProceduralSceneParams? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        groupASelection?.let { obj.put("groupASelection", it) }
        colorR?.let { obj.put("colorR", it) }
        colorG?.let { obj.put("colorG", it) }
        colorB?.let { obj.put("colorB", it) }
        cctWarmth?.let { obj.put("cctWarmth", it) }
        modeIndex?.let { obj.put("modeIndex", it) }
        modeSpeed?.let { obj.put("modeSpeed", it) }
        audioPreset?.let { obj.put("audioPreset", it) }
        audioAttack?.let { obj.put("audioAttack", it.toDouble()) }
        audioDecay?.let { obj.put("audioDecay", it.toDouble()) }
        audioFlash?.let { obj.put("audioFlash", it.toDouble()) }
        musicMode?.let { obj.put("musicMode", it) }
        ambianceIsOn?.let { obj.put("ambianceIsOn", it) }
        ambiancePreset?.let { obj.put("ambiancePreset", it) }
        ambianceResponseSpeed?.let { obj.put("ambianceResponseSpeed", it.toDouble()) }
        ambianceSmoothnessMs?.let { obj.put("ambianceSmoothnessMs", it) }
        ambianceSaturationBoost?.let { obj.put("ambianceSaturationBoost", it.toDouble()) }
        ambianceBrightnessCompensation?.let { obj.put("ambianceBrightnessCompensation", it.toDouble()) }
        ambianceUpdateRateCapFps?.let { obj.put("ambianceUpdateRateCapFps", it) }
        ambianceSceneCutSensitivity?.let { obj.put("ambianceSceneCutSensitivity", it.toDouble()) }
        ambianceNoiseDeadband?.let { obj.put("ambianceNoiseDeadband", it.toDouble()) }
        brightness?.let { obj.put("brightness", it) }
        isPowerOn?.let { obj.put("isPowerOn", it) }
        noiseGateThreshold?.let { obj.put("noiseGateThreshold", it.toDouble()) }
        bassGain?.let { obj.put("bassGain", it.toDouble()) }
        midGain?.let { obj.put("midGain", it.toDouble()) }
        highGain?.let { obj.put("highGain", it.toDouble()) }
        isAutoGainEnabled?.let { obj.put("isAutoGainEnabled", it) }
        isPaletteCyclingEnabled?.let { obj.put("isPaletteCyclingEnabled", it) }
        isLogarithmicScalingEnabled?.let { obj.put("isLogarithmicScalingEnabled", it) }
        audioGammaExponent?.let { obj.put("audioGammaExponent", it.toDouble()) }
        visualizerMinBrightness?.let { obj.put("visualizerMinBrightness", it.toDouble()) }
        visualizerColorSpeed?.let { obj.put("visualizerColorSpeed", it.toDouble()) }
        bluetoothDelayMs?.let { obj.put("bluetoothDelayMs", it) }
        animatedSequence?.let { obj.put("animatedSequence", it.toJson()) }
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): DeviceSceneState {
            return DeviceSceneState(
                groupASelection = if (obj.has("groupASelection") && !obj.isNull("groupASelection")) obj.optString("groupASelection", "").takeIf { it.isNotEmpty() && it != "null" } else null,
                colorR = if (obj.has("colorR") && !obj.isNull("colorR")) obj.optInt("colorR") else null,
                colorG = if (obj.has("colorG") && !obj.isNull("colorG")) obj.optInt("colorG") else null,
                colorB = if (obj.has("colorB") && !obj.isNull("colorB")) obj.optInt("colorB") else null,
                cctWarmth = if (obj.has("cctWarmth") && !obj.isNull("cctWarmth")) obj.optInt("cctWarmth") else null,
                modeIndex = if (obj.has("modeIndex") && !obj.isNull("modeIndex")) obj.optInt("modeIndex") else null,
                modeSpeed = if (obj.has("modeSpeed") && !obj.isNull("modeSpeed")) obj.optInt("modeSpeed") else null,
                audioPreset = if (obj.has("audioPreset") && !obj.isNull("audioPreset")) obj.optString("audioPreset", "").takeIf { it.isNotEmpty() && it != "null" } else null,
                audioAttack = if (obj.has("audioAttack") && !obj.isNull("audioAttack")) obj.optDouble("audioAttack", 0.0).toFloat() else null,
                audioDecay = if (obj.has("audioDecay") && !obj.isNull("audioDecay")) obj.optDouble("audioDecay", 0.0).toFloat() else null,
                audioFlash = if (obj.has("audioFlash") && !obj.isNull("audioFlash")) obj.optDouble("audioFlash", 0.0).toFloat() else null,
                musicMode = if (obj.has("musicMode") && !obj.isNull("musicMode")) obj.optString("musicMode", "").takeIf { it.isNotEmpty() && it != "null" } else null,
                ambianceIsOn = if (obj.has("ambianceIsOn") && !obj.isNull("ambianceIsOn")) obj.optBoolean("ambianceIsOn") else null,
                ambiancePreset = if (obj.has("ambiancePreset") && !obj.isNull("ambiancePreset")) obj.optString("ambiancePreset", "").takeIf { it.isNotEmpty() && it != "null" } else null,
                ambianceResponseSpeed = if (obj.has("ambianceResponseSpeed") && !obj.isNull("ambianceResponseSpeed")) obj.optDouble("ambianceResponseSpeed", 0.0).toFloat() else null,
                ambianceSmoothnessMs = if (obj.has("ambianceSmoothnessMs") && !obj.isNull("ambianceSmoothnessMs")) obj.optInt("ambianceSmoothnessMs") else null,
                ambianceSaturationBoost = if (obj.has("ambianceSaturationBoost") && !obj.isNull("ambianceSaturationBoost")) obj.optDouble("ambianceSaturationBoost", 0.0).toFloat() else null,
                ambianceBrightnessCompensation = if (obj.has("ambianceBrightnessCompensation") && !obj.isNull("ambianceBrightnessCompensation")) obj.optDouble("ambianceBrightnessCompensation", 0.0).toFloat() else null,
                ambianceUpdateRateCapFps = if (obj.has("ambianceUpdateRateCapFps") && !obj.isNull("ambianceUpdateRateCapFps")) obj.optInt("ambianceUpdateRateCapFps") else null,
                ambianceSceneCutSensitivity = if (obj.has("ambianceSceneCutSensitivity") && !obj.isNull("ambianceSceneCutSensitivity")) obj.optDouble("ambianceSceneCutSensitivity", 0.0).toFloat() else null,
                ambianceNoiseDeadband = if (obj.has("ambianceNoiseDeadband") && !obj.isNull("ambianceNoiseDeadband")) obj.optDouble("ambianceNoiseDeadband", 0.0).toFloat() else null,
                brightness = if (obj.has("brightness") && !obj.isNull("brightness")) obj.optInt("brightness") else null,
                isPowerOn = if (obj.has("isPowerOn") && !obj.isNull("isPowerOn")) obj.optBoolean("isPowerOn") else null,
                noiseGateThreshold = if (obj.has("noiseGateThreshold") && !obj.isNull("noiseGateThreshold")) obj.optDouble("noiseGateThreshold", 0.0).toFloat() else null,
                bassGain = if (obj.has("bassGain") && !obj.isNull("bassGain")) obj.optDouble("bassGain", 0.0).toFloat() else null,
                midGain = if (obj.has("midGain") && !obj.isNull("midGain")) obj.optDouble("midGain", 0.0).toFloat() else null,
                highGain = if (obj.has("highGain") && !obj.isNull("highGain")) obj.optDouble("highGain", 0.0).toFloat() else null,
                isAutoGainEnabled = if (obj.has("isAutoGainEnabled") && !obj.isNull("isAutoGainEnabled")) obj.optBoolean("isAutoGainEnabled") else null,
                isPaletteCyclingEnabled = if (obj.has("isPaletteCyclingEnabled") && !obj.isNull("isPaletteCyclingEnabled")) obj.optBoolean("isPaletteCyclingEnabled") else null,
                isLogarithmicScalingEnabled = if (obj.has("isLogarithmicScalingEnabled") && !obj.isNull("isLogarithmicScalingEnabled")) obj.optBoolean("isLogarithmicScalingEnabled") else null,
                audioGammaExponent = if (obj.has("audioGammaExponent") && !obj.isNull("audioGammaExponent")) obj.optDouble("audioGammaExponent", 0.0).toFloat() else null,
                visualizerMinBrightness = if (obj.has("visualizerMinBrightness") && !obj.isNull("visualizerMinBrightness")) obj.optDouble("visualizerMinBrightness", 0.0).toFloat() else null,
                visualizerColorSpeed = if (obj.has("visualizerColorSpeed") && !obj.isNull("visualizerColorSpeed")) obj.optDouble("visualizerColorSpeed", 0.0).toFloat() else null,
                bluetoothDelayMs = if (obj.has("bluetoothDelayMs") && !obj.isNull("bluetoothDelayMs")) obj.optInt("bluetoothDelayMs") else null,
                animatedSequence = if (obj.has("animatedSequence") && !obj.isNull("animatedSequence")) ProceduralSceneParams.fromJson(obj.getJSONObject("animatedSequence")) else null
            )
        }
    }
}

data class AppScene(
    val id: String,
    val name: String,
    val targetScope: String = "ALL_DEVICES",
    val selectedDeviceMacs: List<String>? = null,
    val state: DeviceSceneState = DeviceSceneState(),
    val isPerDevice: Boolean = false,
    val sharedState: DeviceSceneState? = null,
    val deviceStates: Map<String, DeviceSceneState>? = null,
    val chainedSceneId: String? = null,
    val chainedSceneReverseId: String? = null,
    val chainedSceneDelaySeconds: Int? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("targetScope", targetScope)
        if (selectedDeviceMacs != null) {
            val arr = JSONArray()
            selectedDeviceMacs.forEach { arr.put(it) }
            obj.put("selectedDeviceMacs", arr)
        }
        obj.put("state", state.toJson())
        chainedSceneId?.let { obj.put("chainedSceneId", it) }
        chainedSceneReverseId?.let { obj.put("chainedSceneReverseId", it) }
        chainedSceneDelaySeconds?.let { obj.put("chainedSceneDelaySeconds", it) }
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): AppScene {
            val isPerDevice = obj.optBoolean("isPerDevice", false)
            val targetScope = obj.optString("targetScope", if (isPerDevice) "SELECT_DEVICES" else "ALL_DEVICES")
            
            val selectedDeviceMacs = if (obj.has("selectedDeviceMacs") && !obj.isNull("selectedDeviceMacs")) {
                val arr = obj.getJSONArray("selectedDeviceMacs")
                List(arr.length()) { arr.getString(it) }
            } else if (isPerDevice && obj.has("deviceStates") && !obj.isNull("deviceStates")) {
                val deviceObj = obj.getJSONObject("deviceStates")
                val keys = deviceObj.keys()
                val keysList = mutableListOf<String>()
                while (keys.hasNext()) {
                    keysList.add(keys.next())
                }
                keysList
            } else null

            val state = if (obj.has("state") && !obj.isNull("state")) {
                DeviceSceneState.fromJson(obj.getJSONObject("state"))
            } else if (!isPerDevice && obj.has("sharedState") && !obj.isNull("sharedState")) {
                DeviceSceneState.fromJson(obj.getJSONObject("sharedState"))
            } else if (isPerDevice && obj.has("deviceStates") && !obj.isNull("deviceStates")) {
                val deviceObj = obj.getJSONObject("deviceStates")
                val keys = deviceObj.keys()
                if (keys.hasNext()) {
                    DeviceSceneState.fromJson(deviceObj.getJSONObject(keys.next()))
                } else {
                    DeviceSceneState()
                }
            } else {
                DeviceSceneState()
            }

            val id = if (obj.has("id") && !obj.isNull("id")) obj.optString("id", "").trim() else ""
            if (id.isEmpty() || id == "null") {
                throw org.json.JSONException("AppScene is missing a valid id")
            }
            val name = if (obj.has("name") && !obj.isNull("name")) obj.optString("name", "Unnamed Scene") else "Unnamed Scene"

            val chainedSceneId = if (obj.has("chainedSceneId") && !obj.isNull("chainedSceneId")) obj.optString("chainedSceneId", "").takeIf { it.isNotEmpty() && it != "null" } else null
            val chainedSceneReverseId = if (obj.has("chainedSceneReverseId") && !obj.isNull("chainedSceneReverseId")) obj.optString("chainedSceneReverseId", "").takeIf { it.isNotEmpty() && it != "null" } else null
            val chainedSceneDelaySeconds = if (obj.has("chainedSceneDelaySeconds") && !obj.isNull("chainedSceneDelaySeconds")) obj.optInt("chainedSceneDelaySeconds") else null

            return AppScene(
                id = id,
                name = name,
                targetScope = targetScope,
                selectedDeviceMacs = selectedDeviceMacs,
                state = state,
                chainedSceneId = chainedSceneId,
                chainedSceneReverseId = chainedSceneReverseId,
                chainedSceneDelaySeconds = chainedSceneDelaySeconds
            )
        }
    }
}
