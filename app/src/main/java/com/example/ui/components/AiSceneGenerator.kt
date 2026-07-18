package com.example.ui.components

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.min

data class SemanticSceneStep(
    val color: String,
    val rgbHex: String? = null,
    val durationMs: Int? = null,
    val smoothingMs: Int? = null,
    val flash: Boolean? = null,
    val flashPulseCount: Int? = null,
    val flashPulseIntervalMs: Int? = null
)

data class ProceduralSceneResult(
    val palette: List<SemanticSceneStep>,
    val sceneName: String,
    val explanation: String,
    val movementType: String,
    val energy: Float,
    val randomness: Float,
    val colorInterpMode: String,
    val brightnessBehaviour: String,
    val syncBrightnessToColor: Boolean,
    val harmonicLayering: String,
    val sequenceMode: String
)

sealed class SceneGenerationResult {
    data class Success(val result: ProceduralSceneResult) : SceneGenerationResult()
    object Unavailable : SceneGenerationResult()
    data class Error(val message: String) : SceneGenerationResult()
}

// Uses ML Kit GenAI Prompt API: com.google.mlkit:genai-prompt:1.0.0-beta2
class AiSceneGenerator {
    private val model: GenerativeModel by lazy {
        Generation.getClient()
    }

    suspend fun checkAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            val status = model.checkStatus()
            status != FeatureStatus.UNAVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    suspend fun generateScene(userPrompt: String): SceneGenerationResult = withContext(Dispatchers.IO) {
        try {
            val status = model.checkStatus()
            if (status == FeatureStatus.UNAVAILABLE) {
                return@withContext SceneGenerationResult.Unavailable
            }

            // Note: "generator" in the brightnessBehaviour enum list is undocumented and its intended meaning is unclear — do not invent behavior for it or remove it.
            val systemInstruction = """
You are a highly creative lighting scene logic generator optimized for global, non-addressable RGB lights (the entire strip changes color uniformly).
Convert the user's prompt into a procedural behavioral script. Return ONLY valid raw JSON. Do NOT include markdown blocks, backticks (```), or comments.

[JSON SCHEMA]
{
  "sceneName": "1-3 word title",
  "explanation": "Single descriptive sentence of artistic choices",
  "movementType": "Enum [drift, breathe, pulse, wander, flicker, spark, glitch, strobe, beacon, heartbeat]",
  "energy": "Float 0.0 (static/slow) to 1.0 (frantic)",
  "randomness": "Float 0.0 (rigid cycle) to 1.0 (pure organic drift)",
  "colorInterpMode": "Enum [linear, cosine, stepped] -> Controls the mathematical fade curve between colors",
  "brightnessBehaviour": "Enum [stable, breathe, pulse, flicker, drift, storm, ember, clouds, generator]",
  "syncBrightnessToColor": "Boolean -> True if brightness intensity should mimic color warmth/energy",
  "harmonicLayering": "Enum [none, subtle-noise, syncopated] -> Adds a micro-rhythm on top of the main animation",
  "sequenceMode": "Enum [cycle, ping-pong] -> cycle repeats the palette in fixed order (use for rigid/named patterns like sirens, alarms, retro clocks); ping-pong reverses direction at each end of the palette (use for ambient/organic moods)",
  "colors": "Array of 2 to 5 color objects. Dominant colors first."
}

[BEHAVIOR RULES]
1. ABSTRACT / MOOD (e.g., sunset, cozy cabin, deep ocean):
- randomness: >= 0.4
- colorInterpMode: "cosine" (for smooth, high-end organic blending)
- harmonicLayering: "none" or "subtle-noise"
- sequenceMode: "ping-pong" (reverses direction at palette ends for organic/ambient flow)

2. RIGID / NAMED PATTERN (e.g., siren, strobe, alarm, retro clock):
- randomness: 0.0 to 0.15 (forces strict palette rotation order)
- colorInterpMode: "stepped" (locks colors until the exact transition frame)
- movementType: "flicker", "strobe", "pulse", or "heartbeat"
- syncBrightnessToColor: false
- sequenceMode: "cycle" (repeats colors in fixed order for alarms/strobes)

3. BRIGHTNESS BEHAVIOR GUIDANCE:
- breathe: Choose when you want a smooth, rhythmic expanding and contracting wave of light.
- flicker: Choose when mimicking candlelight, crackling fire, or sudden irregular micro-bursts of light.
- drift: Choose when light levels should slowly wander up and down with very slow organic shifts.
- storm: Choose when simulating dramatic lightning flashes and chaotic sudden spikes in brightness.
- ember: Choose when creating the look of hot charcoal or dying embers with slow, irregular deep dips.
- clouds: Choose when simulating light filtering through passing clouds with soft, sluggish variations.

[EXAMPLES]

User: "cozy campfire"
{
  "sceneName": "Campfire Glow",
  "explanation": "Simulates shifting wood embers using natural cosine color blending linked directly to brightness dips.",
  "movementType": "wander",
  "energy": 0.3,
  "randomness": 0.6,
  "colorInterpMode": "cosine",
  "brightnessBehaviour": "ember",
  "syncBrightnessToColor": true,
  "harmonicLayering": "subtle-noise",
  "sequenceMode": "ping-pong",
  "colors": [
    { "color": "deep ember red", "rgbHex": "#991B1B" },
    { "color": "blaze orange", "rgbHex": "#EA580C" },
    { "color": "faint ash gold", "rgbHex": "#D97706" }
  ]
}

User: "dinner party"
{
  "sceneName": "Warm Banquet",
  "explanation": "A balanced, warm setting with gentle color pulses designed for moderate energy and socialization.",
  "movementType": "pulse",
  "energy": 0.5,
  "randomness": 0.35,
  "colorInterpMode": "linear",
  "brightnessBehaviour": "breathe",
  "syncBrightnessToColor": true,
  "harmonicLayering": "none",
  "sequenceMode": "ping-pong",
  "colors": [
    { "color": "warm amber", "rgbHex": "#F59E0B" },
    { "color": "soft rose", "rgbHex": "#FDA4AF" },
    { "color": "muted gold", "rgbHex": "#EAB308" }
  ]
}

User: "sci-fi alarm"
{
  "sceneName": "Sector Alarm",
  "explanation": "A strict, double-pulsing red warning sequence with instant stepped color cuts.",
  "movementType": "heartbeat",
  "energy": 0.8,
  "randomness": 0.0,
  "colorInterpMode": "stepped",
  "brightnessBehaviour": "pulse",
  "syncBrightnessToColor": false,
  "harmonicLayering": "none",
  "sequenceMode": "cycle",
  "colors": [
    { "color": "alert red", "rgbHex": "#FF0000" },
    { "color": "blackout off", "rgbHex": "#000000" }
  ]
}

Output only the raw JSON.
            """.trimIndent()
            
            val fullPrompt = "${systemInstruction}\n\nUser prompt: ${userPrompt}"
            
            val request = GenerateContentRequest.Builder(TextPart(fullPrompt)).apply {
                maxOutputTokens = 256
            }.build()
            val response = model.generateContent(request)
            
            val responseText = response.candidates.firstOrNull()?.text
                ?: return@withContext SceneGenerationResult.Error("Empty response from model")
                
            try {
                val jsonString = extractJson(responseText) ?: throw JSONException("JSON boundaries not found")
                val result = parseAndValidateJson(jsonString)
                SceneGenerationResult.Success(result)
            } catch (e: JSONException) {
                attemptRecovery(responseText) ?: SceneGenerationResult.Error("Failed to parse JSON: ${e.message}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SceneGenerationResult.Error(e.message ?: "Unknown error")
        }
    }

    // Best-effort recovery for truncated on-device model output
    private fun attemptRecovery(text: String): SceneGenerationResult.Success? {
        val sceneNameRegex = """"sceneName"\s*:\s*"([^"]*)"""".toRegex()
        val sceneName = sceneNameRegex.find(text)?.groupValues?.get(1) ?: "AI Scene"

        val explanationRegex = """"explanation"\s*:\s*"([^"]*)"""".toRegex()
        val explanation = explanationRegex.find(text)?.groupValues?.get(1) ?: "Custom scene"

        val movementType = """"movementType"\s*:\s*"([^"]*)"""".toRegex().find(text)?.groupValues?.get(1) ?: "drift"
        val energy = """"energy"\s*:\s*([0-9.]+)""".toRegex().find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
        val randomness = """"randomness"\s*:\s*([0-9.]+)""".toRegex().find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
        val colorInterpMode = """"colorInterpMode"\s*:\s*"([^"]*)"""".toRegex().find(text)?.groupValues?.get(1) ?: "cosine"
        val brightnessBehaviour = """"brightnessBehaviour"\s*:\s*"([^"]*)"""".toRegex().find(text)?.groupValues?.get(1) ?: "stable"
        val syncBrightnessToColor = """"syncBrightnessToColor"\s*:\s*(true|false)""".toRegex().find(text)?.groupValues?.get(1)?.toBoolean() ?: false
        val harmonicLayering = """"harmonicLayering"\s*:\s*"([^"]*)"""".toRegex().find(text)?.groupValues?.get(1) ?: "none"
        val sequenceMode = """"sequenceMode"\s*:\s*"([^"]*)"""".toRegex().find(text)?.groupValues?.get(1)
            ?: (if (randomness < 0.3f) "cycle" else "ping-pong")

        val colorsMatch = """"colors"\s*:""".toRegex().find(text)
        val colorsStartIndex = colorsMatch?.range?.first ?: return null

        val colorsText = text.substring(colorsStartIndex)
        val colors = mutableListOf<SemanticSceneStep>()
        
        var depth = 0
        var currentObjStart = -1
        for (i in colorsText.indices) {
            val c = colorsText[i]
            if (c == '{') {
                if (depth == 0) currentObjStart = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && currentObjStart != -1) {
                    val objStr = colorsText.substring(currentObjStart, i + 1)
                    try {
                        val colObj = JSONObject(objStr)
                        val color = colObj.optString("color", "white")
                        val rgbHexRaw = colObj.optString("rgbHex", null)
                        val rgbHex = normalizeAndValidateHex(rgbHexRaw)
                        if (rgbHex != null) {
                            colors.add(SemanticSceneStep(color = color, rgbHex = rgbHex))
                            if (colors.size >= 6) break
                        }
                    } catch (e: Exception) {
                        // Ignore malformed object
                    }
                    currentObjStart = -1
                }
            }
        }

        if (colors.isEmpty()) return null
        return SceneGenerationResult.Success(ProceduralSceneResult(
            palette = colors,
            sceneName = sceneName,
            explanation = explanation,
            movementType = movementType,
            energy = energy,
            randomness = randomness,
            colorInterpMode = colorInterpMode,
            brightnessBehaviour = brightnessBehaviour,
            syncBrightnessToColor = syncBrightnessToColor,
            harmonicLayering = harmonicLayering,
            sequenceMode = sequenceMode
        ))
    }

    private fun normalizeAndValidateHex(hex: String?): String? {
        if (hex == null) return null
        var trimmed = hex.trim()
        if (!trimmed.startsWith("#") && trimmed.length == 6 && trimmed.matches(Regex("^[0-9a-fA-F]{6}$"))) {
            trimmed = "#$trimmed"
        }
        if (trimmed.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
            return trimmed
        }
        return null
    }

    private fun extractJson(text: String): String? {
        val trimmed = text.trim()
        val startIndex = trimmed.indexOf('{')
        val endIndex = trimmed.lastIndexOf('}')
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return trimmed.substring(startIndex, endIndex + 1)
        }
        return null
    }

    private fun parseAndValidateJson(jsonStr: String): ProceduralSceneResult {
        val json = JSONObject(jsonStr)
        val sceneName = json.optString("sceneName", "AI Scene")
        val explanation = json.optString("explanation", "Custom scene")
        
        val movementType = json.optString("movementType", "drift")
        val energy = json.optDouble("energy", 0.5).toFloat()
        val randomness = json.optDouble("randomness", 0.5).toFloat()
        val colorInterpMode = json.optString("colorInterpMode", "cosine")
        val brightnessBehaviour = json.optString("brightnessBehaviour", "stable")
        val syncBrightnessToColor = json.optBoolean("syncBrightnessToColor", false)
        val harmonicLayering = json.optString("harmonicLayering", "none")
        var sequenceMode = json.optString("sequenceMode", "")
        if (sequenceMode.isEmpty()) {
            sequenceMode = if (randomness < 0.3f) "cycle" else "ping-pong"
        }
        
        val colorsArray = json.getJSONArray("colors")
        
        val steps = mutableListOf<SemanticSceneStep>()
        val maxSteps = min(colorsArray.length(), 6)
        
        for (i in 0 until maxSteps) {
            val colObj = colorsArray.getJSONObject(i)
            val color = colObj.optString("color", "white")
            val rgbHexRaw = colObj.optString("rgbHex", null)
            val rgbHex = normalizeAndValidateHex(rgbHexRaw)
            if (rgbHex != null) {
                steps.add(SemanticSceneStep(color = color, rgbHex = rgbHex))
            }
        }
        
        if (steps.isEmpty()) {
            throw JSONException("No valid colors parsed")
        }
        
        return ProceduralSceneResult(
            palette = steps,
            sceneName = sceneName,
            explanation = explanation,
            movementType = movementType,
            energy = energy,
            randomness = randomness,
            colorInterpMode = colorInterpMode,
            brightnessBehaviour = brightnessBehaviour,
            syncBrightnessToColor = syncBrightnessToColor,
            harmonicLayering = harmonicLayering,
            sequenceMode = sequenceMode
        )
    }

    fun close() {
        try {
            model.close()
        } catch (e: Exception) {
            // Ignore any cleanup errors
        }
    }
}
