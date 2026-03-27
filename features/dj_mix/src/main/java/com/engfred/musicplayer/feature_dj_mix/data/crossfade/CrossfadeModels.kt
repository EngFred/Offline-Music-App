package com.engfred.musicplayer.feature_dj_mix.data.crossfade

data class CrossfadeEngineState(
    val currentTrack: com.engfred.musicplayer.core.domain.model.AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val currentMixStrategy: MixStrategy = MixStrategy.SMOOTH,
    val error: String? = null,
    val timeToNextMixMs: Long? = null
)

/**
 * Classifies the BPM relationship between outgoing and incoming tracks.
 * Each strategy drives different crossfade duration, tempo-sync behaviour, and EQ treatment.
 */
enum class MixStrategy {
    /** ≤3 BPM delta — transparent straight crossfade, no tempo adjustment. */
    TRANSPARENT,
    /** 3–8 BPM delta — tempo-sync + equal-power fade + ease-back. */
    SMOOTH,
    /** 8–15 BPM delta — early bass kill, extended fade, moderate tempo-sync. */
    POWER_MIX,
    /** Harmonic ratio (half-time, double-time, 3:2, 4:3) — short clean fade, no tempo-sync. */
    HARMONIC,
    /** >15 BPM delta — energy-valley technique, no tempo-sync, aggressive bass kill. */
    WIDE_TRANSITION
}

/**
 * Full decision record for one crossfade.
 * Computed by [MixDecisionEngine.computeMixDecision]; drives every parameter
 * in [CrossfadeEngine.executeCrossfade].
 *
 * @param stretchRatio RubberBand time-stretch ratio = incomingBpm/outgoingBpm (1.0 = no stretch).
 *                     < 1.0 speeds up incoming track; > 1.0 slows it down.
 */
data class MixDecision(
    val outgoingBpm: Float,
    val incomingBpm: Float,
    val rawBpmDelta: Float,
    val effectiveBpmDelta: Float,
    val strategy: MixStrategy,
    val isHarmonic: Boolean,
    val effectiveCrossfadeDurationMs: Long,
    val shouldTempoSync: Boolean,
    val stretchRatio: Double,
    val bassKillThresholdFraction: Float,
    val djNote: String
)