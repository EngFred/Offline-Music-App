package com.engfred.musicplayer.feature_dj_mix.domain.model

/**
 * Holds the user-configurable parameters for the DJ Mix feature.
 *
 * @param crossfadeDurationSec    Length of the volume crossfade transition (2–12 s).
 * @param bpmTolerance            Maximum BPM delta still considered "compatible" (±5–±20 BPM).
 * @param isRealMixMode           If true, tracks are mixed early based on either the halfway
 *                                point (default) or [maxTrackDurationSec] if [useManualMaxDuration] is true.
 * @param maxTrackDurationSec     Maximum time a track plays before an early mix is triggered
 *                                (default: 2 min 26 sec — mirrors the smart halfway offset logic).
 *                                Only active when [useManualMaxDuration] is true.
 * @param loopQueue               If true, the playlist resets and loops when exhausted.
 * @param useManualMaxDuration    If false (default), Real Mix Mode triggers at the track's
 *                                halfway point offset by firstBeatMs.
 *                                If true, [maxTrackDurationSec] is used instead.
 * @param autoSamplerEnabled      If true, the SamplerEngine fires transition sounds automatically
 *                                at crossfade lifecycle events. The algorithm decides which sample
 *                                fires and when — users only control this on/off toggle.
 * @param sampleVolume            Master volume for all sampler playback (0.0 – 1.0).
 *                                Default 0.75 keeps samples audible but clearly below the music.
 */
data class DjMixSettings(
    val crossfadeDurationSec: Int     = 5,
    val bpmTolerance: Float           = 10f,
    val isRealMixMode: Boolean        = true,
    val maxTrackDurationSec: Int      = 146,
    val loopQueue: Boolean            = true,
    val useManualMaxDuration: Boolean = false,
    val autoSamplerEnabled: Boolean   = true,
    val sampleVolume: Float           = 1.0f
)