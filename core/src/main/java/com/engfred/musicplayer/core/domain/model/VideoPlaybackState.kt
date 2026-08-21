package com.engfred.musicplayer.core.domain.model

/**
 * Represents the current playback state for a video session.
 */
data class VideoPlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEnded: Boolean = false,
    val availableSubtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleTrackId: String? = null,
    val currentSubtitleText: String = ""
)
