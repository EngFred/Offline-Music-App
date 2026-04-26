package com.engfred.musicplayer.feature_dj_mix.domain.util

object DjConstants {
    /**
     * Musically valid BPM speed relationships used by professional DJs.
     * Restricted to half-time, exact match, and double-time to ensure
     * a smooth, linear energy arc without drastic polyrhythmic tempo jumps.
     */
    val HARMONIC_RATIOS = floatArrayOf(
        0.5f,   // half-time
        1.0f,   // exact match
        2.0f    // double-time
    )
}