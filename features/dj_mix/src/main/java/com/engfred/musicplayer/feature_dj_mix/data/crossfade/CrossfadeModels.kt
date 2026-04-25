package com.engfred.musicplayer.feature_dj_mix.data.crossfade

data class CrossfadeEngineState(
    val currentTrack: com.engfred.musicplayer.core.domain.model.AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val error: String? = null,
    val timeToNextMixMs: Long? = null
)