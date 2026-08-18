package com.engfred.musicplayer.core.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ActiveMediaType {
    AUDIO,
    VIDEO,
    NONE
}

/**
 * Singleton registry that coordinates playback and media signals across modules.
 */
@Singleton
class ActivePlayerRegistry @Inject constructor() {

    private val _activeMediaType = MutableStateFlow(ActiveMediaType.NONE)
    val activeMediaType: StateFlow<ActiveMediaType> = _activeMediaType.asStateFlow()

    private val _pauseNormalPlayerSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pauseNormalPlayerSignal: SharedFlow<Unit> = _pauseNormalPlayerSignal.asSharedFlow()

    fun setActiveMediaType(type: ActiveMediaType) {
        _activeMediaType.value = type
    }

    /**
     * Called by isolated players (e.g. Video Player / Audio Trimmer Preview) to pause the
     * background normal music player so they don't play over each other.
     */
    fun requestPauseNormalPlayer() {
        _pauseNormalPlayerSignal.tryEmit(Unit)
    }
}