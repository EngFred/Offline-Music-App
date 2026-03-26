package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent
    data object MixNow : DjMixEvent
    data object SkipBack : DjMixEvent

    // ── Queue ordering ────────────────────────────────────────────────────────
    // All three are no-ops while isAnalyzing == true (gated in ViewModel).
    data object ShuffleQueue : DjMixEvent
    data object SortByBpm : DjMixEvent
    data class MoveTrack(val fromIndex: Int, val toIndex: Int) : DjMixEvent

    // ── Remove a track from the current session queue (session-only, not
    // permanent — the underlying playlist is untouched).
    data class RemoveTrack(val audioFile: AudioFile) : DjMixEvent

    data object AbortCrossfade : DjMixEvent

    data class UpdateCrossfadeDuration(val seconds: Int) : DjMixEvent
    data class UpdateBpmTolerance(val tolerance: Float) : DjMixEvent
    data class ToggleRealMixMode(val enabled: Boolean) : DjMixEvent
    data class ToggleManualMaxDuration(val enabled: Boolean) : DjMixEvent
    data class UpdateMaxTrackDuration(val seconds: Int) : DjMixEvent
    data class ToggleLoopQueue(val enabled: Boolean) : DjMixEvent
    data class JumpToTrack(val audioFile: AudioFile) : DjMixEvent
}