package com.engfred.musicplayer.core.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton registry that coordinates audio system signals across modules.
 */
@Singleton
class ActivePlayerRegistry @Inject constructor() {

    // ── Pause signal (Trimmer → Normal Player) ────────────────────────────────

    private val _pauseNormalPlayerSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pauseNormalPlayerSignal: SharedFlow<Unit> = _pauseNormalPlayerSignal.asSharedFlow()

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Called by isolated players (e.g. Audio Trimmer Preview) to pause the
     * background normal player so they don't play over each other.
     */
    fun requestPauseNormalPlayer() {
        _pauseNormalPlayerSignal.tryEmit(Unit)
    }
}