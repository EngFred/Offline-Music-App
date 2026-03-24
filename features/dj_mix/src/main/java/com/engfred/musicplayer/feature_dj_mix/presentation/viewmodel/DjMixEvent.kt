package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent
    data class UpdateCrossfadeDuration(val seconds: Int) : DjMixEvent
    data class UpdateBpmTolerance(val tolerance: Float) : DjMixEvent
    /** User taps a track in the smart queue to jump to it directly. */
    data class JumpToTrack(val audioFile: AudioFile) : DjMixEvent
}