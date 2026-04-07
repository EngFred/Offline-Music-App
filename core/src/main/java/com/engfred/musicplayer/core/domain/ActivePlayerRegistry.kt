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
 * This class lives in :core and acts as a shared signal bus.
 *
 * ── Race condition that was fixed ────────────────────────────────────────────
 * BEFORE: requestStopDjMix() immediately set isDjMixActive = false, which caused
 * PlaybackControllerImpl to stop suppressing normal-player playback. But
 * AutoMixService had not yet released the CrossfadeEngine or relinquished audio
 * focus. There was a window — measured in 1-2 frames — where both players held
 * audio focus simultaneously.
 *
 * AFTER: requestStopDjMix() only emits the stop signal. isDjMixActive is NOT
 * cleared here. AutoMixService calls acknowledgeDjMixStopped() AFTER the engine
 * is released and stopSelf() is called. Only then does isDjMixActive → false,
 * and only then does the normal player resume un-suppressed.
 *
 * ── Reviewers: updated flow of control ───────────────────────────────────────
 * DJ starts   → DjMixViewModel calls onDjMixStarted()
 *             → PlaybackControllerImpl sees isDjMixActive = true → pauses ExoPlayer
 *
 * Song tapped → PlaybackControllerImpl.initiatePlayback() calls requestStopDjMix()
 *             → AutoMixService.observeStopSignal() fires → releaseAndStop() runs
 *             → releaseAndStop() calls acknowledgeDjMixStopped() as its LAST step
 *             → isDjMixActive → false → PlaybackControllerImpl stops suppressing
 *
 * Trimmer preview → requestPauseNormalPlayer() pauses ExoPlayer transiently.
 */
@Singleton
class ActivePlayerRegistry @Inject constructor() {

    // ── DJ Mix active flag ────────────────────────────────────────────────────
    // Only modified by onDjMixStarted() and acknowledgeDjMixStopped() — never
    // by requestStopDjMix(). This ensures the flag reflects actual engine state,
    // not just the intent to stop.

    private val _isDjMixActive = MutableStateFlow(false)
    val isDjMixActive: StateFlow<Boolean> = _isDjMixActive.asStateFlow()

    // ── Stop signal (normal player → DJ) ─────────────────────────────────────
    // Fire-and-forget request to AutoMixService to begin shutdown. The caller
    // must NOT assume the DJ is stopped until isDjMixActive becomes false.

    private val _stopDjMixSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopDjMixSignal: SharedFlow<Unit> = _stopDjMixSignal.asSharedFlow()

    // ── Pause signal (Trimmer → Normal Player) ────────────────────────────────

    private val _pauseNormalPlayerSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pauseNormalPlayerSignal: SharedFlow<Unit> = _pauseNormalPlayerSignal.asSharedFlow()

    // ── API ───────────────────────────────────────────────────────────────────

    /** Called by DjMixViewModel when the DJ session starts. */
    fun onDjMixStarted() {
        _isDjMixActive.value = true
    }

    /**
     * Called by AutoMixService.releaseAndStop() AFTER the engine has been
     * released and stopSelf() has been issued.
     *
     * This is the only place isDjMixActive is set to false (other than
     * onDestroy guard in AutoMixService). Keeping it here — instead of inside
     * requestStopDjMix() — closes the race window where both players could
     * briefly hold audio focus simultaneously.
     */
    fun acknowledgeDjMixStopped() {
        _isDjMixActive.value = false
    }

    /**
     * Called by PlaybackControllerImpl when normal playback starts.
     * Emits a signal that AutoMixService observes and responds to by calling
     * releaseAndStop() → acknowledgeDjMixStopped().
     *
     * Does NOT touch isDjMixActive directly — see acknowledgeDjMixStopped().
     */
    fun requestStopDjMix() {
        if (_isDjMixActive.value) {
            // Emit the signal. AutoMixService will call acknowledgeDjMixStopped()
            // once it has fully released the engine.
            _stopDjMixSignal.tryEmit(Unit)
        }
    }

    /**
     * Called by isolated players (e.g. Audio Trimmer Preview) to pause the
     * background normal player so they don't play over each other.
     */
    fun requestPauseNormalPlayer() {
        _pauseNormalPlayerSignal.tryEmit(Unit)
    }
}