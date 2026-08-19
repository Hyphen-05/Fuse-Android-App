package com.example.ambiance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmbiancePresetStoreTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun preset(name: String, id: String = "custom_$name") = AmbiancePreset(
        id = id,
        name = name,
        description = "Custom sliders config preset",
        isCustom = true,
        responseSpeed = 0.5f,
        smoothnessMs = 150,
        saturationBoost = 1.4f,
        brightnessCompensation = 1.0f,
        sceneCutSensitivity = 110f,
        noiseDeadband = 0.10f
    )

    @Test
    fun `encode then decode round-trips every field`() {
        val original = preset("Study").copy(
            responseSpeed = 0.33f,
            smoothnessMs = 275,
            saturationBoost = 1.75f,
            brightnessCompensation = 0.8f,
            sceneCutSensitivity = 140f,
            noiseDeadband = 0.22f
        )

        val decoded = AmbiancePresetStore.decode(AmbiancePresetStore.encode(listOf(original)))

        assertEquals(1, decoded.size)
        assertEquals(original, decoded[0])
    }

    @Test
    fun `fields added after the format shipped fall back to their defaults`() {
        // A blob written by a build that predates sceneCutSensitivity and noiseDeadband.
        val legacy = """[{"id":"custom_1","name":"Old","description":"d","responseSpeed":0.5,
            |"smoothnessMs":150,"saturationBoost":1.4,"brightnessCompensation":1.0}]""".trimMargin()

        val decoded = AmbiancePresetStore.decode(legacy)

        assertEquals(1, decoded.size)
        assertEquals(110f, decoded[0].sceneCutSensitivity, 0.001f)
        assertEquals(0.10f, decoded[0].noiseDeadband, 0.001f)
    }

    @Test
    fun `a malformed blob yields no presets rather than throwing`() {
        assertTrue(AmbiancePresetStore.decode("not json at all").isEmpty())
    }

    @Test
    fun `saving publishes to the flow immediately`() {
        // The bug this store exists to fix: the fine-tune panel saved a preset and the Ambiance
        // tab's list did not know about it. Anything observing the flow sees it at once.
        val context = context()
        AmbiancePresetStore.load(context)
        val before = AmbiancePresetStore.presets.value.size

        AmbiancePresetStore.save(context, preset("Reading"))

        assertEquals(before + 1, AmbiancePresetStore.presets.value.size)
        assertTrue(AmbiancePresetStore.presets.value.any { it.name == "Reading" })
    }

    @Test
    fun `saving the same name replaces rather than duplicating`() {
        val context = context()
        AmbiancePresetStore.save(context, preset("Evening", id = "first"))
        AmbiancePresetStore.save(context, preset("evening", id = "second"))

        val matches = AmbiancePresetStore.presets.value.filter { it.name.equals("evening", true) }
        assertEquals(1, matches.size)
        assertEquals("second", matches[0].id)
    }

    @Test
    fun `rename and delete both survive a reload from prefs`() {
        val context = context()
        AmbiancePresetStore.save(context, preset("Keep", id = "keep"))
        AmbiancePresetStore.save(context, preset("Drop", id = "drop"))

        AmbiancePresetStore.rename(context, "keep", "Kept")
        AmbiancePresetStore.delete(context, "drop")

        // What a cold launch would read back.
        val persisted = AmbiancePresetStore.decode(
            context.getSharedPreferences("ambiance_presets_prefs", Context.MODE_PRIVATE)
                .getString("custom_presets_json", "")!!
        )
        assertTrue(persisted.any { it.name == "Kept" })
        assertTrue(persisted.none { it.id == "drop" })
    }
}
