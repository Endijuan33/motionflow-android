package com.motionflow.player.core.media.performance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the last measurement taken on each pipeline, for the length of one process.
 *
 * Application-scoped, because a comparison spans two runs of the same video under two pipelines and
 * changing the pipeline restarts the service that produced the first one. A history kept anywhere
 * shorter-lived would be destroyed by the very action that makes a comparison possible.
 *
 * It is a store and not a model: the comparison itself is a pure value derived from what is here
 * ([PerformanceHistory.comparison]). Nothing is written to disk, nothing is sent anywhere, and there is
 * no way to persist it — an experiment's memory should not outlive the experiment.
 */
class PerformanceHistoryStore {

    private val _history = MutableStateFlow(PerformanceHistory.Empty)

    /** The two baselines measured so far. */
    val history: StateFlow<PerformanceHistory> = _history.asStateFlow()

    /** Files a completed measurement. A failed or empty one is ignored by the model itself. */
    fun record(mode: ProcessingPerformanceMode, snapshot: FramePerformanceSnapshot) {
        _history.value = _history.value.record(mode, snapshot)
    }

    /** Forgets both measurements, so the next comparison starts from nothing. */
    fun clear() {
        _history.value = PerformanceHistory.Empty
    }
}
