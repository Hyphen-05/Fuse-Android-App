package com.example.core.modecapture

import com.example.db.CustomMode
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric is required here (not plain JUnit) because ModeCaptureExporter uses org.json, an
// Android SDK stub class that throws at runtime under plain unit tests — same reasoning as the
// reducer tests in app/src/test/java/com/example/presentation/.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModeCaptureModelsTest {

    private fun customMode(byteValue: Int, name: String, category: String = "Classic Effects") =
        CustomMode(byteValue = byteValue, name = name, category = category, direction = "none", colors = "Red")

    // === modesToCapture ===

    @Test
    fun `modesToCapture excludes Auto Play byteValue 0`() {
        val modes = listOf(
            customMode(0, "Auto Play"),
            customMode(1, "Solid Red"),
            customMode(57, "Magic Forward")
        )

        val result = modesToCapture(modes)

        assertEquals(listOf(1, 57), result.map { it.byteValue })
    }

    @Test
    fun `modesToCapture on empty list returns empty list`() {
        assertTrue(modesToCapture(emptyList()).isEmpty())
    }

    // === ModeCaptureExporter.toJson ===

    @Test
    fun `toJson round-trips top-level fields`() {
        val export = ModeCaptureExport(
            exportedAtEpochMs = 12345L,
            samplePositionCount = 24,
            reference = ReferenceCapture(
                knownR = 255, knownG = 255, knownB = 255,
                measuredSamples = listOf(SpatialSample(0, 250, 248, 252))
            ),
            modes = listOf(
                ModeCaptureRecord(
                    byteValue = 57,
                    name = "Magic Forward",
                    category = "Classic Effects",
                    frames = listOf(
                        CaptureFrame(
                            elapsedMs = 100L,
                            samples = listOf(SpatialSample(0, 10, 20, 30), SpatialSample(1, 40, 50, 60))
                        )
                    ),
                    complete = true
                )
            )
        )

        val json = JSONObject(ModeCaptureExporter.toJson(export))

        assertEquals(12345L, json.getLong("exportedAtEpochMs"))
        assertEquals(24, json.getInt("samplePositionCount"))
        assertTrue(json.getString("notes").isNotBlank())

        val referenceJson = json.getJSONObject("reference")
        assertEquals(255, referenceJson.getInt("knownR"))
        val referenceSamples = referenceJson.getJSONArray("measuredSamples")
        assertEquals(1, referenceSamples.length())
        assertEquals(252, referenceSamples.getJSONObject(0).getInt("b"))

        val modesJson = json.getJSONArray("modes")
        assertEquals(1, modesJson.length())
        val modeJson = modesJson.getJSONObject(0)
        assertEquals(57, modeJson.getInt("byteValue"))
        assertEquals("Magic Forward", modeJson.getString("name"))
        assertTrue(modeJson.getBoolean("complete"))

        val framesJson = modeJson.getJSONArray("frames")
        assertEquals(1, framesJson.length())
        assertEquals(100L, framesJson.getJSONObject(0).getLong("elapsedMs"))
        val samplesJson = framesJson.getJSONObject(0).getJSONArray("samples")
        assertEquals(2, samplesJson.length())
        assertEquals(1, samplesJson.getJSONObject(1).getInt("positionIndex"))
        assertEquals(60, samplesJson.getJSONObject(1).getInt("b"))
    }

    @Test
    fun `toJson handles null reference and incomplete mode`() {
        val export = ModeCaptureExport(
            exportedAtEpochMs = 1L,
            samplePositionCount = 8,
            reference = null,
            modes = listOf(
                ModeCaptureRecord(1, "Solid Red", "Classic Effects", emptyList(), complete = false)
            )
        )

        val json = JSONObject(ModeCaptureExporter.toJson(export))

        assertNull(json.opt("reference").takeUnless { it == JSONObject.NULL })
        val modeJson = json.getJSONArray("modes").getJSONObject(0)
        assertFalse(modeJson.getBoolean("complete"))
        assertEquals(0, modeJson.getJSONArray("frames").length())
    }

    @Test
    fun `toJson escapes special characters in mode name`() {
        val export = ModeCaptureExport(
            exportedAtEpochMs = 1L,
            samplePositionCount = 1,
            reference = null,
            modes = listOf(
                ModeCaptureRecord(2, "Quote \" and \\ backslash", "Category", emptyList(), complete = true)
            )
        )

        val json = JSONObject(ModeCaptureExporter.toJson(export))
        val modeJson = json.getJSONArray("modes").getJSONObject(0)

        assertEquals("Quote \" and \\ backslash", modeJson.getString("name"))
    }

    // === writeJson streaming path ===

    // Regression test for a real on-device crash: exporting a full capture run (~214 modes, ~100
    // frames/mode at the 10Hz/10s-hold defaults, 24 samples/frame) via the old JSONObject-tree +
    // single `.toString()` implementation threw java.lang.OutOfMemoryError (~75MB single allocation)
    // before any file was ever written. This drives writeJson at comparable scale against a real
    // file Writer — the actual code path ModeCaptureViewModel.export() uses — to confirm it
    // completes and produces valid, structurally-correct JSON instead of buffering the whole
    // document in memory at once.
    @Test
    fun `writeJson streams a full-scale capture run to a real file without buffering it whole`() {
        val modeCount = 214
        val framesPerMode = 100
        val samplesPerFrame = 24

        val modes = (1..modeCount).map { modeIndex ->
            val frames = (0 until framesPerMode).map { frameIndex ->
                val samples = (0 until samplesPerFrame).map { positionIndex ->
                    SpatialSample(positionIndex, positionIndex % 256, frameIndex % 256, modeIndex % 256)
                }
                CaptureFrame(elapsedMs = frameIndex * 100L, samples = samples)
            }
            ModeCaptureRecord(
                byteValue = modeIndex,
                name = "Mode $modeIndex",
                category = "Classic Effects",
                frames = frames,
                complete = true
            )
        }
        val export = ModeCaptureExport(
            exportedAtEpochMs = 1_700_000_000_000L,
            samplePositionCount = samplesPerFrame,
            reference = ReferenceCapture(255, 255, 255, listOf(SpatialSample(0, 250, 248, 252))),
            modes = modes
        )

        val file = File.createTempFile("mode_capture_stress", ".json")
        try {
            file.bufferedWriter().use { writer -> ModeCaptureExporter.writeJson(export, writer) }

            val json = JSONObject(file.readText())
            val modesJson = json.getJSONArray("modes")
            assertEquals(modeCount, modesJson.length())
            assertEquals(framesPerMode, modesJson.getJSONObject(0).getJSONArray("frames").length())
            assertEquals(
                samplesPerFrame,
                modesJson.getJSONObject(modeCount - 1).getJSONArray("frames")
                    .getJSONObject(framesPerMode - 1).getJSONArray("samples").length()
            )
        } finally {
            file.delete()
        }
    }
}
