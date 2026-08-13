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
}
