package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

sealed interface MixStudioEvent {
    data object PlayPause : MixStudioEvent
    data object MixStudioNow : MixStudioEvent
    data object AbortCrossfade : MixStudioEvent

    data class ToggleRealMixStudioMode(val enabled: Boolean) : MixStudioEvent

    // ── Sampler settings ──────────────────────────────────────────────────────
    data class ToggleAutoSampler(val enabled: Boolean) : MixStudioEvent
    data class UpdateSampleVolume(val volume: Float) : MixStudioEvent

    // ── Cue point setting ─────────────────────────────────────────────────────

    /**
     * Updates the cue-point offset — the minimum position at which the incoming
     * track's first audible beat will be placed during a crossfade.
     *
     * [sec] must be one of [CUE_POINT_OPTIONS_SEC] (0, 5, 10, 15, 20, 25, 30).
     * The UI is responsible for restricting selection to this list.
     *
     * What happens on dispatch:
     *   1. [CrossfadeEngine.cuePointOffsetMs] is updated immediately (no re-analysis).
     *   2. The current track's beat grid is re-synced so the ongoing mix trigger
     *      recalculates with the new offset.
     *   3. The setting is persisted to DataStore via [SettingsRepository].
     *
     * Mix-timing effect:
     *   The halfway-mix trigger = (duration/2) + guardedFirstBeatMs.
     *   Because guardedFirstBeatMs ≈ sec × 1000 ms, a higher cue point delays
     *   the trigger by ~sec seconds, compensating for the incoming track's
     *   unheard intro period. See [CrossfadeEngine.applyFirstBeatGuard].
     */
    data class UpdateCuePointOffset(val sec: Int) : MixStudioEvent

    // Add alongside existing events:
//    data class UpdateCrossfadeDuration(val sec: Int) : MixStudioEvent

    // ── Analysis-in-progress dialog ───────────────────────────────────────────

    /**
     * User tapped outside the dialog or pressed back — dismiss without taking
     * action. No auto-start is scheduled; the user must tap START MIX again.
     */
    data object DismissAnalysisDialog : MixStudioEvent

    /**
     * User chose to wait for BPM analysis to finish.
     * Closes the dialog and schedules an automatic mix start the moment
     * analysis reaches 100% for this playlist.
     */
    data object WaitAndAutoStart : MixStudioEvent

    /**
     * User wants to start immediately despite ongoing analysis.
     * Unanalysed tracks fall back to natural playlist order in the queue.
     */
    data object StartAnywayDespiteAnalysis : MixStudioEvent

    // ── Deck layout ───────────────────────────────────────────────────────────

    /**
     * Toggles between single-deck (classic vinyl + NowPlayingSection) and
     * dual-deck (Deck 1 / Deck 2 split with animated crossfader) layouts.
     *
     * The preference lives in [MixStudioUiState.isDualDeckMode] for the duration
     * of the current session and resets to false when the screen is re-created.
     * It is deliberately NOT persisted to DataStore — the default single-deck
     * view is the recommended starting point for new users; power users who want
     * dual deck can enable it per session without it being "sticky".
     */
    data object ToggleDeckLayout : MixStudioEvent
}