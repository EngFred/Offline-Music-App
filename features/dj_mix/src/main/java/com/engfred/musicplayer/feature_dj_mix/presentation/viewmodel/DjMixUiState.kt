package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.model.DjMixSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo

/**
 * Immutable snapshot of everything [DjMixScreen] needs to render itself.
 *
 * @param playlistName Name shown in the header.
 * @param totalSongs Total number of songs in the playlist.
 * @param smartQueue Songs ordered by BPM proximity for display.
 * @param bpmCache Map of audioFileId → BPM info (now includes firstBeatMs).
 * @param analysisProgress 0f..1f fraction of songs whose BPM has been analysed.
 * @param isAnalyzing True while the background worker is running.
 * @param currentTrack Track currently audible in the crossfade engine.
 * @param isPlaying Whether the engine is actively playing.
 * @param isCrossfading True during an in-progress crossfade transition.
 * @param currentPositionMs Playback position of the current track.
 * @param currentDurationMs Duration of the current track.
 * @param crossfadeProgressFraction 0f..1f visual progress of an active crossfade.
 * @param waveform A real-time list of normalized floats (0.0 to 1.0) representing the current audio amplitude.
 * @param settings Current DJ Mix settings (crossfade duration, BPM tolerance).
 * @param isLoading True while the playlist is being fetched from the DB.
 * @param error Non-null when a fatal error has occurred.
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
    val waveform: List<Float> = emptyList(),
    val settings: DjMixSettings = DjMixSettings(),
    val isLoading: Boolean = true,
    val error: String? = null
)