package com.example.hardware.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * Orders the two GATT operations that a fresh connection issues, because Android's GATT client runs
 * **one at a time** and will silently drop the second.
 *
 * ## The bug this encodes
 *
 * The connect path asked for a bigger MTU and then called `discoverServices()` about two
 * milliseconds later, while the MTU exchange was still in flight. The stack dropped the discovery
 * request, `onServicesDiscovered` never arrived, and the connection sat at CONNECTING forever —
 * having looked perfectly healthy up to that point: `status=0`, MTU negotiated to 220, connection
 * parameters updated. From the UI it read as "finds the devices but won't connect".
 *
 * Observed on the moto 2026-08-18, on both strips, every attempt. It is a race, which is why the
 * same code connected fine on the Pixel for months: the slower phone loses it reliably where the
 * faster one wins it. That is also why this is a class with tests rather than a comment — nothing
 * about the old code *looked* wrong, and a future edit reordering these two calls would reintroduce
 * it invisibly on one device and not the other.
 *
 * ## The rule
 *
 * Discovery is issued once per connection, and only when no MTU exchange is outstanding. Three
 * callers race to trigger it — the connect path, the MTU callback, and a fallback timer for
 * peripherals that never answer an MTU request — and exactly one must win.
 */
class GattDiscoveryGate {

    private val mtuInFlight = ConcurrentHashMap.newKeySet<String>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val issued = ConcurrentHashMap.newKeySet<String>()

    /** An MTU exchange has been started: discovery must wait for it. */
    fun onMtuRequested(address: String) {
        mtuInFlight.add(address)
    }

    /**
     * The MTU exchange finished, was refused outright, or timed out.
     *
     * Returns true if it cleared an exchange that was actually outstanding — the caller uses that
     * to make the fallback timer a no-op once the real callback has arrived.
     */
    fun onMtuSettled(address: String): Boolean = mtuInFlight.remove(address)

    /** Discovery is wanted for this connection; it may or may not be issuable yet. */
    fun onDiscoveryWanted(address: String) {
        wanted.add(address)
    }

    /**
     * Whether the caller should issue `discoverServices()` right now.
     *
     * Claims the right to issue, so only the first caller to ask under the right conditions gets
     * true. Every other caller — including the ones racing on other threads — gets false.
     */
    @Synchronized
    fun shouldIssueDiscovery(address: String): Boolean {
        if (mtuInFlight.contains(address)) return false
        if (!wanted.contains(address)) return false
        return issued.add(address)
    }

    /**
     * The issue attempt failed, so release the claim and let a later trigger try again rather than
     * stranding the connection at CONNECTING.
     */
    fun onDiscoveryFailed(address: String) {
        issued.remove(address)
    }

    /** Connection gone: forget everything about it, so a reconnect starts clean. */
    fun forget(address: String) {
        mtuInFlight.remove(address)
        wanted.remove(address)
        issued.remove(address)
    }
}
