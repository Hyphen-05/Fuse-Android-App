package com.github.hyphen05.fuse.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureDisplayNameTest {

    @Test
    fun cctIsShownAsWarmth() {
        assertEquals("Warmth", featureDisplayName("CCT"))
    }

    @Test
    fun otherFeaturesAreShownAsTheyAre() {
        for (name in listOf("Colour", "Ambiance", "LED Visualiser (Steady)", "Rainbow Jump")) {
            assertEquals(name, featureDisplayName(name))
        }
    }
}
