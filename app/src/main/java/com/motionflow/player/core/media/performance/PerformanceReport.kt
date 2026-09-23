package com.motionflow.player.core.media.performance

/**
 * The hardware a measurement was taken on, recorded only as far as it takes to interpret the numbers.
 *
 * Every field here is either a platform capability or a coarse hardware class. Nothing identifies a
 * person, a device or an account: no IMEI, no serial, no Android ID, no advertising ID, no account, no
 * location, no file path. Manufacturer and model are shared by millions of devices and are the minimum
 * needed to say which class of hardware a number came from — a measurement from one device is evidence
 * about that class of device and nothing more, which is why the record says so little and the report says
 * so.
 *
 * [media3Version] and [build] are recorded because a measurement is only evidence about a build: a
 * number without them cannot be reproduced or even dated.
 */
data class DeviceRecord(
    val apiLevel: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val soc: String? = null,
    val primaryAbi: String? = null,
    val cpuArchitecture: String? = null,
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
    val screenDensityDpi: Int? = null,
    val hdrCapable: Boolean? = null,
    val supportedDisplayRefreshRatesHz: List<Float> = emptyList(),
    val memoryClassMb: Int? = null,
    val thermalApiAvailable: Boolean = false,
    val media3Version: String? = null,
    val build: String? = null,
) {

    companion object {

        /** Nothing known yet. */
        val Unknown = DeviceRecord()
    }
}

/**
 * Renders a characterization as plain text, locally.
 *
 * Plain text and a fixed field order, because the whole point of an export is that two of them can be
 * put side by side and read. It carries no names, no paths and no identifiers — the video appears as its
 * fingerprint, which is derived from its shape and reveals nothing about it — and it is produced only
 * when a person asks for it: nothing here is uploaded, scheduled or sent anywhere.
 *
 * The wording rules the UI follows apply here too, and matter more: this text is what a hardware report
 * ends up containing, so it states measurements and differences, never a score, a rank or a verdict.
 */
object PerformanceReport {

    /** The whole record as text: what was measured, on what, and what the evidence does not settle. */
    fun text(
        device: DeviceRecord,
        native: PerformanceRunSeries?,
        effect: PerformanceRunSeries?,
        feasibility: PerformanceFeasibility,
        support: PerformanceMeasurementSupport,
    ): String = buildString {
        appendLine("MotionFlow hardware characterization")
        appendLine("===================================")
        appendLine()
        appendLine("Build")
        appendLine("  MotionFlow      ${device.build ?: NOT_KNOWN}")
        appendLine("  Media3          ${device.media3Version ?: NOT_KNOWN}")
        appendLine()
        appendLine("Device")
        appendLine("  API level       ${device.apiLevel ?: NOT_KNOWN}")
        appendLine("  Manufacturer    ${device.manufacturer ?: NOT_KNOWN}")
        appendLine("  Model           ${device.model ?: NOT_KNOWN}")
        appendLine("  SoC             ${device.soc ?: NOT_KNOWN}")
        appendLine("  ABI             ${device.primaryAbi ?: NOT_KNOWN}")
        appendLine("  Architecture    ${device.cpuArchitecture ?: NOT_KNOWN}")
        appendLine(
            "  Screen          " +
                listOfNotNull(
                    device.screenWidthPx?.toString(),
                    device.screenHeightPx?.toString(),
                ).joinToString("x").ifEmpty { NOT_KNOWN } +
                (device.screenDensityDpi?.let { " at ${it} dpi" } ?: ""),
        )
        appendLine("  HDR capable     ${device.hdrCapable?.toString() ?: NOT_KNOWN}")
        appendLine(
            "  Display modes   " + device.supportedDisplayRefreshRatesHz
                .joinToString(", ") { "$it Hz" }
                .ifEmpty { NOT_KNOWN },
        )
        appendLine("  Memory class    ${device.memoryClassMb?.let { "$it MB" } ?: NOT_KNOWN}")
        appendLine("  Thermal API     ${if (device.thermalApiAvailable) "available" else "unavailable"}")
        appendLine()

        appendSeries("Baseline A — native Media3", native)
        appendSeries("Baseline B — effect pipeline", effect)

        appendLine("Comparison")
        val eligibility = PerformanceComparisonEligibility.of(native, effect)
        if (!eligibility.eligible) {
            appendLine("  No valid comparison: ${eligibility.blockers.joinToString(", ") { it.name }}")
        } else {
            val nativeStats = PerformanceSeriesStatistics.of(native!!)
            val effectStats = PerformanceSeriesStatistics.of(effect!!)
            PerformanceOverheadPolicy.compare(nativeStats, effectStats).forEach { overhead ->
                appendLine("  ${overhead.metric.name.lowercase()}")
                appendLine("    native mean   ${overhead.nativeMean ?: NOT_MEASURED}")
                appendLine("    effect mean   ${overhead.effectMean ?: NOT_MEASURED}")
                appendLine("    difference    ${overhead.absoluteDelta ?: NOT_MEASURED}")
                appendLine(
                    "    ratio         " + (
                        overhead.relativeDelta?.let { "$it" }
                            ?: when (overhead.basis) {
                                PerformanceOverheadBasis.ABSOLUTE_ONLY_BASELINE_IS_ZERO ->
                                    "not applicable: the native mean is zero"
                                else -> NOT_MEASURED
                            }
                        ),
                )
            }
        }
        appendLine()

        appendLine("Not measurable on this platform")
        if (support.unsupported.isEmpty()) {
            appendLine("  (none)")
        } else {
            support.unsupported.forEach { appendLine("  ${it.name.lowercase()}") }
        }
        appendLine()

        appendLine("Evidence")
        appendLine("  states: ${feasibility.stateNames.joinToString(", ")}")
        feasibility.evidence.forEach { line ->
            appendLine("  [${line.bucket.name}] ${line.statement}")
        }
    }

    private fun StringBuilder.appendSeries(title: String, series: PerformanceRunSeries?) {
        appendLine(title)
        val runs = series?.runs.orEmpty()
        if (runs.isEmpty()) {
            appendLine("  no runs recorded")
            appendLine()
            return
        }

        runs.forEach { run ->
            appendLine("  Run ${run.index} — ${run.status.name.lowercase()}")
            appendLine("    video          ${run.condition.video.fingerprint?.value ?: NOT_KNOWN}")
            appendLine("    source rate    ${run.condition.video.sourceFps ?: NOT_MEASURED} fps")
            appendLine("    resolution     ${resolutionOf(run)}")
            appendLine("    display        ${run.condition.display.effectiveRefreshRateHz ?: NOT_MEASURED} Hz " +
                "(${run.condition.display.outcome.name.lowercase()})")
            appendLine("    window         ${run.condition.length.seconds} s")
            appendLine("    measured for   ${run.snapshot.measurementDurationMs ?: NOT_MEASURED} ms")
            appendLine("    rendered       ${run.snapshot.renderedFrames ?: NOT_MEASURED}")
            appendLine("    dropped        ${run.snapshot.droppedFrames ?: NOT_MEASURED}")
            appendLine("    first frame    ${run.snapshot.firstFrameLatencyMs ?: NOT_MEASURED} ms")
            appendLine("    decoder init   ${run.snapshot.decoderInitializationMs ?: NOT_MEASURED} ms")
            appendLine("    offset total   ${run.snapshot.frameProcessingOffsetTotalUs ?: NOT_MEASURED} us")
            appendLine("    offset frames  ${run.snapshot.frameProcessingOffsetFrames ?: NOT_MEASURED}")
            appendLine("    mean offset    ${run.snapshot.averageFrameProcessingOffsetMs ?: NOT_MEASURED} ms")
            appendLine("    cpu delta      ${run.snapshot.cpuTimeMs ?: NOT_MEASURED} ms")
            appendLine("    pss            ${run.snapshot.processPssKb ?: NOT_MEASURED} kB")
            appendLine("    heap           ${run.snapshot.heapUsedBytes ?: NOT_MEASURED} bytes")
            appendLine("    thermal        start ${run.snapshot.thermalStatusAtStart ?: NOT_MEASURED}, " +
                "end ${run.snapshot.thermalStatusAtEnd ?: NOT_MEASURED}, " +
                "peak ${run.snapshot.thermalStatusPeak ?: NOT_MEASURED}")
            appendLine("    cadence        ${run.cadence?.cadenceMode ?: NOT_MEASURED} " +
                "(${run.cadence?.cadenceReason ?: NOT_MEASURED})")
            run.snapshot.unavailable.takeIf { it.isNotEmpty() }?.let { unavailable ->
                appendLine("    not measured   ${unavailable.joinToString(", ") { it.name.lowercase() }}")
            }
        }

        val stats = series?.let(PerformanceSeriesStatistics::of)
        if (stats != null && stats.usableRunCount > 1) {
            appendLine("  Across ${stats.usableRunCount} usable runs (mean / min / max / range)")
            appendLine("    rendered       ${stats.renderedFrames.line()}")
            appendLine("    dropped        ${stats.droppedFrames.line()}")
            appendLine("    first frame    ${stats.firstFrameLatencyMs.line()}")
            appendLine("    cpu delta      ${stats.cpuTimeMs.line()}")
            appendLine("    pss            ${stats.processPssKb.line()}")
            appendLine("    thermal peak   ${stats.thermalStatusPeak.line()}")
        }
        appendLine()
    }

    private fun MetricStatistics.line(): String =
        if (!hasEvidence) NOT_MEASURED else "$mean / $minimum / $maximum / $range"

    private fun resolutionOf(run: PerformanceRun): String =
        listOfNotNull(run.condition.video.width, run.condition.video.height)
            .joinToString("x")
            .ifEmpty { NOT_KNOWN }

    /** Wording for something no run measured. Never a zero, and never a dash that could be a zero. */
    const val NOT_MEASURED = "not measured"

    /** Wording for something the platform does not expose. */
    const val NOT_KNOWN = "unknown"
}
