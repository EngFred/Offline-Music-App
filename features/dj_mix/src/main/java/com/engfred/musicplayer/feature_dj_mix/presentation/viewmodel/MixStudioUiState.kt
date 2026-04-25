package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.model.MixStudioSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo

data class MixStudioUiState(
    val playlistName: String = "",
    val totalSongs: Int = 0,
    val smartQueue: List<AudioFile> = emptyList(),
    val bpmCache: Map<Long, BpmInfo> = emptyMap(),
    val analysisProgress: Float = 0f,
    val isAnalyzing: Boolean = false,
    val currentTrack: AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val settings: MixStudioSettings = MixStudioSettings(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val timeToNextMixMs: Long? = null,
    val analysisFailedCount: Int = 0,
    val canSkipBack: Boolean = false,
    val playedTrackIds: Set<Long> = emptySet(),
    val nextTrack: AudioFile? = null,

    // ── Analysis-in-progress dialog ───────────────────────────────────────────

    /**
     * True while the "BPM analysis still in progress" confirmation dialog is visible.
     * Set to true when the user taps START MIX during an active analysis pass.
     */
    val showAnalysisDialog: Boolean = false,

    /**
     * True when the user chose "Auto-Start When Ready" from the analysis dialog.
     * The mix will start automatically the moment [analysisProgress] reaches 1.0.
     * Cleared as soon as the auto-start fires (or if the user presses START MIX again).
     */
    val pendingAutoStartAfterAnalysis: Boolean = false,

    // ── Deck layout preference ────────────────────────────────────────────────

    /**
     * When false (default) the screen shows the classic single-vinyl [NowPlayingSection].
     * When true it renders [DualDeckSection] — Deck 1 for the current track and
     * Deck 2 for the upcoming next track — connected by an animated crossfader strip.
     *
     * The preference is stored in this ViewModel's state for the lifetime of the
     * current session (it resets to false when the screen is first opened).
     * Toggled by dispatching [MixStudioEvent.ToggleDeckLayout].
     */
    val isDualDeckMode: Boolean = false,
)