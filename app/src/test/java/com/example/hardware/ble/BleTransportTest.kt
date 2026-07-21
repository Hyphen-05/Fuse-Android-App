package com.example.hardware.ble

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Construction/contract-level coverage for the BLE transports (Phase 6 hardware isolation), plus
 * wiring tests using [FakeBleScanTransport]/[FakeBleGattTransport].
 *
 * Deliberately NOT covered here (same rationale as `AudioCaptureSourceTest` for the audio layer):
 * actually connecting to real BLE hardware and receiving real GATT callbacks. Robolectric's
 * Bluetooth shadows don't model a real peripheral, so a "does it really connect" test would be
 * exercising the shadow, not the hardware contract — that's a manual on-device smoke test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BleTransportTest {

    @Test
    fun `AndroidBleScanTransport constructs and stops cleanly before any scan`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = AndroidBleScanTransport(context)
        // stop before start must be a no-op and not throw
        transport.stopScan()
    }

    @Test
    fun `AndroidBleGattTransport pure state ops behave without a real gatt`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = AndroidBleGattTransport(context)

        assertFalse(transport.isConnected("AA:BB:CC:DD:EE:FF"))
        assertTrue(transport.activeConnectionAddresses().isEmpty())
        assertTrue(transport.deviceWriteManagerAddresses().isEmpty())

        assertEquals(0, transport.getRetryAttempt("AA:BB:CC:DD:EE:FF"))
        transport.setRetryAttempt("AA:BB:CC:DD:EE:FF", 3)
        assertEquals(3, transport.getRetryAttempt("AA:BB:CC:DD:EE:FF"))

        // No managers registered yet — these must be safe no-ops.
        transport.setPacing("AA:BB:CC:DD:EE:FF", 100)
        transport.resetAllPacing(100)
        transport.writeCommand("AA:BB:CC:DD:EE:FF", byteArrayOf(1, 2, 3))
        transport.notifyWriteCompleted("AA:BB:CC:DD:EE:FF")

        // No active connections/characteristics — restore must invoke onRestored zero times.
        var restored = 0
        transport.restoreWriteManagers { restored++ }
        assertEquals(0, restored)

        // No connection -> nothing to remove.
        assertNull(transport.removeConnection("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `fake scan transport records start, forwards results, and tracks stop`() {
        val fake = FakeBleScanTransport()
        val failures = mutableListOf<Int>()
        var resultCount = 0

        val result = fake.startScan(
            onResult = { resultCount++ },
            onFailed = { failures.add(it) }
        )

        assertEquals(ScanStartResult.STARTED, result)
        assertTrue(fake.isScanning)
        assertEquals(1, fake.startCallCount)

        fake.emitScanFailed(2)
        assertEquals(listOf(2), failures)

        fake.stopScan()
        assertEquals(1, fake.stopCallCount)
        assertFalse(fake.isScanning)
    }

    @Test
    fun `fake scan transport can report a start failure without scanning`() {
        val fake = FakeBleScanTransport(startResult = ScanStartResult.ADAPTER_UNAVAILABLE)
        val result = fake.startScan(onResult = {}, onFailed = {})
        assertEquals(ScanStartResult.ADAPTER_UNAVAILABLE, result)
        assertFalse(fake.isScanning)
    }

    @Test
    fun `fake gatt transport forwards registered connection and write callbacks`() {
        val fake = FakeBleGattTransport()
        val events = mutableListOf<String>()

        fake.registerCallbacks(
            onConnectionStateChange = { addr, status, newState -> events.add("conn:$addr:$status:$newState") },
            onServicesDiscovered = { addr, status -> events.add("svc:$addr:$status") },
            onCharacteristicWrite = { addr, status -> events.add("write:$addr:$status") },
            onLog = { events.add("log:$it") }
        )

        fake.emitConnectionStateChange("AA:BB", 0, 2)
        fake.emitServicesDiscovered("AA:BB", 0)
        fake.emitCharacteristicWrite("AA:BB", 0)

        assertEquals(listOf("conn:AA:BB:0:2", "svc:AA:BB:0", "write:AA:BB:0"), events)
    }

    @Test
    fun `fake gatt transport tracks connect, write, and disconnect lifecycle`() {
        val fake = FakeBleGattTransport()
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertFalse(fake.isConnected("AA:BB"))
        fake.connect(context, "AA:BB")
        assertTrue(fake.isConnected("AA:BB"))
        assertEquals(listOf("AA:BB"), fake.connectCalls)

        fake.writeCommand("AA:BB", byteArrayOf(9, 9))
        assertEquals(1, fake.writtenCommands.size)
        assertEquals("AA:BB", fake.writtenCommands[0].first)

        assertNull(fake.removeConnection("AA:BB"))
        assertFalse(fake.isConnected("AA:BB"))
        assertEquals(listOf("AA:BB"), fake.disconnectedAddresses)
    }

    @Test
    fun `fake gatt transport registerDuoCoCharacteristic returns configured result`() {
        val registered = CharacteristicRegistration.Registered(
            address = "AA:BB",
            charUuid = UUID.fromString("0000ffd9-0000-1000-8000-00805f9b34fb"),
            pacingMs = 50,
            ackSupported = false
        )
        val fake = FakeBleGattTransport(registrationResult = registered)
        assertEquals(registered, fake.registerDuoCoCharacteristic("AA:BB"))
        assertTrue(fake.deviceWriteManagerAddresses().contains("AA:BB"))

        val notFound = FakeBleGattTransport(registrationResult = CharacteristicRegistration.NotFound("CC:DD"))
        assertEquals(
            CharacteristicRegistration.NotFound("CC:DD"),
            notFound.registerDuoCoCharacteristic("CC:DD")
        )
        assertFalse(notFound.deviceWriteManagerAddresses().contains("CC:DD"))
    }
}
