package com.engfred.musicplayer.core.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton registry that coordinates which audio system is currently active.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 * :feature_player and :feature_dj_mix must not depend on each other directly.
 * This class lives in :core and acts as a shared signal bus so both features can
 * coordinate without a circular module dependency.
 *
 * ── Reviewers: flow of control ───────────────────────────────────────────────
 * DJ starts  → DjMixViewModel calls onDjMixStarted()
 *            → PlaybackControllerImpl observes isDjMixActive = true → pauses ExoPlayer
 *
 * Song tapped → PlaybackControllerImpl.initiatePlayback() calls requestStopDjMix()
 *            → DjMixService observes stopDjMixSignal → releases engine → stopSelf()
 */
@Singleton
class ActivePlayerRegistry @Inject constructor() {

    // ── DJ Mix active flag ────────────────────────────────────────────────────

    private val _isDjMixActive = MutableStateFlow(false)
    val isDjMixActive: StateFlow<Boolean> = _isDjMixActive.asStateFlow()

    // ── Stop signal (normal player → DJ) ─────────────────────────────────────

    private val _stopDjMixSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopDjMixSignal: SharedFlow<Unit> = _stopDjMixSignal.asSharedFlow()

    // ── Pause signal (Preview Trimmer → Normal Player) ───────────────────────

    private val _pauseNormalPlayerSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pauseNormalPlayerSignal: SharedFlow<Unit> = _pauseNormalPlayerSignal.asSharedFlow()


    // ── API ───────────────────────────────────────────────────────────────────

    fun onDjMixStarted() {
        _isDjMixActive.value = true
    }

    fun onDjMixStopped() {
        _isDjMixActive.value = false
    }

    fun requestStopDjMix() {
        if (_isDjMixActive.value) {
            _isDjMixActive.value = false
            _stopDjMixSignal.tryEmit(Unit)
        }
    }

    /**
     * Called by isolated players (like the Audio Trimmer Preview) to force the
     * background normal player to pause so they don't play over each other.
     */
    fun requestPauseNormalPlayer() {
        _pauseNormalPlayerSignal.tryEmit(Unit)
    }
}