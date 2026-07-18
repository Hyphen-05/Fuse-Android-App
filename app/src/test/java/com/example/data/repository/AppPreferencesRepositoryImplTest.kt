package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Reason for Robolectric: AppPreferencesRepositoryImpl uses Android's Context directly 
// to obtain SharedPreferences. SharedPreferences is an Android framework component, 
// so Robolectric is required to provide a realistic testing environment without a physical device.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppPreferencesRepositoryImplTest {

    private lateinit var classUnderTest: AppPreferencesRepositoryImpl
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        classUnderTest = AppPreferencesRepositoryImpl(context)
    }

    @Test
    fun testAppStatePrefs() {
        classUnderTest.putAppStatePrefBoolean("test_bool", true)
        assertTrue(classUnderTest.getAppStatePrefBoolean("test_bool", false))

        classUnderTest.putAppStatePrefInt("test_int", 42)
        assertEquals(42, classUnderTest.getAppStatePrefInt("test_int", 0))
    }

    @Test
    fun testAmbiancePrefs() {
        classUnderTest.putAmbiancePrefInt("test_amb_int", 100)
        assertEquals(100, classUnderTest.getAmbiancePrefInt("test_amb_int", 0))
    }

    @Test
    fun testProtocolOverrides() {
        classUnderTest.putProtocolOverrideString("test_key", "7E 00")
        val overrides = classUnderTest.getProtocolOverrideAll()
        assertEquals("7E 00", overrides["test_key"])
        
        classUnderTest.removeProtocolOverride("test_key")
        val after = classUnderTest.getProtocolOverrideAll()
        assertEquals(null, after["test_key"])
    }
}
