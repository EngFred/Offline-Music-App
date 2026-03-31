package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent
    data object MixNow : DjMixEvent
    data object SkipBack : DjMixEvent
    data object AbortCrossfade : DjMixEvent

    // ── Track selection ───────────────────────────────────────────────────────
    data class JumpToTrack(val audioFile: AudioFile) : DjMixEvent
//    data class RemoveTrack(val audioFile: AudioFile) : DjMixEvent

    // ── Settings ──────────────────────────────────────────────────────────────
    data class UpdateCrossfadeDuration(val seconds: Int) : DjMixEvent
    data class UpdateBpmTolerance(val tolerance: Float) : DjMixEvent
    data class ToggleRealMixMode(val enabled: Boolean) : DjMixEvent
    data class ToggleManualMaxDuration(val enabled: Boolean) : DjMixEvent
    data class UpdateMaxTrackDuration(val seconds: Int) : DjMixEvent
    data class ToggleLoopQueue(val enabled: Boolean) : DjMixEvent

    // ── Sampler settings ──────────────────────────────────────────────────────
    data class ToggleAutoSampler(val enabled: Boolean) : DjMixEvent
    data class UpdateSampleVolume(val volume: Float) : DjMixEvent
}