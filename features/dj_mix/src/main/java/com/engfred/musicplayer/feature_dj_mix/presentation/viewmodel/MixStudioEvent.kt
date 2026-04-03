package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

sealed interface MixStudioEvent {
    data object PlayPause : MixStudioEvent
    data object MixStudioNow : MixStudioEvent
    data object AbortCrossfade : MixStudioEvent

    data class ToggleRealMixStudioMode(val enabled: Boolean) : MixStudioEvent

    // ── Sampler settings ──────────────────────────────────────────────────────
    data class ToggleAutoSampler(val enabled: Boolean) : MixStudioEvent
    data class UpdateSampleVolume(val volume: Float) : MixStudioEvent

    // ── Analysis-in-progress dialog ───────────────────────────────────────────

    /**
     * User tapped outside the dialog or pressed back — dismiss without taking action.
     * No auto-start is scheduled; the user must tap START MIX again manually.
     */
    data object DismissAnalysisDialog : MixStudioEvent

    /**
     * User chose to wait for BPM analysis to finish.
     * Closes the dialog and schedules an automatic mix start the moment
     * analysis reaches 100 % for this playlist.
     */
    data object WaitAndAutoStart : MixStudioEvent

    /**
     * User wants to start immediately despite ongoing analysis.
     * Unanalysed tracks will fall back to natural playlist order in the queue.
     */
    data object StartAnywayDespiteAnalysis : MixStudioEvent
}