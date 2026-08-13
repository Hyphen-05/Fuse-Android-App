package com.example.ui.components

import com.example.db.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The layout chips have no state of their own — which one reads as selected is derived from the
 * per-device roles in the DB, so this is where "the chips agree with what the strips are doing"
 * gets checked.
 */
class VisualizerRoleLayoutTest {

    private fun device(mac: String, role: String, hueOffset: Float = 180f) = SavedDevice(
        macAddress = mac,
        customName = mac,
        deviceRole = role,
        hueOffsetDegrees = hueOffset
    )

    @Test
    fun `no devices means no layout`() {
        assertNull(currentVisualizerLayout(emptyList()))
    }

    @Test
    fun `all mirror is the Mirror layout`() {
        val devices = listOf(device("A", "Mirror"), device("B", "Mirror"))
        assertEquals("Mirror", currentVisualizerLayout(devices))
    }

    @Test
    fun `first mirror plus hue offsets is the HueSplit layout`() {
        val devices = listOf(device("A", "Mirror"), device("B", "HueOffset"))
        assertEquals("HueSplit", currentVisualizerLayout(devices))
    }

    @Test
    fun `hue offset on the first device is not HueSplit`() {
        // applyVisualizerRoleLayout always leaves device 0 on Mirror, so this combination can only
        // come from somewhere else — report it as custom rather than lighting up the chip.
        val devices = listOf(device("A", "HueOffset"), device("B", "HueOffset"))
        assertNull(currentVisualizerLayout(devices))
    }

    @Test
    fun `all alternating and all band split are their own layouts`() {
        assertEquals(
            "AlternatingFlash",
            currentVisualizerLayout(listOf(device("A", "AlternatingFlash"), device("B", "AlternatingFlash")))
        )
        assertEquals(
            "BandSplit",
            currentVisualizerLayout(listOf(device("A", "BandSplit"), device("B", "BandSplit")))
        )
    }

    @Test
    fun `a half-applied set matches nothing`() {
        val devices = listOf(device("A", "BandSplit"), device("B", "AlternatingFlash"))
        assertNull(currentVisualizerLayout(devices))
    }

    @Test
    fun `a single mirrored device still reads as Mirror`() {
        assertEquals("Mirror", currentVisualizerLayout(listOf(device("A", "Mirror"))))
    }

    @Test
    fun `band split summary names the halves in visualiser order`() {
        val summaries = deviceRoleSummaries(listOf(device("A", "BandSplit"), device("B", "BandSplit")))
        assertEquals(listOf("Bass", "Mids & highs"), summaries.map { it.second })
    }

    @Test
    fun `hue offset summary carries the angle`() {
        val summaries = deviceRoleSummaries(listOf(device("A", "Mirror"), device("B", "HueOffset", 120f)))
        assertEquals(listOf("In sync", "Hue +120°"), summaries.map { it.second })
    }

    @Test
    fun `alternating summary counts only the alternating devices`() {
        // A Mirror device in the middle must not take a slot in the flash rotation, or the labels
        // stop matching what publishAudioDspResult actually does.
        val devices = listOf(
            device("A", "AlternatingFlash"),
            device("B", "Mirror"),
            device("C", "AlternatingFlash")
        )
        assertEquals(
            listOf("Flash 1 of 2", "In sync", "Flash 2 of 2"),
            deviceRoleSummaries(devices).map { it.second }
        )
    }

    @Test
    fun `swapping two band split devices swaps which one has the bass`() {
        // What Settings' Swap Roles has to produce: the labels move, the device order doesn't.
        val before = listOf(device("A", "BandSplit"), device("B", "Mirror"))
        assertEquals(listOf("Bass", "In sync"), deviceRoleSummaries(before).map { it.second })

        val after = listOf(device("A", "Mirror"), device("B", "BandSplit"))
        assertEquals(listOf("In sync", "Bass"), deviceRoleSummaries(after).map { it.second })
    }
}
