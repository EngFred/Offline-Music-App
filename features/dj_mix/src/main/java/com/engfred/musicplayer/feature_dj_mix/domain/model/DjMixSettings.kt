package com.engfred.musicplayer.feature_dj_mix.domain.model

/**
 * Holds the user-configurable parameters for the DJ Mix feature.
 *
 * These are intentionally separate from [AppSettings] while the DJ Mix feature
 * is in development. Step 12 wires them into [AppSettings] + DataStore so they
 * persist across sessions.
 *
 * @param crossfadeDurationSec  Length of the volume crossfade transition (2–12 s).
 * @param bpmTolerance          Maximum BPM delta still considered "compatible" (±5–±20 BPM).
 * @param isRealMixMode         If true, tracks are mixed early based on [maxTrackDurationSec].
 * @param maxTrackDurationSec   Maximum time a track plays before an early mix is triggered.
 * @param loopQueue             If true, the playlist resets and loops when exhausted. If false, stops on last track.
 */
data class DjMixSettings(
    val crossfadeDurationSec: Int = 5,
    val bpmTolerance: Float = 10f,
    val isRealMixMode: Boolean = false,
    val maxTrackDurationSec: Int = 120,
    val loopQueue: Boolean = true
)