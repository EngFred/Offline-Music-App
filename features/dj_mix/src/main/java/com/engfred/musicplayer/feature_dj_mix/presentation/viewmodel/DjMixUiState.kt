package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy
import com.engfred.musicplayer.feature_dj_mix.domain.model.DjMixSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo

/**
 * Immutable snapshot of everything [DjMixScreen] needs to render itself.
 *
 * @param playlistName            Name shown in the header.
 * @param totalSongs              Total number of songs in the playlist.
 * @param smartQueue              Songs ordered by the genius DJ arc algorithm for display.
 * @param bpmCache                Map of audioFileId → BPM info (bpm, firstBeatMs, amplitude).
 * @param analysisProgress        0f..1f fraction of songs whose BPM has been analysed.
 * @param isAnalyzing             True while the background worker is running.
 * @param currentTrack            Track currently audible in the crossfade engine.
 * @param isPlaying               Whether the engine is actively playing.
 * @param isCrossfading           True during an in-progress crossfade transition.
 * @param currentPositionMs       Playback position of the current track in milliseconds.
 * @param currentDurationMs       Duration of the current track in milliseconds.
 * @param crossfadeProgressFraction 0f..1f visual progress of an active crossfade.
 * @param currentMixStrategy      The [MixStrategy] chosen for the most recent crossfade.
 *                                Expose in the UI as a label ("Harmonic Drop", "Power Mix" etc.)
 *                                to help DJs and users understand what's happening.
 * @param waveform                Real-time list of normalised floats (0.0–1.0) for the visualiser.
 * @param settings                Current DJ Mix settings (crossfade duration, BPM tolerance, etc.).
 * @param isLoading               True while the playlist is being fetched from the database.
 * @param error                   Non-null when a fatal error has occurred.
 */
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
    val error: String? = null
)