package com.motionflow.player.core.media.performance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two series a comparison needs: one per pipeline, of the same video, display rate and window. */
data class PerformanceComparisonPair(
    val native: PerformanceRunSeries? = null,
    val effect: PerformanceRunSeries? = null,
) {

    val eligibility: PerformanceComparisonEligibility
        get() = PerformanceComparisonEligibility.of(native, effect)
}

/**
 * Every run recorded in this process, grouped by the condition it was taken under.
 *
 * A single ordered list and a grouping, and nothing else: no database, no schema, no migration. The
 * representation is deliberately deterministic — runs keep the order they were taken in, and a series is
 * keyed by the whole condition — because the point of the archive is that two people looking at it see
 * the same thing.
 *
 * Nothing here is persisted. An archive that survived a process would need a store, a version and a
 * privacy story, and a characterization you can run again in a minute does not need one.
 */
data class PerformanceArchive(val runs: List<PerformanceRun> = emptyList()) {

    /** Every distinct condition that has been measured, in the order its first run appeared. */
    val conditions: List<RunCondition>
        get() = runs.map { it.condition }.distinct()

    /** One series per measured condition. */
    val series: List<PerformanceRunSeries>
        get() = conditions.map { condition ->
            PerformanceRunSeries(condition, runs.filter { it.condition == condition })
        }

    /** The series measured on [mode], most recent first. */
    fun seriesFor(mode: ProcessingPerformanceMode): List<PerformanceRunSeries> =
        series.filter { it.mode == mode }.reversed()

    /**
     * Adds a run, numbering it within the series it belongs to.
     *
     * The index is assigned here rather than by the caller, so "run 3 of 3" counts the runs of *this
     * condition* and not everything the process has measured.
     */
    fun record(run: PerformanceRun): PerformanceArchive {
        val existing = runs.count { it.condition == run.condition }
        return copy(runs = runs + run.copy(index = existing + 1))
    }

    /** Forgets everything, so a characterization can start from nothing. */
    fun clear(): PerformanceArchive = Empty

    /**
     * The most recent pair of series that describe the same conditions on both pipelines.
     *
     * The pair is chosen by *condition* rather than by recency alone: the newest effect-pipeline run is
     * matched with a native-pipeline run of the same video, display rate and window, which is exactly the
     * rule §5 of this phase's brief sets. When no such pair exists, the corresponding side is `null` and
     * the eligibility check says why a comparison cannot be made.
     */
    fun comparablePair(): PerformanceComparisonPair {
        val newestEffect = seriesFor(ProcessingPerformanceMode.EFFECT_PIPELINE).firstOrNull()
        val newestNative = seriesFor(ProcessingPerformanceMode.NATIVE).firstOrNull()

        // Whichever pipeline was measured last decides which condition is being characterized.
        val anchor = newestEffect ?: newestNative ?: return PerformanceComparisonPair()
        val wanted = anchor.condition

        val matchingNative = series.firstOrNull {
            it.mode == ProcessingPerformanceMode.NATIVE && it.condition.matchesVideoAndDisplayOf(wanted)
        }
        val matchingEffect = series.firstOrNull {
            it.mode == ProcessingPerformanceMode.EFFECT_PIPELINE && it.condition.matchesVideoAndDisplayOf(wanted)
        }

        return PerformanceComparisonPair(native = matchingNative, effect = matchingEffect)
    }

    companion object {

        /** Nothing measured yet. */
        val Empty = PerformanceArchive()
    }
}

/**
 * True when two conditions describe the same video, display rate and window.
 *
 * The pipeline is deliberately excluded: that is the one thing a comparison is allowed to vary. The
 * duration is included, because a ten-second window and a thirty-second one are not the same measurement.
 */
private fun RunCondition.matchesVideoAndDisplayOf(other: RunCondition): Boolean =
    video.fingerprint == other.video.fingerprint &&
        video.width == other.video.width &&
        video.height == other.video.height &&
        video.sourceFps == other.video.sourceFps &&
        display.effectiveRefreshRateHz == other.display.effectiveRefreshRateHz &&
        length == other.length

/**
 * Holds the archive for the length of one process.
 *
 * Application-scoped, like the pipeline choice and the history before it: a characterization spans runs
 * taken minutes apart, and changing the pipeline restarts the media session. Nothing is written to disk
 * and nothing leaves the device unless a person asks for the export, which is a share action they take
 * themselves.
 */
class PerformanceRunStore(initial: PerformanceArchive = PerformanceArchive.Empty) {

    private val _archive = MutableStateFlow(initial)

    /** Every run recorded so far, grouped on demand. */
    val archive: StateFlow<PerformanceArchive> = _archive.asStateFlow()

    /** Files a run under its condition. */
    fun record(run: PerformanceRun) {
        _archive.value = _archive.value.record(run)
    }

    /** Forgets every run. */
    fun clear() {
        _archive.value = _archive.value.clear()
    }
}
