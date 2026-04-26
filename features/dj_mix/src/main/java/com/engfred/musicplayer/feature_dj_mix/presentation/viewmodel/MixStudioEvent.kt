package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

sealed interface MixStudioEvent {
    object PlayPause : MixStudioEvent
    object MixStudioNow : MixStudioEvent
    object DismissAnalysisDialog : MixStudioEvent
    object WaitAndAutoStart : MixStudioEvent
    object StartAnywayDespiteAnalysis : MixStudioEvent
    object ToggleDeckLayout : MixStudioEvent
    data class ToggleRealMixStudioMode(val enabled: Boolean) : MixStudioEvent
    data class ToggleAutoSampler(val enabled: Boolean) : MixStudioEvent
    data class UpdateSampleVolume(val volume: Float) : MixStudioEvent
}