package com.hermes.node.engine

import android.app.ActivityManager
import android.content.Intent
import android.os.BatteryManager
import com.hermes.node.ui.components.formatBattery
import com.hermes.node.ui.components.formatCpuUsage
import com.hermes.node.ui.components.formatRamUsage
import com.hermes.node.ui.components.formatTemperature
import com.hermes.node.ui.components.getCpuColor
import com.hermes.node.ui.components.getThermalColor
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.StatusError
import com.hermes.node.ui.theme.StatusStarting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryMonitorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun deviceTelemetry_defaultValues() {
        val telemetry = DeviceTelemetry()
        assertEquals(0f, telemetry.cpuPercent, 0.001f)
        assertEquals(0L, telemetry.usedMemoryMb)
        assertEquals(0L, telemetry.totalMemoryMb)
        assertEquals(100, telemetry.batteryPercent)
        assertFalse(telemetry.isCharging)
        assertEquals(0f, telemetry.batteryTemperatureCelsius, 0.001f)
    }

    @Test
    fun deviceTelemetry_customValues() {
        val telemetry = DeviceTelemetry(
            cpuPercent = 14.5f,
            usedMemoryMb = 1850L,
            totalMemoryMb = 4096L,
            batteryPercent = 82,
            isCharging = true,
            batteryTemperatureCelsius = 33.5f
        )
        assertEquals(14.5f, telemetry.cpuPercent, 0.001f)
        assertEquals(1850L, telemetry.usedMemoryMb)
        assertEquals(4096L, telemetry.totalMemoryMb)
        assertEquals(82, telemetry.batteryPercent)
        assertTrue(telemetry.isCharging)
        assertEquals(33.5f, telemetry.batteryTemperatureCelsius, 0.001f)
    }

    @Test
    fun parseBatteryValues_withValidValues_extractsAllMetricsCorrectly() {
        val (percent, isCharging, temp) = SystemTelemetryCollector.parseBatteryValues(
            level = 85,
            scale = 100,
            rawTemperature = 345, // 34.5°C
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        assertEquals(85, percent)
        assertTrue(isCharging)
        assertEquals(34.5f, temp, 0.001f)
    }

    @Test
    fun parseBatteryValues_whenFull_isChargingIsTrue() {
        val (percent, isCharging, temp) = SystemTelemetryCollector.parseBatteryValues(
            level = 100,
            scale = 100,
            rawTemperature = 290,
            status = BatteryManager.BATTERY_STATUS_FULL
        )
        assertEquals(100, percent)
        assertTrue(isCharging)
        assertEquals(29.0f, temp, 0.001f)
    }

    @Test
    fun parseBatteryValues_whenDischarging_isChargingIsFalse() {
        val (percent, isCharging, temp) = SystemTelemetryCollector.parseBatteryValues(
            level = 54,
            scale = 100,
            rawTemperature = 310,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING
        )
        assertEquals(54, percent)
        assertFalse(isCharging)
        assertEquals(31.0f, temp, 0.001f)
    }

    @Test
    fun parseBatteryIntent_whenNull_returnsSafeDefaults() {
        val (percent, isCharging, temp) = SystemTelemetryCollector.parseBatteryIntent(null)
        assertEquals(100, percent)
        assertFalse(isCharging)
        assertEquals(0.0f, temp, 0.001f)
    }

    @Test
    fun parseBatteryValues_withInvalidLevelOrScale_fallsBackSafely() {
        val (percent, isCharging, temp) = SystemTelemetryCollector.parseBatteryValues(
            level = -1,
            scale = -1,
            rawTemperature = 0,
            status = -1
        )
        assertEquals(100, percent)
        assertFalse(isCharging)
        assertEquals(0.0f, temp, 0.001f)
    }

    @Test
    fun parseMemoryInfo_calculatesUsedAndTotalMemoryMb() {
        val memInfo = ActivityManager.MemoryInfo().apply {
            totalMem = 4L * 1024 * 1024 * 1024 // 4096 MB
            availMem = 2560L * 1024 * 1024      // 2560 MB
        }

        val (usedMb, totalMb) = SystemTelemetryCollector.parseMemoryInfo(memInfo)
        assertEquals(4096L, totalMb)
        assertEquals(1536L, usedMb)
    }

    @Test
    fun parseProcStatLine_extractsWorkAndTotalTicks() {
        val line = "cpu  4705 356 584 3699 23 12 21 0 0 0"
        val snapshot = SystemTelemetryCollector.parseProcStatLine(line)

        assertNotNull(snapshot)
        // total: 4705 + 356 + 584 + 3699 + 23 + 12 + 21 + 0 = 9400
        assertEquals(9400L, snapshot!!.totalTime)
        // work: 4705 + 356 + 584 + 12 + 21 + 0 = 5678
        assertEquals(5678L, snapshot.workTime)
    }

    @Test
    fun parseProcStatLine_withInvalidOrNonCpuLine_returnsNull() {
        assertNull(SystemTelemetryCollector.parseProcStatLine("intr 2145321 0 0"))
        assertNull(SystemTelemetryCollector.parseProcStatLine("cpu0 100 200 300 400 500 600"))
        assertNull(SystemTelemetryCollector.parseProcStatLine("cpu1 100 200 300 400 500 600"))
        assertNull(SystemTelemetryCollector.parseProcStatLine(""))
        assertNull(SystemTelemetryCollector.parseProcStatLine("cpu abc def ghi"))
    }

    @Test
    fun calculateCpuPercent_computesAccuratePercentageBetweenSnapshots() {
        val snap1 = CpuStatSnapshot(workTime = 5678L, totalTime = 9400L)
        val snap2 = CpuStatSnapshot(workTime = 6090L, totalTime = 10120L)

        // deltaWork = 6090 - 5678 = 412
        // deltaTotal = 10120 - 9400 = 720
        // cpu = (412 / 720) * 100 = 57.222%
        val cpuPercent = SystemTelemetryCollector.calculateCpuPercent(snap1, snap2)
        assertEquals(57.222f, cpuPercent, 0.01f)
    }

    @Test
    fun calculateCpuPercent_withZeroOrNegativeDelta_returnsZero() {
        val snap1 = CpuStatSnapshot(workTime = 5000L, totalTime = 10000L)
        val snap2 = CpuStatSnapshot(workTime = 5000L, totalTime = 10000L)

        assertEquals(0f, SystemTelemetryCollector.calculateCpuPercent(snap1, snap2), 0.001f)

        val snap3 = CpuStatSnapshot(workTime = 4900L, totalTime = 9900L)
        assertEquals(0f, SystemTelemetryCollector.calculateCpuPercent(snap1, snap3), 0.001f)
    }

    @Test
    fun systemTelemetryCollector_withCustomStatReader_calculatesCpuDelta() = runTest(testDispatcher) {
        var statLine = "cpu  1000 200 300 5000 50 10 20 0 0 0"
        val collector = SystemTelemetryCollector(
            ioDispatcher = testDispatcher,
            customStatReader = { statLine }
        )

        // First sample initializes baseline
        val reading1 = collector.collect()
        assertEquals(0f, reading1.cpuPercent, 0.001f)

        // Advance stat line: work +100, idle +100 (total +200) -> 50% CPU
        statLine = "cpu  1100 200 300 5100 50 10 20 0 0 0"
        val reading2 = collector.collect()
        assertEquals(50.0f, reading2.cpuPercent, 0.01f)
    }

    @Test
    fun systemTelemetryCollector_whenProcFails_handlesGracefullyWithoutThrowing() = runTest(testDispatcher) {
        val collector = SystemTelemetryCollector(
            ioDispatcher = testDispatcher,
            customStatReader = { throw IOException("SELinux Permission Denied on /proc/stat") }
        )

        val reading = collector.collect()
        assertNotNull(reading)
        assertTrue(reading.cpuPercent >= 0f)
    }

    @Test
    fun telemetryMonitor_pollingLoop_emitsUpdatesAtInterval() = runTest(testDispatcher) {
        var counter = 0
        val fakeCollector = object : TelemetryCollector {
            override suspend fun collect(): DeviceTelemetry {
                counter++
                return DeviceTelemetry(
                    cpuPercent = counter * 10f,
                    usedMemoryMb = counter * 100L,
                    totalMemoryMb = 4096L,
                    batteryPercent = 90 - counter,
                    isCharging = true,
                    batteryTemperatureCelsius = 30f + counter
                )
            }
        }

        val monitor = TelemetryMonitor(
            collector = fakeCollector,
            pollingIntervalMs = 2000L,
            dispatcher = testDispatcher
        )

        assertFalse(monitor.isPolling)
        monitor.start(this)
        assertTrue(monitor.isPolling)

        advanceTimeBy(10)
        assertEquals(10f, monitor.telemetry.value.cpuPercent, 0.001f)
        assertEquals(100L, monitor.telemetry.value.usedMemoryMb)

        // Advance 2 seconds
        advanceTimeBy(2000)
        assertEquals(20f, monitor.telemetry.value.cpuPercent, 0.001f)
        assertEquals(200L, monitor.telemetry.value.usedMemoryMb)

        // Advance another 2 seconds
        advanceTimeBy(2000)
        assertEquals(30f, monitor.telemetry.value.cpuPercent, 0.001f)
        assertEquals(300L, monitor.telemetry.value.usedMemoryMb)

        monitor.stop()
        assertFalse(monitor.isPolling)
    }

    @Test
    fun telemetryMonitor_pauseAndResume_controlsCollection() = runTest(testDispatcher) {
        var counter = 0
        val fakeCollector = object : TelemetryCollector {
            override suspend fun collect(): DeviceTelemetry {
                counter++
                return DeviceTelemetry(cpuPercent = counter.toFloat())
            }
        }

        val monitor = TelemetryMonitor(
            collector = fakeCollector,
            pollingIntervalMs = 2000L,
            dispatcher = testDispatcher
        )

        monitor.start(this)
        advanceTimeBy(10)
        assertEquals(1f, monitor.telemetry.value.cpuPercent, 0.001f)

        // Pause monitor
        monitor.pause()
        assertTrue(monitor.isPaused)

        // Advance time while paused: counter does not increment
        advanceTimeBy(4000)
        assertEquals(1f, monitor.telemetry.value.cpuPercent, 0.001f)

        // Resume monitor
        monitor.resume()
        assertFalse(monitor.isPaused)

        advanceTimeBy(2000)
        assertEquals(2f, monitor.telemetry.value.cpuPercent, 0.001f)

        monitor.stop()
    }

    @Test
    fun telemetryMonitor_sampleOnce_triggersDirectUpdate() = runTest(testDispatcher) {
        val fakeCollector = object : TelemetryCollector {
            override suspend fun collect(): DeviceTelemetry {
                return DeviceTelemetry(cpuPercent = 42.0f, usedMemoryMb = 512L)
            }
        }

        val monitor = TelemetryMonitor(
            collector = fakeCollector,
            pollingIntervalMs = 2000L,
            dispatcher = testDispatcher
        )

        val result = monitor.sampleOnce()
        assertEquals(42.0f, result.cpuPercent, 0.001f)
        assertEquals(512L, result.usedMemoryMb)
        assertEquals(42.0f, monitor.telemetry.value.cpuPercent, 0.001f)
    }

    @Test
    fun telemetryMonitor_whenCollectorThrows_retainsPreviousReading() = runTest(testDispatcher) {
        var shouldFail = false
        val fakeCollector = object : TelemetryCollector {
            override suspend fun collect(): DeviceTelemetry {
                if (shouldFail) throw IllegalStateException("Hardware sensor unavailable")
                return DeviceTelemetry(cpuPercent = 15f, batteryTemperatureCelsius = 32f)
            }
        }

        val monitor = TelemetryMonitor(
            collector = fakeCollector,
            pollingIntervalMs = 2000L,
            dispatcher = testDispatcher
        )

        monitor.start(this)
        advanceTimeBy(10)
        assertEquals(15f, monitor.telemetry.value.cpuPercent, 0.001f)

        // Now simulate failure
        shouldFail = true
        advanceTimeBy(2000)

        // Retains previous valid values
        assertEquals(15f, monitor.telemetry.value.cpuPercent, 0.001f)
        assertEquals(32f, monitor.telemetry.value.batteryTemperatureCelsius, 0.001f)

        monitor.stop()
    }

    @Test
    fun getCpuColor_thresholdMapping() {
        assertEquals(HermesCyan, getCpuColor(0f))
        assertEquals(HermesCyan, getCpuColor(50f))
        assertEquals(HermesCyan, getCpuColor(74.9f))

        assertEquals(StatusStarting, getCpuColor(75.0f))
        assertEquals(StatusStarting, getCpuColor(85.0f))
        assertEquals(StatusStarting, getCpuColor(89.9f))

        assertEquals(StatusError, getCpuColor(90.0f))
        assertEquals(StatusError, getCpuColor(99.9f))
        assertEquals(StatusError, getCpuColor(100.0f))
    }

    @Test
    fun getThermalColor_thresholdMapping() {
        assertEquals(HermesCyan, getThermalColor(25.0f))
        assertEquals(HermesCyan, getThermalColor(37.9f))

        assertEquals(StatusStarting, getThermalColor(38.0f))
        assertEquals(StatusStarting, getThermalColor(42.5f))
        assertEquals(StatusStarting, getThermalColor(44.9f))

        assertEquals(StatusError, getThermalColor(45.0f))
        assertEquals(StatusError, getThermalColor(50.0f))
    }

    @Test
    fun formattingHelpers_produceAccurateOutputs() {
        assertEquals("85%", formatBattery(85, isCharging = false))
        assertEquals("85% ⚡", formatBattery(85, isCharging = true))

        assertEquals("34.5 °C", formatTemperature(34.5f))
        assertEquals("-- °C", formatTemperature(0.0f))
        assertEquals("-- °C", formatTemperature(-5.0f))

        assertEquals("12.4%", formatCpuUsage(12.36f))
        assertEquals("0.0%", formatCpuUsage(0.0f))

        assertEquals("1536 / 4096 MB", formatRamUsage(1536L, 4096L))
        assertEquals("1536 MB", formatRamUsage(1536L, 0L))
    }
}
