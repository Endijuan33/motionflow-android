package com.motionflow.player.core.media.performance

/**
 * The hardware facts that make a measurement interpretable.
 *
 * Only facts that change how a number should be read, and only ones that are public and non-identifying:
 * an API level explains which thermal API exists, the ABI explains what the decoder is, the memory
 * class explains how close the heap is to its ceiling, and the display's own modes explain what the
 * refresh engine had to choose from. Nothing here is uploaded, and nothing here identifies a person or
 * a device — no identifier, no account, no location, no serial.
 */
data class DeviceCharacteristics(
    val apiLevel: Int? = null,
    val primaryAbi: String? = null,
    val memoryClassMb: Int? = null,
    val supportedDisplayRefreshRatesHz: List<Float> = emptyList(),
    val thermalApiAvailable: Boolean = false,
) {

    companion object {

        /** Nothing known yet. */
        val Unknown = DeviceCharacteristics()
    }
}

/**
 * The whole performance picture, as one immutable value.
 *
 * Deliberately separate from the cadence, refresh-rate, rendering and processing diagnostics: those
 * describe what the hardware and the pipeline *are*, and this describes what a controlled measurement
 * of them *found*. A panel can show both without either model having to grow a field for the other.
 */
data class PerformanceDiagnostics(
    /** The pipeline the engine is currently configured for, or [ProcessingPerformanceMode.FAILED]. */
    val mode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE,

    /** The session in progress or last run, or `null` when none has been run. */
    val session: PerformanceSession? = null,

    /** The latest measurement. Empty means nothing has been measured yet. */
    val snapshot: FramePerformanceSnapshot = FramePerformanceSnapshot.Empty,

    /** What this platform can measure at all, so an absent number can be attributed. */
    val support: PerformanceMeasurementSupport = PerformanceMeasurementSupport(),

    /** The device facts a measurement should be read against. */
    val device: DeviceCharacteristics = DeviceCharacteristics.Unknown,
) {

    /** True while a session is collecting. */
    val isMeasuring: Boolean get() = session?.isRunning == true

    /** True when there is a measurement to show. */
    val hasMeasurement: Boolean get() = !snapshot.isEmpty

    companion object {

        /** Nothing measured, nothing running. */
        val Idle = PerformanceDiagnostics()
    }
}
