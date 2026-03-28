package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent
    data object MixNow : DjMixEvent
    data object SkipBack : DjMixEvent

    // ── Custom Cue Point UI Actions ───────────────────────────────────────────
    data object SetCustomCueIn : DjMixEvent
    data object SetCustomMixOut : DjMixEvent
    data object ClearCustomCues : DjMixEvent

    // ── Queue ordering ────────────────────────────────────────────────────────
    data object ShuffleQueue : DjMixEvent
    data object SortByBpm : DjMixEvent
    data class MoveTrack(val fromIndex: Int, val toIndex: Int) : DjMixEvent
    data class RemoveTrack(val audioFile: AudioFile) : DjMixEvent

    data object AbortCrossfade : DjMixEvent

    // ── Crossfade / BPM settings ──────────────────────────────────────────────
    data class UpdateCrossfadeDuration(val seconds: Int) : DjMixEvent
    data class UpdateBpmTolerance(val tolerance: Float) : DjMixEvent
    data class ToggleRealMixMode(val enabled: Boolean) : DjMixEvent
    data class ToggleManualMaxDuration(val enabled: Boolean) : DjMixEvent
    data class UpdateMaxTrackDuration(val seconds: Int) : DjMixEvent
    data class ToggleLoopQueue(val enabled: Boolean) : DjMixEvent
    data class JumpToTrack(val audioFile: AudioFile) : DjMixEvent

    // ── Sampler settings (algorithm-driven — no manual pad control) ───────────
    /** Turns the automatic sample engine on or off globally. */
    data class ToggleAutoSampler(val enabled: Boolean) : DjMixEvent
    /** Sets the master volume for all sample playback (0.0 – 1.0). */
    data class UpdateSampleVolume(val volume: Float) : DjMixEvent
}