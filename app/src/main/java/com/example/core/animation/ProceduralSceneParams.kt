package com.example.core.animation

import org.json.JSONArray
import org.json.JSONObject

data class ProceduralSceneParams(
    val palette: List<Triple<Int, Int, Int>>,
    val movementType: String,
    val energy: Float,
    val randomness: Float,
    val colorInterpMode: String,
    val brightnessBehaviour: String,
    val syncBrightnessToColor: Boolean,
    val harmonicLayering: String,
    val sequenceMode: String
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        val paletteArr = JSONArray()
        for (color in palette) {
            val colorObj = JSONObject()
            colorObj.put("r", color.first)
            colorObj.put("g", color.second)
            colorObj.put("b", color.third)
            paletteArr.put(colorObj)
        }
        obj.put("palette", paletteArr)
        obj.put("movementType", movementType)
        obj.put("energy", energy.toDouble())
        obj.put("randomness", randomness.toDouble())
        obj.put("colorInterpMode", colorInterpMode)
        obj.put("brightnessBehaviour", brightnessBehaviour)
        obj.put("syncBrightnessToColor", syncBrightnessToColor)
        obj.put("harmonicLayering", harmonicLayering)
        obj.put("sequenceMode", sequenceMode)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): ProceduralSceneParams {
            val paletteArr = obj.optJSONArray("palette")
            val palette = mutableListOf<Triple<Int, Int, Int>>()
            if (paletteArr != null) {
                for (i in 0 until paletteArr.length()) {
                    val colorObj = paletteArr.getJSONObject(i)
                    palette.add(Triple(colorObj.getInt("r"), colorObj.getInt("g"), colorObj.getInt("b")))
                }
            }
            return ProceduralSceneParams(
                palette = palette,
                movementType = obj.optString("movementType", "drift"),
                energy = obj.optDouble("energy", 0.5).toFloat(),
                randomness = obj.optDouble("randomness", 0.5).toFloat(),
                colorInterpMode = obj.optString("colorInterpMode", "cosine"),
                brightnessBehaviour = obj.optString("brightnessBehaviour", "stable"),
                syncBrightnessToColor = obj.optBoolean("syncBrightnessToColor", false),
                harmonicLayering = obj.optString("harmonicLayering", "none"),
                sequenceMode = obj.optString("sequenceMode", "cycle")
            )
        }
    }
}
