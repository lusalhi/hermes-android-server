package com.hermes.node.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Snapshot of physical device hardware vitals.
 */
data class DeviceTelemetry(
    val cpuPercent: Float = 0f,
    val usedMemoryMb: Long = 0L,
    val totalMemoryMb: Long = 0L,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val batteryTemperatureCelsius: Float = 0f
)

/**
 * Interface for hardware telemetry collection.
 */
interface TelemetryCollector {
    suspend fun collect(): DeviceTelemetry
}

/**
 * Raw snapshot of CPU total and work ticks.
 */
data class CpuStatSnapshot(
    val workTime: Long,
    val totalTime: Long
)

/**
 * Android system hardware telemetry collector.
 *
 * Samples RAM consumption via [ActivityManager.MemoryInfo],
 * battery level, charging state, and temperature via sticky [Intent.ACTION_BATTERY_CHANGED] broadcasts,
 * and CPU % via `/proc/stat` delta or process CPU fallback.
 */
open class SystemTelemetryCollector(
    private val context: Context? = null,
    private val activityManager: ActivityManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val statFile: File? = File("/proc/stat"),
    private val customStatReader: (() -> String?)? = null,
    private val customBatteryReader: (() -> Triple<Int, Boolean, Float>)? = null
) : TelemetryCollector {

    private val cpuLock = Any()

    @Volatile
    private var lastCpuSnapshot: CpuStatSnapshot? = null

    @Volatile
    private var lastProcessCpuTime: Long = 0L

    @Volatile
    private var lastRealtime: Long = 0L

    override suspend fun collect(): DeviceTelemetry = withContext(ioDispatcher) {
        val memory = collectMemory()
        val battery = collectBattery()
        val cpu = collectCpu()

        DeviceTelemetry(
            cpuPercent = cpu,
            usedMemoryMb = memory.first,
            totalMemoryMb = memory.second,
            batteryPercent = battery.first,
            isCharging = battery.second,
            batteryTemperatureCelsius = battery.third
        )
    }

    /**
     * Reads RAM usage: Pair(usedMb, totalMb).
     */
    fun collectMemory(): Pair<Long, Long> {
        return try {
            val actManager = activityManager ?: (context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            if (actManager != null) {
                val memInfo = ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                parseMemoryInfo(memInfo)
            } else {
                val runtime = Runtime.getRuntime()
                val totalMb = runtime.totalMemory() / (1024 * 1024)
                val freeMb = runtime.freeMemory() / (1024 * 1024)
                val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
                Pair(usedMb, totalMb)
            }
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    /**
     * Reads battery stats: Triple(percent, isCharging, temperatureCelsius).
     */
    fun collectBattery(): Triple<Int, Boolean, Float> {
        return try {
            if (customBatteryReader != null) {
                customBatteryReader.invoke()
            } else {
                val intent = context?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                parseBatteryIntent(intent)
            }
        } catch (e: Exception) {
            Triple(100, false, 0f)
        }
    }

    /**
     * Calculates CPU % via /proc/stat delta or process CPU fallback.
     */
    fun collectCpu(): Float = synchronized(cpuLock) {
        return try {
            val procStatContent = readProcStat()
            if (procStatContent != null) {
                val currentSnapshot = parseProcStatLine(procStatContent)
                if (currentSnapshot != null) {
                    val prev = lastCpuSnapshot
                    lastCpuSnapshot = currentSnapshot
                    if (prev != null) {
                        return calculateCpuPercent(prev, currentSnapshot)
                    }
                }
            }

            // Fallback to process CPU delta
            collectProcessCpuFallback()
        } catch (e: Exception) {
            0f
        }
    }

    private fun readProcStat(): String? {
        return try {
            if (customStatReader != null) {
                customStatReader.invoke()
            } else if (statFile != null && statFile.exists() && statFile.canRead()) {
                FileInputStream(statFile).use { fis ->
                    InputStreamReader(fis, Charsets.UTF_8).use { isr ->
                        isr.buffered().readLine()
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun collectProcessCpuFallback(): Float {
        return try {
            val currentCpuTime = Process.getElapsedCpuTime()
            val currentRealtime = SystemClock.elapsedRealtime()

            val prevCpu = lastProcessCpuTime
            val prevRealtime = lastRealtime

            lastProcessCpuTime = currentCpuTime
            lastRealtime = currentRealtime

            if (prevRealtime > 0L && currentRealtime > prevRealtime) {
                val deltaRealtime = currentRealtime - prevRealtime
                // If delta is excessively large (e.g. initial transition from /proc/stat), treat as baseline
                if (deltaRealtime > 10000L) {
                    return 0f
                }
                val deltaCpu = (currentCpuTime - prevCpu).coerceAtLeast(0L)
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val usage = (deltaCpu.toFloat() / (deltaRealtime.toFloat() * cores)) * 100f
                usage.coerceIn(0f, 100f)
            } else {
                0f
            }
        } catch (e: Throwable) {
            0f
        }
    }

    companion object {
        /**
         * Calculates used and total RAM from [ActivityManager.MemoryInfo].
         */
        fun parseMemoryInfo(memInfo: ActivityManager.MemoryInfo): Pair<Long, Long> {
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val availMb = memInfo.availMem / (1024 * 1024)
            val usedMb = (totalMb - availMb).coerceAtLeast(0L)
            return Pair(usedMb, totalMb)
        }

        /**
         * Calculates battery percent, charging state, and temperature from raw intent values.
         */
        fun parseBatteryValues(
            level: Int,
            scale: Int,
            rawTemperature: Int,
            status: Int
        ): Triple<Int, Boolean, Float> {
            val tempCelsius = rawTemperature / 10.0f
            val batteryPercent = if (level >= 0 && scale > 0) {
                ((level.toLong() * 100L) / scale.toLong()).toInt().coerceIn(0, 100)
            } else {
                100
            }
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return Triple(batteryPercent, isCharging, tempCelsius)
        }

        /**
         * Parses battery intent data into Triple(percent, isCharging, temperatureCelsius).
         */
        fun parseBatteryIntent(batteryIntent: Intent?): Triple<Int, Boolean, Float> {
            if (batteryIntent == null) {
                return Triple(100, false, 0.0f)
            }

            val rawTemp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            return parseBatteryValues(level, scale, rawTemp, status)
        }

        /**
         * Parses the first line of /proc/stat into [CpuStatSnapshot].
         * Line format: `cpu  user nice system idle iowait irq softirq steal guest guest_nice`
         * Rejects non-aggregate lines such as individual cores (e.g. `cpu0`).
         */
        fun parseProcStatLine(line: String): CpuStatSnapshot? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("cpu")) return null

            val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size < 5 || tokens[0] != "cpu") return null

            return try {
                // tokens[0] is strictly "cpu"
                val user = tokens[1].toLong()
                val nice = tokens[2].toLong()
                val system = tokens[3].toLong()
                val idle = tokens[4].toLong()
                val iowait = if (tokens.size > 5) tokens[5].toLong() else 0L
                val irq = if (tokens.size > 6) tokens[6].toLong() else 0L
                val softirq = if (tokens.size > 7) tokens[7].toLong() else 0L
                val steal = if (tokens.size > 8) tokens[8].toLong() else 0L

                val total = user + nice + system + idle + iowait + irq + softirq + steal
                val work = user + nice + system + irq + softirq + steal

                CpuStatSnapshot(workTime = work, totalTime = total)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Computes CPU % load between two snapshots.
         */
        fun calculateCpuPercent(prev: CpuStatSnapshot, curr: CpuStatSnapshot): Float {
            val deltaTotal = curr.totalTime - prev.totalTime
            val deltaWork = curr.workTime - prev.workTime

            if (deltaTotal <= 0L || deltaWork < 0L) return 0f

            val percent = (deltaWork.toFloat() / deltaTotal.toFloat()) * 100f
            return percent.coerceIn(0f, 100f)
        }
    }
}

/**
 * Engine managing asynchronous coroutine hardware telemetry polling loop.
 */
class TelemetryMonitor(
    private val collector: TelemetryCollector,
    pollingIntervalMs: Long = 2000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    val pollingIntervalMs: Long = pollingIntervalMs.coerceAtLeast(1000L)

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private var pollingJob: Job? = null

    @Volatile
    var isPolling: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    /**
     * Starts the polling loop within the provided [scope].
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (pollingJob != null && pollingJob?.isActive == true) return
        isPolling = true
        isPaused = false

        pollingJob = scope.launch(dispatcher) {
            while (isActive) {
                if (!isPaused) {
                    try {
                        val reading = collector.collect()
                        _telemetry.value = reading
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Retain previous valid reading on error
                    }
                }
                delay(pollingIntervalMs)
            }
        }
    }

    /**
     * Pauses the telemetry polling loop to conserve battery/CPU when backgrounded.
     */
    @Synchronized
    fun pause() {
        isPaused = true
    }

    /**
     * Resumes the telemetry polling loop when returning to foreground.
     */
    @Synchronized
    fun resume() {
        isPaused = false
    }

    /**
     * Stops the polling loop.
     */
    @Synchronized
    fun stop() {
        isPolling = false
        isPaused = false
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Manually triggers a single telemetry sample.
     */
    suspend fun sampleOnce(): DeviceTelemetry {
        return try {
            val reading = collector.collect()
            _telemetry.value = reading
            reading
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _telemetry.value
        }
    }
}
