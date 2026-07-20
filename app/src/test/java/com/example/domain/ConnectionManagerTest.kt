package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {

    @Test
    fun `unknown address is not manually disconnected`() {
        val manager = ConnectionManager()

        assertFalse(manager.isManuallyDisconnected("AA:BB:CC:DD:EE:FF"))
        assertTrue(manager.connectionStates.value.isEmpty())
    }

    @Test
    fun `connect records Connecting state`() {
        val manager = ConnectionManager()
        val address = "AA:BB:CC:DD:EE:FF"

        manager.connect(address)

        assertEquals(ConnectionState.Connecting, manager.connectionStates.value[address])
        assertFalse(manager.isManuallyDisconnected(address))
    }

    @Test
    fun `setConnected records Connected state`() {
        val manager = ConnectionManager()
        val address = "AA:BB:CC:DD:EE:FF"

        manager.connect(address)
        manager.setConnected(address)

        assertEquals(ConnectionState.Connected, manager.connectionStates.value[address])
        assertFalse(manager.isManuallyDisconnected(address))
    }

    @Test
    fun `disconnect with manual true is reported as manually disconnected`() {
        val manager = ConnectionManager()
        val address = "AA:BB:CC:DD:EE:FF"

        manager.setConnected(address)
        manager.disconnect(address, manual = true)

        assertEquals(ConnectionState.Disconnected(true), manager.connectionStates.value[address])
        assertTrue(manager.isManuallyDisconnected(address))
    }

    @Test
    fun `disconnect with manual false is not reported as manually disconnected`() {
        val manager = ConnectionManager()
        val address = "AA:BB:CC:DD:EE:FF"

        manager.setConnected(address)
        manager.disconnect(address, manual = false)

        assertEquals(ConnectionState.Disconnected(false), manager.connectionStates.value[address])
        assertFalse(manager.isManuallyDisconnected(address))
    }

    @Test
    fun `reconnecting after a manual disconnect clears the manual flag`() {
        val manager = ConnectionManager()
        val address = "AA:BB:CC:DD:EE:FF"

        manager.disconnect(address, manual = true)
        assertTrue(manager.isManuallyDisconnected(address))

        manager.connect(address)

        assertFalse(manager.isManuallyDisconnected(address))
        assertEquals(ConnectionState.Connecting, manager.connectionStates.value[address])
    }

    @Test
    fun `states for different addresses are tracked independently`() {
        val manager = ConnectionManager()
        val addressA = "AA:AA:AA:AA:AA:AA"
        val addressB = "BB:BB:BB:BB:BB:BB"

        manager.setConnected(addressA)
        manager.disconnect(addressB, manual = true)

        assertEquals(ConnectionState.Connected, manager.connectionStates.value[addressA])
        assertFalse(manager.isManuallyDisconnected(addressA))
        assertTrue(manager.isManuallyDisconnected(addressB))
        assertEquals(2, manager.connectionStates.value.size)
    }
}
