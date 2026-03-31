package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent
    data object MixNow : DjMixEvent
    data object AbortCrossfade : DjMixEvent

    data class ToggleRealMixMode(val enabled: Boolean) : DjMixEvent

    // ── Sampler settings ──────────────────────────────────────────────────────
    data class ToggleAutoSampler(val enabled: Boolean) : DjMixEvent
    data class UpdateSampleVolume(val volume: Float) : DjMixEvent
}