package com.motionflow.player.core.media.performance.android

import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import com.motionflow.player.core.media.performance.DeviceCharacteristics
import com.motionflow.player.core.media.performance.FramePerformanceReadings
import com.motionflow.player.core.media.performance.PerformanceMeasurementSupport
import java.util.concurrent.Executor

/**
 * Reads the process and platform facts a measurement is written against.
 *
 * Every reading here comes from public Android API, and each one is chosen for a unit that the platform
 * documents rather than for convenience:
 *
 * | Reading | API | Unit |
 * | --- | --- | --- |
 * | Process CPU time | `android.os.Process.getElapsedCpuTime()` | milliseconds, for this process |
 * | Resident memory | `android.os.Debug.getPss()` | kilobytes, from `/proc/self/smaps` |
 * | Managed heap | `Runtime.totalMemory() - freeMemory()` | bytes, and only the managed part |
 * | Thermal status | `PowerManager.getCurrentThermalStatus()` | the platform's own ordinal, API 29+ |
 *
 * A zero `getPss()` means the platform could not read the process, which is reported as *not measured*
 * rather than as a process using no memory.
 *
 * ## What it deliberately does not read
 *
 * No GPU utilisation, no thermal headroom and no battery attribution, because no public API provides
 * them: see [PerformanceMeasurementSupport.unsupported]. No identifier of any kind is read at all.
 *
 * It holds a `PowerManager` rather than a `Context`, so nothing here can keep a UI context alive, and it
 * is told the API level instead of reading a static it might be tested against.
 */
class AndroidPerformanceProbe(
    private val powerManager: PowerManager?,
    private val executor: Executor?,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val memoryClassMb: Int? = null,
) {

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** One set of readings, taken now. */
    fun readings(): FramePerformanceReadings = FramePerformanceReadings(
        cpuTimeMs = runCatching { Process.getElapsedCpuTime() }.getOrNull(),
        processPssKb = runCatching { Debug.getPss() }.getOrNull()?.takeIf { it > 0 },
        heapUsedBytes = runCatching {
            val runtime = Runtime.getRuntime()
            runtime.totalMemory() - runtime.freeMemory()
        }.getOrNull(),
        thermalStatus = currentThermalStatus(),
    )

    /** The thermal status this platform reports, or `null` where the API does not exist. */
    fun currentThermalStatus(): Int? {
        if (apiLevel < PerformanceMeasurementSupport.THERMAL_STATUS_API_LEVEL) return null
        return runCatching { powerManager?.currentThermalStatus }.getOrNull()
    }

    /** What this platform can measure, derived from its API level rather than assumed. */
    fun support(): PerformanceMeasurementSupport = PerformanceMeasurementSupport.forApiLevel(apiLevel)

    /**
     * The device facts a reader needs to interpret a measurement.
     *
     * The display's modes arrive from the refresh-rate engine rather than being read again here: that
     * engine already owns the display, and a second reader could disagree with the one that actually
     * chooses a mode.
     */
    fun deviceCharacteristics(supportedDisplayRefreshRatesHz: List<Float>): DeviceCharacteristics =
        DeviceCharacteristics(
            apiLevel = apiLevel,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull(),
            memoryClassMb = memoryClassMb,
            supportedDisplayRefreshRatesHz = supportedDisplayRefreshRatesHz,
            thermalApiAvailable = apiLevel >= PerformanceMeasurementSupport.THERMAL_STATUS_API_LEVEL,
        )

    /**
     * Starts watching for thermal changes, and reports each one.
     *
     * A listener rather than a poll: the platform publishes status changes, so a session pays for them
     * only when they happen, and a measurement cannot be contaminated by a timer asking the same
     * question sixty times a minute.
     */
    fun startThermalWatch(onStatus: (Int) -> Unit) {
        if (apiLevel < PerformanceMeasurementSupport.THERMAL_STATUS_API_LEVEL) return
        val manager = powerManager ?: return
        // The executor is not optional: the accumulator that receives these statuses is fed on the
        // playback thread by Media3's callbacks, and a thermal callback arriving on a binder thread
        // would be a data race on a counter that no lock protects. Registering without one is not
        // possible here, and the caller supplies the playback thread.
        val handler = executor ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
        val register = runCatching { manager.addThermalStatusListener(handler, listener) }
        if (register.isSuccess) thermalListener = listener
    }

    /** Stops watching. Safe to call when nothing was ever registered. */
    fun stopThermalWatch() {
        val listener = thermalListener ?: return
        thermalListener = null
        runCatching { powerManager?.removeThermalStatusListener(listener) }
    }

    /** True when a thermal listener is currently registered. */
    val isWatchingThermal: Boolean get() = thermalListener != null
}
