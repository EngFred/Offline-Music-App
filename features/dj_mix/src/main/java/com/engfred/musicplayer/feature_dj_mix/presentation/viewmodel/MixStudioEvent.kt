package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface MixStudioEvent {
    object PlayPause : MixStudioEvent
    object MixStudioNow : MixStudioEvent
    object SkipBack : MixStudioEvent
    object DismissAnalysisDialog : MixStudioEvent
    object WaitAndAutoStart : MixStudioEvent
    object StartAnywayDespiteAnalysis : MixStudioEvent
    object ToggleDeckLayout : MixStudioEvent
    data class ToggleRealMixStudioMode(val enabled: Boolean) : MixStudioEvent
    data class ToggleLoopQueue(val enabled: Boolean) : MixStudioEvent
    data class ToggleAutoSampler(val enabled: Boolean) : MixStudioEvent
    data class UpdateSampleVolume(val volume: Float) : MixStudioEvent

    // Queue control events
    data class RequestJumpToTrack(val track: AudioFile) : MixStudioEvent
    object ConfirmJumpToTrack : MixStudioEvent
    object DismissJumpDialog : MixStudioEvent

    data class RemoveFromQueue(val track: AudioFile) : MixStudioEvent
}