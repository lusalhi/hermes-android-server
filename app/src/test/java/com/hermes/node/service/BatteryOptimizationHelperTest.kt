package com.hermes.node.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BatteryOptimizationHelperTest {

    private lateinit var helper: BatteryOptimizationHelper
    private lateinit var fakeContext: FakeBatteryTestContext

    @Before
    fun setUp() {
        helper = BatteryOptimizationHelper()
        fakeContext = FakeBatteryTestContext()
    }

    @Test
    fun constants_haveExpectedValues() {
        assertEquals("https://dontkillmyapp.com", BatteryOptimizationHelper.DONT_KILL_MY_APP_BASE)
        assertEquals("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", BatteryOptimizationHelper.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", BatteryOptimizationHelper.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    @Test
    fun createRequestExemptionIntent_returnsIntent() {
        val intent = helper.createRequestExemptionIntent(fakeContext)
        assertNotNull(intent)
    }

    @Test
    fun createBatteryOptimizationSettingsIntent_returnsIntent() {
        val intent = helper.createBatteryOptimizationSettingsIntent()
        assertNotNull(intent)
    }

    @Test
    fun isIgnoringBatteryOptimizations_whenPowerManagerNull_returnsTrueGracefully() {
        fakeContext.powerManager = null

        val result = helper.isIgnoringBatteryOptimizations(fakeContext)
        assertTrue(result)
    }

    @Test
    fun isIgnoringBatteryOptimizations_whenExceptionThrown_returnsTrueGracefully() {
        val throwingContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.hermes.node"
            override fun getSystemService(name: String): Any {
                throw RuntimeException("IPC error")
            }
        }

        val result = helper.isIgnoringBatteryOptimizations(throwingContext)
        assertTrue(result)
    }

    @Test
    fun requestExemption_whenDirectIntentSucceeds_returnsTrue() {
        val result = helper.requestExemption(fakeContext)

        assertTrue(result)
        assertEquals(1, fakeContext.startActivityCallCount)
    }

    @Test
    fun requestExemption_whenDirectIntentThrowsActivityNotFound_fallsBackToSettingsIntent() {
        fakeContext.throwOnFirstCall = ActivityNotFoundException("No activity found for direct exemption")

        val result = helper.requestExemption(fakeContext)

        assertTrue(result)
        assertEquals(2, fakeContext.startActivityCallCount)
    }

    @Test
    fun requestExemption_whenDirectIntentThrowsSecurityException_fallsBackToSettingsIntent() {
        fakeContext.throwOnFirstCall = SecurityException("Direct exemption permission denied")

        val result = helper.requestExemption(fakeContext)

        assertTrue(result)
        assertEquals(2, fakeContext.startActivityCallCount)
    }

    @Test
    fun requestExemption_whenBothDirectAndFallbackThrow_returnsFalseWithoutCrashing() {
        val failingContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.hermes.node"
            override fun startActivity(intent: Intent?) {
                throw RuntimeException("Cannot start any activity")
            }
        }

        val result = helper.requestExemption(failingContext)
        assertFalse(result)
    }

    @Test
    fun getDontKillMyAppUrl_resolvesKnownManufacturers() {
        assertEquals("https://dontkillmyapp.com/samsung", helper.getDontKillMyAppUrl("Samsung"))
        assertEquals("https://dontkillmyapp.com/samsung", helper.getDontKillMyAppUrl("  samsung  "))
        assertEquals("https://dontkillmyapp.com/samsung", helper.getDontKillMyAppUrl("Samsung Electronics"))
        assertEquals("https://dontkillmyapp.com/xiaomi", helper.getDontKillMyAppUrl("Xiaomi"))
        assertEquals("https://dontkillmyapp.com/xiaomi", helper.getDontKillMyAppUrl("Redmi"))
        assertEquals("https://dontkillmyapp.com/xiaomi", helper.getDontKillMyAppUrl("POCO"))
        assertEquals("https://dontkillmyapp.com/huawei", helper.getDontKillMyAppUrl("Huawei"))
        assertEquals("https://dontkillmyapp.com/huawei", helper.getDontKillMyAppUrl("Huawei Technologies"))
        assertEquals("https://dontkillmyapp.com/huawei", helper.getDontKillMyAppUrl("Honor"))
        assertEquals("https://dontkillmyapp.com/oneplus", helper.getDontKillMyAppUrl("OnePlus"))
        assertEquals("https://dontkillmyapp.com/oppo", helper.getDontKillMyAppUrl("OPPO"))
        assertEquals("https://dontkillmyapp.com/realme", helper.getDontKillMyAppUrl("Realme"))
        assertEquals("https://dontkillmyapp.com/vivo", helper.getDontKillMyAppUrl("Vivo"))
        assertEquals("https://dontkillmyapp.com/google", helper.getDontKillMyAppUrl("Google"))
        assertEquals("https://dontkillmyapp.com/google", helper.getDontKillMyAppUrl("Google Inc"))
        assertEquals("https://dontkillmyapp.com/google", helper.getDontKillMyAppUrl("Pixel"))
        assertEquals("https://dontkillmyapp.com/asus", helper.getDontKillMyAppUrl("ASUS"))
        assertEquals("https://dontkillmyapp.com/sony", helper.getDontKillMyAppUrl("Sony"))
        assertEquals("https://dontkillmyapp.com/motorola", helper.getDontKillMyAppUrl("Motorola"))
        assertEquals("https://dontkillmyapp.com/motorola", helper.getDontKillMyAppUrl("Motorola Mobility LLC"))
        assertEquals("https://dontkillmyapp.com/nothing", helper.getDontKillMyAppUrl("Nothing"))
        assertEquals("https://dontkillmyapp.com/nothing", helper.getDontKillMyAppUrl("Nothing Phone 2"))
        assertEquals("https://dontkillmyapp.com/lg", helper.getDontKillMyAppUrl("LGE"))
        assertEquals("https://dontkillmyapp.com/lg", helper.getDontKillMyAppUrl("LG Electronics"))
        assertEquals("https://dontkillmyapp.com/unihertz", helper.getDontKillMyAppUrl("Unihertz"))
        assertEquals("https://dontkillmyapp.com/blackview", helper.getDontKillMyAppUrl("Blackview"))
        assertEquals("https://dontkillmyapp.com/ulefone", helper.getDontKillMyAppUrl("Ulefone"))
        assertEquals("https://dontkillmyapp.com/transsion", helper.getDontKillMyAppUrl("Tecno Mobile"))
        assertEquals("https://dontkillmyapp.com/transsion", helper.getDontKillMyAppUrl("Infinix"))
    }

    @Test
    fun getDontKillMyAppUrl_unknownManufacturer_returnsDefaultBaseUrl() {
        assertEquals("https://dontkillmyapp.com", helper.getDontKillMyAppUrl("UnknownBrand"))
        assertEquals("https://dontkillmyapp.com", helper.getDontKillMyAppUrl(""))
        assertEquals("https://dontkillmyapp.com", helper.getDontKillMyAppUrl(null))
    }

    private class FakeBatteryTestContext : ContextWrapper(null) {
        var powerManager: PowerManager? = null
        var throwOnFirstCall: Throwable? = null
        var startActivityCallCount = 0

        override fun getPackageName(): String = "com.hermes.node"

        override fun getSystemService(name: String): Any? {
            return if (name == Context.POWER_SERVICE) powerManager else null
        }

        override fun startActivity(intent: Intent?) {
            startActivityCallCount++
            if (startActivityCallCount == 1 && throwOnFirstCall != null) {
                throw throwOnFirstCall!!
            }
        }
    }
}
