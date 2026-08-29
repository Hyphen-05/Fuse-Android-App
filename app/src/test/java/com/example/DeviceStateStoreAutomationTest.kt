package com.example

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the automation marker that decides whether a device is still owed a state restore.
 *
 * The colours were always persisted; the "is this device under automation" flag was not — it lived
 * only in a `ConcurrentHashMap` in the ViewModel. Ambiance runs in a foreground service while the
 * user is in another app, which is exactly when Android is most likely to destroy the ViewModel, so
 * the map came back empty and the strip silently kept its automation colour. These tests exist so
 * that the marker cannot quietly stop being persisted again.
 *
 * Robolectric because DataStore needs a real Context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceStateStoreAutomationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = DeviceStateStore(context)

    private val mac = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"

    @After
    fun tearDown() = runTest {
        store.clearState(mac)
        store.clearState(other)
    }

    @Test
    fun `automation marker round-trips`() = runTest {
        assertNull("no marker before anything is saved", store.getAutomation(mac))

        store.setAutomation(mac, "AMBIANCE")
        assertEquals("AMBIANCE", store.getAutomation(mac))

        store.clearAutomation(mac)
        assertNull("cleared once the restore has been paid", store.getAutomation(mac))
    }

    @Test
    fun `allAutomations lists every device still owed a restore`() = runTest {
        store.setAutomation(mac, "AUDIO")
        store.setAutomation(other, "AMBIANCE")

        val owed = store.allAutomations()

        assertEquals(mapOf(mac to "AUDIO", other to "AMBIANCE"), owed)
    }

    @Test
    fun `allAutomations ignores the colour keys stored beside it`() = runTest {
        // The colour keys share the same DataStore and the same MAC prefix, so a sloppy suffix match
        // here would return "AA:BB:CC:DD:EE:01_red" as if it were a device address.
        store.saveState(mac, power = true, red = 10, green = 20, blue = 30, warmth = 40, brightness = 50)

        assertTrue("colours alone owe no restore", store.allAutomations().isEmpty())

        store.setAutomation(mac, "AUDIO")
        assertEquals(setOf(mac), store.allAutomations().keys)
    }

    @Test
    fun `the marker is independent of the saved colours`() = runTest {
        store.saveState(mac, power = true, red = 10, green = 20, blue = 30, warmth = 40, brightness = 50)
        store.setAutomation(mac, "AMBIANCE")

        store.clearAutomation(mac)

        // Clearing the marker must not take the snapshot with it: the restore reads the colours
        // after deciding it is owed, and clearing happens once the write has gone out.
        val state = store.getState(mac)
        assertEquals(10, state?.red)
        assertEquals(50, state?.brightness)
    }

    @Test
    fun `forgetting a device clears its marker too`() = runTest {
        store.saveState(mac, power = true, red = 1, green = 2, blue = 3, warmth = 4, brightness = 5)
        store.setAutomation(mac, "AUDIO")

        store.clearState(mac)

        // Otherwise a forgotten device stays on the owed list forever and every launch tries to
        // restore a device the user has removed.
        assertNull(store.getAutomation(mac))
        assertNull(store.getState(mac))
        assertTrue(store.allAutomations().isEmpty())
    }
}
