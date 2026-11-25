package com.github.hyphen05.fuse.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarPromptTest {

    @Test
    fun hiddenBeforeEnoughConnectedLaunches() {
        for (n in 0 until StarPrompt.SHOW_AFTER_CONNECTS) {
            assertFalse(StarPrompt.shouldShow(connectedLaunches = n, done = false))
        }
    }

    @Test
    fun shownFromTheFifthConnectedLaunch() {
        assertTrue(StarPrompt.shouldShow(StarPrompt.SHOW_AFTER_CONNECTS, done = false))
        assertTrue(StarPrompt.shouldShow(StarPrompt.SHOW_AFTER_CONNECTS + 20, done = false))
    }

    @Test
    fun neverShownOnceAnswered() {
        assertFalse(StarPrompt.shouldShow(StarPrompt.SHOW_AFTER_CONNECTS, done = true))
        assertFalse(StarPrompt.shouldShow(100, done = true))
    }
}
