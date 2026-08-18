package com.example.hardware.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ordering that cost an evening on 2026-08-18: service discovery issued while an MTU
 * exchange was still in flight, silently dropped by the stack, connection stuck at CONNECTING.
 *
 * Every test here is a sequence of callbacks in an order the stack really produces. The point is
 * that nothing about the broken code looked wrong — it read as "ask for a big MTU, then discover
 * services" — so the rule needs pinning rather than describing.
 */
class GattDiscoveryGateTest {

    private val address = "BE:16:0A:00:3B:DA"

    @Test
    fun `discovery waits while the mtu exchange is in flight`() {
        val gate = GattDiscoveryGate()
        gate.onMtuRequested(address)
        gate.onDiscoveryWanted(address)
        assertFalse("this is the bug: discovery must not go out mid-MTU",
            gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `discovery goes out once the mtu exchange settles`() {
        val gate = GattDiscoveryGate()
        gate.onMtuRequested(address)
        gate.onDiscoveryWanted(address)
        gate.onMtuSettled(address)
        assertTrue(gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `only one caller wins the race`() {
        // The connect path, the MTU callback and the fallback timer all trigger this.
        val gate = GattDiscoveryGate()
        gate.onDiscoveryWanted(address)
        assertTrue(gate.shouldIssueDiscovery(address))
        assertFalse(gate.shouldIssueDiscovery(address))
        assertFalse(gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `discovery is not issued before anyone asked for it`() {
        val gate = GattDiscoveryGate()
        gate.onMtuSettled(address)
        assertFalse(gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `a refused mtu request does not strand the connection`() {
        // requestMtu returning false means no callback is ever coming.
        val gate = GattDiscoveryGate()
        gate.onMtuRequested(address)
        gate.onDiscoveryWanted(address)
        gate.onMtuSettled(address)
        assertTrue(gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `the fallback timer can tell whether the real callback beat it`() {
        val gate = GattDiscoveryGate()
        gate.onMtuRequested(address)
        assertTrue("first settle clears a real outstanding exchange", gate.onMtuSettled(address))
        assertFalse("the timer must find nothing left to do", gate.onMtuSettled(address))
    }

    @Test
    fun `a failed issue can be retried`() {
        val gate = GattDiscoveryGate()
        gate.onDiscoveryWanted(address)
        assertTrue(gate.shouldIssueDiscovery(address))
        gate.onDiscoveryFailed(address)
        assertTrue("otherwise the connection sits at CONNECTING forever",
            gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `a reconnect starts clean`() {
        val gate = GattDiscoveryGate()
        gate.onDiscoveryWanted(address)
        assertTrue(gate.shouldIssueDiscovery(address))
        gate.forget(address)

        gate.onDiscoveryWanted(address)
        assertTrue("a second connection must be able to discover again",
            gate.shouldIssueDiscovery(address))
    }

    @Test
    fun `connections are tracked independently`() {
        val gate = GattDiscoveryGate()
        val other = "11:66:F0:00:01:3D"
        gate.onMtuRequested(address)
        gate.onDiscoveryWanted(address)
        gate.onDiscoveryWanted(other)

        assertTrue("one strip's MTU exchange must not block the other's discovery",
            gate.shouldIssueDiscovery(other))
        assertFalse(gate.shouldIssueDiscovery(address))
    }
}
