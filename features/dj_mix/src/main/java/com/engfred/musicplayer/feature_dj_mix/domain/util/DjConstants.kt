package com.engfred.musicplayer.feature_dj_mix.domain.util

object DjConstants {
    /**
     * Musically valid BPM speed relationships used by professional DJs.
     * Shared across the track-selection UseCase and the CrossfadeEngine
     * to ensure both systems agree on what constitutes a "perfect match".
     */
    val HARMONIC_RATIOS = floatArrayOf(
        0.5f,   // half-time
        0.667f, // 2:3 polyrhythm
        0.75f,  // 3:4 polyrhythm
        1.0f,   // exact match
        1.333f, // 4:3
        1.5f,   // 3:2
        2.0f    // double-time
    )
}