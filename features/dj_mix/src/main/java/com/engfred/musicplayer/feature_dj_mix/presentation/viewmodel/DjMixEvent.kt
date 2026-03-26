package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile

sealed interface DjMixEvent {
    data object PlayPause : DjMixEvent
    data object MixNow : DjMixEvent
    data object SkipBack : DjMixEvent           // ── NEW (Feature 3) ──

    // ── NEW (Feature 2) ──────────────────────────────────────────────────────
    // Overrides the energy arc order. Once any of these fires, isQueueUserOrdered
    // is set to true and rebuildSmartQueue() becomes a no-op until the session ends.
    data object ShuffleQueue : DjMixEvent
    data object SortByBpm : DjMixEvent
    data class MoveTrack(val fromIndex: Int, val toIndex: Int) : DjMixEvent
    // ─────────────────────────────────────────────────────────────────────────

    data object AbortCrossfade : DjMixEvent

    data class UpdateCrossfadeDuration(val seconds: Int) : DjMixEvent
    data class UpdateBpmTolerance(val tolerance: Float) : DjMixEvent
    data class ToggleRealMixMode(val enabled: Boolean) : DjMixEvent
    data class ToggleManualMaxDuration(val enabled: Boolean) : DjMixEvent
    data class UpdateMaxTrackDuration(val seconds: Int) : DjMixEvent
    data class ToggleLoopQueue(val enabled: Boolean) : DjMixEvent
    data class JumpToTrack(val audioFile: AudioFile) : DjMixEvent
}