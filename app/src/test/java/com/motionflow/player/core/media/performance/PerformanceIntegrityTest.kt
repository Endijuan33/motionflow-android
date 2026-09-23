package com.motionflow.player.core.media.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the rules that decide whether a run is evidence.
 *
 * Every case here is about the *record*: a run is never judged on how it performed, only on whether it
 * says enough to be read, and on whether it claims something the platform cannot provide.
 */
class PerformanceIntegrityTest {

    private val support = PerformanceMeasurementSupport.forApiLevel(36)

    @Test
    fun `a complete run with everything recorded has nothing wrong with it`() {
        val findings = PerformanceIntegrity.issues(completeRun(), support)

        assertTrue("expected no findings, got ${findings.map { it.issue }}", findings.isEmpty())
        assertTrue(PerformanceIntegrity.isUsable(completeRun(), support))
    }

    @Test
    fun `an incomplete session is named, and the run stays in the record`() {
        val run = completeRun(status = PerformanceRunStatus.INCOMPLETE)

        val findings = PerformanceIntegrity.issues(run, support)

        assertTrue(findings.any { it.issue == PerformanceIntegrityIssue.SESSION_INCOMPLETE })
        assertFalse("an incomplete run is not evidence", PerformanceIntegrity.isUsable(run, support))
    }

    @Test
    fun `a failed session is represented as failed rather than discarded`() {
        val run = completeRun(status = PerformanceRunStatus.FAILED)

        val findings = PerformanceIntegrity.issues(run, support)

        assertTrue(findings.any { it.issue == PerformanceIntegrityIssue.SESSION_FAILED })
    }

    @Test
    fun `a run claiming completion but lasting less than its window is a contradiction`() {
        val run = completeRun(durationMs = 4_000L)

        val findings = PerformanceIntegrity.issues(run, support)

        assertTrue(findings.any { it.issue == PerformanceIntegrityIssue.SESSION_NOT_RUN_FOR_ITS_WINDOW })
    }

    @Test
    fun `a run that measured nothing is named as empty`() {
        val run = completeRun(snapshot = FramePerformanceSnapshot())

        val findings = PerformanceIntegrity.issues(run, support)

        assertTrue(findings.any { it.issue == PerformanceIntegrityIssue.MEASUREMENT_EMPTY })
    }

    @Test
    fun `a video that cannot be told from another one blocks a comparison`() {
        val run = completeRun(fingerprint = null)

        assertTrue(
            PerformanceIntegrity.issues(run, support)
                .any { it.issue == PerformanceIntegrityIssue.VIDEO_NOT_IDENTIFIABLE },
        )
    }

    @Test
    fun `a missing source rate or display rate is named, because both are half of the pair cadence needs`() {
        val withoutRate = PerformanceIntegrity.issues(completeRun(sourceFps = null), support)
        val withoutDisplay = PerformanceIntegrity.issues(completeRun(displayHz = null), support)

        assertTrue(withoutRate.any { it.issue == PerformanceIntegrityIssue.SOURCE_RATE_MISSING })
        assertTrue(withoutDisplay.any { it.issue == PerformanceIntegrityIssue.DISPLAY_RATE_MISSING })
    }

    @Test
    fun `a missing cadence classification is named, because the interaction cannot then be checked`() {
        val run = completeRun(cadence = null)

        assertTrue(
            PerformanceIntegrity.issues(run, support)
                .any { it.issue == PerformanceIntegrityIssue.CADENCE_NOT_RECORDED },
        )
    }

    @Test
    fun `a metric the platform cannot measure must be absent, and the finding names it`() {
        // API 26 has no thermal status API, so a thermal reading is a fabricated one.
        val oldSupport = PerformanceMeasurementSupport.forApiLevel(26)
        val run = completeRun(
            snapshot = snapshot(thermalStatusAtEnd = 1),
        )

        val findings = PerformanceIntegrity.issues(run, oldSupport)

        val fabricated = findings.filter { it.issue == PerformanceIntegrityIssue.UNSUPPORTED_METRIC_REPORTED }
        assertTrue(fabricated.isNotEmpty())
        assertEquals(PerformanceMetric.THERMAL_STATUS, fabricated.first().metric)
    }

    @Test
    fun `a zero frame-processing offset with no total is a zero where nothing was measured`() {
        val run = completeRun(
            snapshot = snapshot(processingOffsetTotalUs = null, processingOffsetFrames = 0),
        )

        assertTrue(
            PerformanceIntegrity.issues(run, support)
                .any { it.issue == PerformanceIntegrityIssue.ZERO_WHERE_NOT_MEASURED },
        )
    }

    @Test
    fun `two complete series of the same video and conditions are comparable`() {
        val native = series(ProcessingPerformanceMode.NATIVE, PerformanceRunStatus.COMPLETE)
        val effect = series(ProcessingPerformanceMode.EFFECT_PIPELINE, PerformanceRunStatus.COMPLETE)

        val eligibility = PerformanceComparisonEligibility.of(native, effect)

        assertTrue("expected comparable, blocked by ${eligibility.blockers}", eligibility.eligible)
    }

    @Test
    fun `a comparison without both pipelines says which side is missing`() {
        val onlyEffect = PerformanceComparisonEligibility.of(null, series(ProcessingPerformanceMode.EFFECT_PIPELINE))

        assertTrue(onlyEffect.blockers.contains(PerformanceComparisonBlocker.NATIVE_EVIDENCE_MISSING))
        assertFalse(onlyEffect.eligible)
    }

    @Test
    fun `only the pipeline may differ, and each way the rule can be broken is named`() {
        val base = series(ProcessingPerformanceMode.NATIVE)
        val differentVideo = series(ProcessingPerformanceMode.EFFECT_PIPELINE, fingerprint = "other")
        val differentRate = series(ProcessingPerformanceMode.EFFECT_PIPELINE, sourceFps = 30f)
        val differentDisplay = series(ProcessingPerformanceMode.EFFECT_PIPELINE, displayHz = 120f)
        val differentWindow = series(ProcessingPerformanceMode.EFFECT_PIPELINE, length = PerformanceSessionLength.TEN)

        assertTrue(
            PerformanceComparisonEligibility.of(base, differentVideo)
                .blockers.contains(PerformanceComparisonBlocker.DIFFERENT_VIDEO),
        )
        assertTrue(
            PerformanceComparisonEligibility.of(base, differentRate)
                .blockers.contains(PerformanceComparisonBlocker.DIFFERENT_SOURCE_RATE),
        )
        assertTrue(
            PerformanceComparisonEligibility.of(base, differentDisplay)
                .blockers.contains(PerformanceComparisonBlocker.DIFFERENT_DISPLAY_RATE),
        )
        assertTrue(
            PerformanceComparisonEligibility.of(base, differentWindow)
                .blockers.contains(PerformanceComparisonBlocker.DIFFERENT_DURATION),
        )
    }

    @Test
    fun `an incomplete or failed run in either series blocks the comparison`() {
        val incomplete = series(ProcessingPerformanceMode.NATIVE, PerformanceRunStatus.INCOMPLETE)
        val failed = series(ProcessingPerformanceMode.NATIVE, PerformanceRunStatus.FAILED)

        assertTrue(
            PerformanceComparisonEligibility.of(incomplete, series(ProcessingPerformanceMode.EFFECT_PIPELINE))
                .blockers.contains(PerformanceComparisonBlocker.INCOMPLETE_RUN_PRESENT),
        )
        assertTrue(
            PerformanceComparisonEligibility.of(failed, series(ProcessingPerformanceMode.EFFECT_PIPELINE))
                .blockers.contains(PerformanceComparisonBlocker.FAILED_RUN_PRESENT),
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun snapshot(
        thermalStatusAtEnd: Int? = 0,
        processingOffsetTotalUs: Long? = 1_000L,
        processingOffsetFrames: Int? = 5,
    ) = FramePerformanceSnapshot(
        renderedFrames = 720,
        droppedFrames = 1,
        firstFrameLatencyMs = 412L,
        decoderInitializationMs = 120L,
        playbackPositionMs = 30_000L,
        measurementDurationMs = 30_000L,
        videoWidth = 1920,
        videoHeight = 1080,
        cpuTimeMs = 4_000L,
        processPssKb = 180_000L,
        heapUsedBytes = 12_000_000L,
        thermalStatusAtStart = 0,
        thermalStatusAtEnd = thermalStatusAtEnd,
        thermalStatusPeak = thermalStatusAtEnd,
        frameProcessingOffsetTotalUs = processingOffsetTotalUs,
        frameProcessingOffsetFrames = processingOffsetFrames,
    )

    private fun completeRun(
        mode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE,
        status: PerformanceRunStatus = PerformanceRunStatus.COMPLETE,
        durationMs: Long = 30_000L,
        fingerprint: String? = "video-a",
        sourceFps: Float? = 24f,
        displayHz: Float? = 60f,
        cadence: CadenceObservation? = cadence(),
        snapshot: FramePerformanceSnapshot = snapshot(),
    ) = PerformanceRun(
        index = 1,
        startedAt = RunTimestamp(1_000L),
        condition = RunCondition(
            mode = mode,
            video = VideoCharacteristics(
                fingerprint = fingerprint?.let(::VideoFingerprint),
                sourceFps = sourceFps,
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
                requestedRefreshRateHz = displayHz,
                appliedRefreshRateHz = displayHz,
                automaticSelectionEnabled = true,
                outcome = DisplayRequestOutcome.HONOURED,
                engineStatus = "MATCHED",
            ),
            length = PerformanceSessionLength.THIRTY,
        ),
        snapshot = snapshot.copy(measurementDurationMs = durationMs),
        unsupported = snapshot.unavailable,
        cadence = cadence,
        status = status,
    )

    private fun cadence() = CadenceObservation(
        cadenceMode = "INTEGER_MULTIPLE",
        cadenceReason = "FIVE_REFRESHES_PER_FRAME",
        pacingMode = "NATURAL_CADENCE",
        pacingApplied = false,
        sourceFps = 24f,
        displayRefreshRateHz = 60f,
    )

    private fun series(
        mode: ProcessingPerformanceMode,
        status: PerformanceRunStatus = PerformanceRunStatus.COMPLETE,
        fingerprint: String = "video-a",
        sourceFps: Float = 24f,
        displayHz: Float = 60f,
        length: PerformanceSessionLength = PerformanceSessionLength.THIRTY,
    ): PerformanceRunSeries {
        // One condition, used by both the series and its run: the grouping key and the run's own record
        // must be the same value or the fixture would be testing something the application cannot build.
        val condition = RunCondition(
            mode = mode,
            video = VideoCharacteristics(
                fingerprint = VideoFingerprint(fingerprint),
                sourceFps = sourceFps,
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
                requestedRefreshRateHz = displayHz,
                appliedRefreshRateHz = displayHz,
                automaticSelectionEnabled = true,
                outcome = DisplayRequestOutcome.HONOURED,
                engineStatus = "MATCHED",
            ),
            length = length,
        )

        return PerformanceRunSeries(
            condition = condition,
            runs = listOf(completeRun(mode = mode, status = status).copy(condition = condition)),
        )
    }
}

