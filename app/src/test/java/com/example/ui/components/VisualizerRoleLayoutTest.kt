package com.example.ui.components

import com.example.db.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            currentVisualizerLayout(listOf(device("A", "BandSplitLow"), device("B", "BandSplitHigh")))
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
    fun `band split summary names the halves from the roles themselves`() {
        val summaries = deviceRoleSummaries(listOf(device("A", "BandSplitLow"), device("B", "BandSplitHigh")))
        assertEquals(listOf("Bass", "Mids & highs"), summaries.map { it.second })
    }

    @Test
    fun `legacy band split rows still get labelled by position`() {
        // Rows written before the halves moved into the role string. They still render by list
        // position in publishAudioDspResult, so the labels have to match that, not the role.
        val summaries = deviceRoleSummaries(listOf(device("A", "BandSplit"), device("B", "BandSplit")))
        assertEquals(listOf("Bass", "Mids & highs"), summaries.map { it.second })
        assertEquals("BandSplit", currentVisualizerLayout(listOf(device("A", "BandSplit"), device("B", "BandSplit"))))
    }

    @Test
    fun `split halves are swappable but identical roles are not`() {
        // The bug this fixes: Band Split used to give both strips the same role string, so the
        // swap wrote back the values it had just read and nothing moved.
        assertTrue(rolesAreSwappable(listOf(device("A", "BandSplitLow"), device("B", "BandSplitHigh"))))
        assertTrue(rolesAreSwappable(listOf(device("A", "Mirror"), device("B", "HueOffset"))))
        assertFalse(rolesAreSwappable(listOf(device("A", "Mirror"), device("B", "Mirror"))))
        assertFalse(rolesAreSwappable(listOf(device("A", "AlternatingFlash"), device("B", "AlternatingFlash"))))
        assertFalse(rolesAreSwappable(listOf(device("A", "BandSplitLow"))))
    }

    @Test
    fun `hue offset summary carries the angle`() {
        val summaries = deviceRoleSummaries(listOf(device("A", "Mirror"), device("B", "HueOffset", 120f)))
        assertEquals(listOf("In sync", "Hue +120°"), summaries.map { it.second })
    }

    @Test
    fun `every alternating device reads the same`() {
        // The flash rotates through all of them, so no strip is "the first" for longer than a beat
        // — numbering them implied an order that swapping could change, and it can't.
        val devices = listOf(
            device("A", "AlternatingFlash"),
            device("B", "Mirror"),
            device("C", "AlternatingFlash")
        )
        assertEquals(
            listOf("Alternating", "In sync", "Alternating"),
            deviceRoleSummaries(devices).map { it.second }
        )
    }

    @Test
    fun `swapping two band split devices swaps which one has the bass`() {
        // What Settings' Swap Roles has to produce: the labels move, the device order doesn't.
        val before = listOf(device("A", "BandSplitLow"), device("B", "BandSplitHigh"))
        assertEquals(listOf("Bass", "Mids & highs"), deviceRoleSummaries(before).map { it.second })

        val after = listOf(device("A", "BandSplitHigh"), device("B", "BandSplitLow"))
        assertEquals(listOf("Mids & highs", "Bass"), deviceRoleSummaries(after).map { it.second })
    }
}
