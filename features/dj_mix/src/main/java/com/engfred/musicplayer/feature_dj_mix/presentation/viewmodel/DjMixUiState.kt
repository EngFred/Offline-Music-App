package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy
import com.engfred.musicplayer.feature_dj_mix.domain.model.DjMixSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo

data class DjMixUiState(
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
    val currentMixStrategy: MixStrategy = MixStrategy.SMOOTH,
    val waveform: List<Float> = emptyList(),
    val settings: DjMixSettings = DjMixSettings(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val timeToNextMixMs: Long? = null,

    // ── Feature 1: Failed BPM visibility ─────────────────────────────────────
    val analysisFailedCount: Int = 0,

    // ── Feature 2: Manual queue ordering ─────────────────────────────────────
    val isQueueUserOrdered: Boolean = false,

    // ── Feature 3: Skip back ──────────────────────────────────────────────────
    val canSkipBack: Boolean = false,

    // ── Track History Synchronization ────────────────────────────────────────
    val playedTrackIds: Set<Long> = emptySet(),

    // ── Next track preview ────────────────────────────────────────────────────
    val nextTrack: AudioFile? = null
)