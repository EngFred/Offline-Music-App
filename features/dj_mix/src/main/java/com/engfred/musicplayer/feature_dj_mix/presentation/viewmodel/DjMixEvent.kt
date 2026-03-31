package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent

    // ── Sampler settings ──────────────────────────────────────────────────────
    data class ToggleAutoSampler(val enabled: Boolean) : DjMixEvent
    data class UpdateSampleVolume(val volume: Float) : DjMixEvent
}