package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import com.engfred.musicplayer.core.domain.model.AudioFile

data class CrossfadeEngineState(
    val currentTrack: AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val error: String? = null,
    val timeToNextMixMs: Long? = null,
    val audioFeatures: WaveformCaptureAudioProcessor.AudioFeatures = WaveformCaptureAudioProcessor.AudioFeatures(
        rms = 0.5f,
        trend = 0f,
        isLowEnergy = false,
        isDropping = false
    )
)