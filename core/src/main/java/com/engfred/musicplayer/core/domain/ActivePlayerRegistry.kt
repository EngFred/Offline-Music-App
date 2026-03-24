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
    /**
     * True while [DjMixService] is running and [CrossfadeEngine] has active players.
     * Observed by [PlaybackControllerImpl] to auto-pause the normal player.
     */
    val isDjMixActive: StateFlow<Boolean> = _isDjMixActive.asStateFlow()

    // ── Stop signal (normal player → DJ) ─────────────────────────────────────

    private val _stopDjMixSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Emits a single Unit whenever normal playback requests the DJ mix to stop.
     * [DjMixService] collects this and calls stopSelf() + releases the engine.
     */
    val stopDjMixSignal: SharedFlow<Unit> = _stopDjMixSignal.asSharedFlow()

    // ── API ───────────────────────────────────────────────────────────────────

    /** Called by [DjMixViewModel] the moment a DJ session is initiated. */
    fun onDjMixStarted() {
        _isDjMixActive.value = true
    }

    /** Called by [DjMixService.onDestroy] when the service is torn down. */
    fun onDjMixStopped() {
        _isDjMixActive.value = false
    }

    /**
     * Called by [PlaybackControllerImpl] when normal playback is about to start.
     * If the DJ mix is currently active, emits [stopDjMixSignal] so [DjMixService]
     * can shut itself down cleanly before the normal player takes audio focus.
     */
    fun requestStopDjMix() {
        if (_isDjMixActive.value) {
            _isDjMixActive.value = false
            _stopDjMixSignal.tryEmit(Unit)
        }
    }
}