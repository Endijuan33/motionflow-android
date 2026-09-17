package com.motionflow.player

import android.app.Application

/**
 * Process-level entry point for MotionFlow.
 *
 * The class is intentionally empty: it is registered in the manifest and exists as the documented
 * place for process-wide setup (dependency graph, logging, media session configuration) that the
 * later playback and rendering phases introduce.
 */
class MotionFlowApplication : Application()
