package com.motionflow.player.core.media.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the arithmetic and the decisions built on the measurements: statistics over repeat runs,
 * defensible overhead, grouping, the feasibility gate, and the local report.
 *
 * The rule that runs through all of it is that an absent reading stays absent: no mean is computed over
 * a zero that meant "not measured", no ratio is taken against a baseline of zero, and no gate is
 * declared answered without evidence.
 */
class PerformanceStatisticsTest {

    private val support = PerformanceMeasurementSupport.forApiLevel(36)
    private val oldSupport = PerformanceMeasurementSupport.forApiLevel(26)

    // --- statistics -------------------------------------------------------------------------------

    @Test
    fun `statistics are computed over the runs that measured the metric`() {
        val stats = MetricStatistics.of(listOf(10.0, 20.0, null))

        assertEquals(2, stats.sampleCount)
        assertEquals(15.0, stats.mean!!, 0.0001)
        assertEquals(10.0, stats.minimum!!, 0.0001)
        assertEquals(20.0, stats.maximum!!, 0.0001)
        assertEquals(10.0, stats.range!!, 0.0001)
    }

    @Test
    fun `a metric no run measured has no statistics at all`() {
        val stats = MetricStatistics.of(listOf(null, null))

        assertEquals(0, stats.sampleCount)
        assertNull(stats.mean)
        assertFalse(stats.hasEvidence)
    }

    @Test
    fun `a series summarises only its usable runs`() {
        val usable = run(index = 1, status = PerformanceRunStatus.COMPLETE, durationMs = 30_000L)
        val incomplete = run(index = 2, status = PerformanceRunStatus.INCOMPLETE, durationMs = 4_000L)
        val series = PerformanceRunSeries(condition = usable.condition, runs = listOf(usable, incomplete))

        val stats = PerformanceSeriesStatistics.of(series)

        assertEquals("both runs are kept", 2, stats.runCount)
        assertEquals("one of them is evidence", 1, stats.usableRunCount)
        assertEquals(720.0, stats.renderedFrames.mean!!, 0.0001)
    }

    // --- overhead ---------------------------------------------------------------------------------

    @Test
    fun `overhead reports a difference and a ratio when the baseline is a real number`() {
        val overhead = PerformanceOverhead.of(
            PerformanceMetric.CPU_TIME,
            MetricStatistics.of(listOf(4_000.0)),
            MetricStatistics.of(listOf(5_000.0)),
        )

        assertEquals(1_000.0, overhead.absoluteDelta!!, 0.0001)
        assertEquals(0.25, overhead.relativeDelta!!, 0.0001)
        assertEquals(PerformanceOverheadBasis.ABSOLUTE_AND_RELATIVE, overhead.basis)
    }

    @Test
    fun `a zero baseline yields an absolute difference and no percentage`() {
        val overhead = PerformanceOverhead.of(
            PerformanceMetric.DROPPED_FRAMES,
            MetricStatistics.of(listOf(0.0, 0.0)),
            MetricStatistics.of(listOf(7.0)),
        )

        assertEquals(7.0, overhead.absoluteDelta!!, 0.0001)
        assertNull("dividing by zero is not a large ratio, it is an undefined one", overhead.relativeDelta)
        assertEquals(PerformanceOverheadBasis.ABSOLUTE_ONLY_BASELINE_IS_ZERO, overhead.basis)
    }

    @Test
    fun `a metric only one side measured has no difference to report`() {
        val overhead = PerformanceOverhead.of(
            PerformanceMetric.FRAME_PROCESSING_OFFSET,
            MetricStatistics.None,
            MetricStatistics.of(listOf(2.0)),
        )

        assertNull(overhead.absoluteDelta)
        assertNull(overhead.relativeDelta)
        assertEquals(PerformanceOverheadBasis.UNAVAILABLE_MEASURED_ON_ONE_SIDE_ONLY, overhead.basis)
    }

    @Test
    fun `the overhead list is fixed, so nothing is compared that cannot be`() {
        val metrics = PerformanceOverheadPolicy.compare(
            PerformanceSeriesStatistics.of(series(ProcessingPerformanceMode.NATIVE)),
            PerformanceSeriesStatistics.of(series(ProcessingPerformanceMode.EFFECT_PIPELINE)),
        ).map { it.metric }

        assertTrue(metrics.contains(PerformanceMetric.CPU_TIME))
        assertTrue(metrics.contains(PerformanceMetric.FIRST_FRAME_LATENCY))
        assertFalse("a metric with no reading is not invented into the list", metrics.contains(PerformanceMetric.GPU_UTILISATION))
    }

    // --- grouping ---------------------------------------------------------------------------------

    @Test
    fun `a run is numbered within its own condition, not across the archive`() {
        val native = run(mode = ProcessingPerformanceMode.NATIVE)
        val effect = run(mode = ProcessingPerformanceMode.EFFECT_PIPELINE)

        val archive = PerformanceArchive.Empty.record(native).record(effect).record(native)

        assertEquals(listOf(1, 1, 2), archive.runs.map { it.index })
    }

    @Test
    fun `runs of the same condition group into one series`() {
        val archive = PerformanceArchive.Empty
            .record(run(mode = ProcessingPerformanceMode.NATIVE))
            .record(run(mode = ProcessingPerformanceMode.NATIVE))
            .record(run(mode = ProcessingPerformanceMode.EFFECT_PIPELINE))

        assertEquals(2, archive.series.size)
        assertEquals(2, archive.seriesFor(ProcessingPerformanceMode.NATIVE).first().runs.size)
        assertEquals(1, archive.seriesFor(ProcessingPerformanceMode.EFFECT_PIPELINE).first().runs.size)
    }

    @Test
    fun `the comparable pair is matched by video, display rate and window, not by recency alone`() {
        val otherVideoNative = run(mode = ProcessingPerformanceMode.NATIVE, fingerprint = "video-b")
        val effectOnA = run(mode = ProcessingPerformanceMode.EFFECT_PIPELINE, fingerprint = "video-a")
        val nativeOnA = run(mode = ProcessingPerformanceMode.NATIVE, fingerprint = "video-a")
        val archive = PerformanceArchive.Empty
            .record(otherVideoNative)
            .record(nativeOnA)
            .record(effectOnA)

        val pair = archive.comparablePair()

        assertEquals("video-a", pair.native?.condition?.video?.fingerprint?.value)
        assertEquals("video-a", pair.effect?.condition?.video?.fingerprint?.value)
        assertTrue(pair.eligibility.eligible)
    }

    @Test
    fun `the comparable pair names the missing side when one pipeline has not been measured`() {
        val archive = PerformanceArchive.Empty.record(run(mode = ProcessingPerformanceMode.EFFECT_PIPELINE))

        val pair = archive.comparablePair()

        assertNull(pair.native)
        assertNotNull(pair.effect)
        assertTrue(pair.eligibility.blockers.contains(PerformanceComparisonBlocker.NATIVE_EVIDENCE_MISSING))
    }

    @Test
    fun `clearing the archive forgets every run`() {
        val archive = PerformanceArchive.Empty.record(run()).record(run())

        assertTrue(archive.clear().runs.isEmpty())
    }

    // --- the feasibility gate ----------------------------------------------------------------------

    @Test
    fun `without evidence the gate is unresolved, and says so in the right categories`() {
        val feasibility = PerformanceFeasibilityPolicy.evaluate(null, null, support)

        assertFalse(feasibility.isResolved)
        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.MEASUREMENT_INCOMPLETE))
        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.ADDITIONAL_DEVICE_DATA_REQUIRED))
        assertFalse("nothing measured means nothing is characterized", feasibility.states.contains(PerformanceFeasibilityState.PIPELINE_OVERHEAD_CHARACTERIZED))
        assertTrue(
            feasibility.evidence.any { it.bucket == PerformanceEvidenceBucket.NOT_TESTED },
        )
    }

    @Test
    fun `one effect-pipeline run shows the pipeline ran, and asks for more data`() {
        val feasibility = PerformanceFeasibilityPolicy.evaluate(
            native = null,
            effect = series(ProcessingPerformanceMode.EFFECT_PIPELINE),
            support = support,
        )

        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.PIPELINE_OPERATIONAL))
        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.ADDITIONAL_DEVICE_DATA_REQUIRED))
        assertFalse(feasibility.states.contains(PerformanceFeasibilityState.PIPELINE_OVERHEAD_CHARACTERIZED))
    }

    @Test
    fun `three comparable runs on each pipeline characterize the overhead and the cadence interaction`() {
        val native = seriesOf(ProcessingPerformanceMode.NATIVE, count = 3)
        val effect = seriesOf(ProcessingPerformanceMode.EFFECT_PIPELINE, count = 3)

        val feasibility = PerformanceFeasibilityPolicy.evaluate(native, effect, support)

        assertTrue(feasibility.isResolved)
        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.PIPELINE_OPERATIONAL))
        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.PIPELINE_OVERHEAD_CHARACTERIZED))
        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.CADENCE_INTERACTION_CHARACTERIZED))
        assertTrue(
            "the overhead line is a calculation over observed values",
            feasibility.evidence.any {
                it.bucket == PerformanceEvidenceBucket.CALCULATED &&
                    it.statement.contains("Overhead between the pipelines")
            },
        )
    }

    @Test
    fun `a higher thermal peak under the effect pipeline is observed, and reported as a step difference`() {
        val native = seriesOf(ProcessingPerformanceMode.NATIVE, count = 3, thermalPeak = 0)
        val effect = seriesOf(ProcessingPerformanceMode.EFFECT_PIPELINE, count = 3, thermalPeak = 2)

        val feasibility = PerformanceFeasibilityPolicy.evaluate(native, effect, support)

        assertTrue(feasibility.states.contains(PerformanceFeasibilityState.THERMAL_IMPACT_OBSERVED))
    }

    @Test
    fun `a cadence classification that changed with the pipeline is left as open, not explained away`() {
        val native = seriesOf(ProcessingPerformanceMode.NATIVE, count = 3, cadenceMode = "INTEGER_MULTIPLE")
        val effect = seriesOf(ProcessingPerformanceMode.EFFECT_PIPELINE, count = 3, cadenceMode = "MISMATCH")

        val feasibility = PerformanceFeasibilityPolicy.evaluate(native, effect, support)

        assertFalse(feasibility.states.contains(PerformanceFeasibilityState.CADENCE_INTERACTION_CHARACTERIZED))
        assertTrue(
            feasibility.evidence.any {
                it.bucket == PerformanceEvidenceBucket.HYPOTHESIS && it.statement.contains("cadence")
            },
        )
    }

    @Test
    fun `well-behaved repeat runs are not reported as hardware variance`() {
        val feasibility = PerformanceFeasibilityPolicy.evaluate(
            seriesOf(ProcessingPerformanceMode.NATIVE, count = 3),
            seriesOf(ProcessingPerformanceMode.EFFECT_PIPELINE, count = 3),
            support,
        )

        assertFalse(feasibility.states.contains(PerformanceFeasibilityState.HARDWARE_VARIANCE_OBSERVED))
    }

    @Test
    fun `metrics the platform cannot measure are catalogued as unknown, with a reason`() {
        val feasibility = PerformanceFeasibilityPolicy.evaluate(null, null, oldSupport)

        assertTrue(
            feasibility.evidence.any {
                it.bucket == PerformanceEvidenceBucket.UNKNOWN && it.statement.contains("gpu_utilisation")
            },
        )
        assertTrue(
            feasibility.evidence.any {
                it.bucket == PerformanceEvidenceBucket.UNKNOWN && it.statement.contains("thermal_status")
            },
        )
    }

    // --- the report --------------------------------------------------------------------------------

    @Test
    fun `the report is deterministic and carries the video's shape rather than its name`() {
        val native = seriesOf(ProcessingPerformanceMode.NATIVE, count = 3)
        val effect = seriesOf(ProcessingPerformanceMode.EFFECT_PIPELINE, count = 3)
        val feasibility = PerformanceFeasibilityPolicy.evaluate(native, effect, support)
        val device = DeviceRecord(apiLevel = 36, manufacturer = "Test", model = "Device")

        val first = PerformanceReport.text(device, native, effect, feasibility, support)
        val second = PerformanceReport.text(device, native, effect, feasibility, support)

        assertEquals("a report is reproducible", first, second)
        assertTrue("the video appears as its fingerprint", first.contains("video-a"))
        assertFalse("no path or file name may appear", first.contains("/") || first.contains(".mp4"))
        assertTrue(first.contains("MEASUREMENT") || first.contains("OVERHEAD"))
    }

    @Test
    fun `the report lists unsupported metrics instead of leaving a blank`() {
        val text = PerformanceReport.text(
            DeviceRecord(),
            native = null,
            effect = null,
            feasibility = PerformanceFeasibilityPolicy.evaluate(null, null, oldSupport),
            support = oldSupport,
        )

        assertTrue(text.contains("gpu_utilisation"))
        assertTrue(text.contains("thermal_headroom"))
        assertTrue(text.contains("battery_drain"))
        assertTrue("the gate is stated as a state, not as a verdict", text.contains("MEASUREMENT_INCOMPLETE"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun run(
        index: Int = 1,
        mode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE,
        status: PerformanceRunStatus = PerformanceRunStatus.COMPLETE,
        fingerprint: String = "video-a",
        durationMs: Long = 30_000L,
        thermalPeak: Int? = 0,
        cadenceMode: String = "INTEGER_MULTIPLE",
        renderedFrames: Int? = 720,
    ): PerformanceRun {
        val condition = condition(mode = mode, fingerprint = fingerprint)
        return PerformanceRun(
            index = index,
            startedAt = RunTimestamp(1_000L),
            condition = condition,
            snapshot = FramePerformanceSnapshot(
                renderedFrames = renderedFrames,
                droppedFrames = 1,
                firstFrameLatencyMs = 412L,
                decoderInitializationMs = 120L,
                measurementDurationMs = durationMs,
                videoWidth = 1920,
                videoHeight = 1080,
                cpuTimeMs = 4_000L,
                processPssKb = 180_000L,
                thermalStatusAtStart = 0,
                thermalStatusAtEnd = thermalPeak,
                thermalStatusPeak = thermalPeak,
            ),
            unsupported = emptyList(),
            cadence = CadenceObservation(
                cadenceMode = cadenceMode,
                cadenceReason = "REASON",
                pacingMode = "NATURAL_CADENCE",
                pacingApplied = false,
                sourceFps = 24f,
                displayRefreshRateHz = 60f,
            ),
            status = status,
        )
    }

    private fun condition(
        mode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE,
        fingerprint: String = "video-a",
    ) = RunCondition(
        mode = mode,
        video = VideoCharacteristics(
            fingerprint = VideoFingerprint(fingerprint),
            sourceFps = 24f,
            namedRate = "24",
            isVariableFrameRate = false,
            confidence = "HIGH",
            width = 1920,
            height = 1080,
            containerType = "video/mp4",
            durationMs = 600_000L,
        ),
        display = DisplayCharacteristics(
            panelRefreshRatesHz = listOf(60f, 120f),
            requestedRefreshRateHz = 60f,
            appliedRefreshRateHz = 60f,
            automaticSelectionEnabled = true,
            outcome = DisplayRequestOutcome.HONOURED,
            engineStatus = "MATCHED",
        ),
        length = PerformanceSessionLength.THIRTY,
    )

    private fun series(mode: ProcessingPerformanceMode) = PerformanceRunSeries(
        condition = condition(mode = mode),
        runs = listOf(run(mode = mode).copy(condition = condition(mode = mode))),
    )

    private fun seriesOf(
        mode: ProcessingPerformanceMode,
        count: Int,
        thermalPeak: Int? = 0,
        cadenceMode: String = "INTEGER_MULTIPLE",
    ) = PerformanceRunSeries(
        condition = condition(mode = mode),
        runs = (1..count).map {
            run(mode = mode, index = it, thermalPeak = thermalPeak, cadenceMode = cadenceMode)
                .copy(condition = condition(mode = mode))
        },
    )
}
