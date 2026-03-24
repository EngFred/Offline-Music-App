package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent

    /**
     * Immediately triggers a crossfade to the next track in the queue,
     * bypassing the position-based monitor. The crossfade starts on the
     * next [DjMixService.observeNextTrackRequests] tick — same path as
     * automatic mixing, just fired on demand.
     */
    data object MixNow : DjMixEvent

    data class UpdateCrossfadeDuration(val seconds: Int) : DjMixEvent
    data class UpdateBpmTolerance(val tolerance: Float) : DjMixEvent
    data class ToggleRealMixMode(val enabled: Boolean) : DjMixEvent

    /**
     * Switches between "mix at halfway point" (enabled = false, default) and
     * "mix after a fixed max playtime" (enabled = true, uses [UpdateMaxTrackDuration]).
     */
    data class ToggleManualMaxDuration(val enabled: Boolean) : DjMixEvent

    data class UpdateMaxTrackDuration(val seconds: Int) : DjMixEvent
    data class ToggleLoopQueue(val enabled: Boolean) : DjMixEvent
    data class JumpToTrack(val audioFile: AudioFile) : DjMixEvent
}