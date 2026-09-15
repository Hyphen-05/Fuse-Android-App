package com.example.hardware.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionReconcilerTest {

    private val connected = ConnectionReconciler.STATE_CONNECTED
    private val disconnected = 0

    @Test
    fun `a connection the stack no longer has is stale`() {
        // Joe's 2026-09-14 report: hours in the background, reopen, tiles for strips that are gone.
        val stale = ConnectionReconciler.stale(setOf("A", "B")) { if (it == "A") disconnected else connected }
        assertEquals(setOf("A"), stale)
    }

    @Test
    fun `connections the stack agrees with are left alone`() {
        assertEquals(emptySet<String>(), ConnectionReconciler.stale(setOf("A", "B")) { connected })
    }

    @Test
    fun `a check that could not be made does not drop anything`() {
        // Bluetooth off or a permission hiccup on resume must not tear every strip down.
        assertEquals(emptySet<String>(), ConnectionReconciler.stale(setOf("A", "B")) { null })
    }

    @Test
    fun `only what the app believes is connected is checked`() {
        // A device mid-connect is not linked yet as far as the stack is concerned either; the
        // caller keeps it out of the set, and nothing outside the set is ever reported.
        val asked = mutableListOf<String>()
        ConnectionReconciler.stale(setOf("A")) { asked += it; disconnected }
        assertEquals(listOf("A"), asked)
    }
}
