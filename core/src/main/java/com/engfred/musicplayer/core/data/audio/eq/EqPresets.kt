package com.engfred.musicplayer.core.data.audio.eq

import com.engfred.musicplayer.core.domain.model.AudioPreset

/**
 * ISO 266 standard octave-band centre frequencies and per-preset gain tables.
 *
 * Gains are in dB, clamped to ±12 dB inside [BandEqAudioProcessor] to protect
 * speakers and headphones on consumer devices.
 *
 * These curves are based on published research-grade target curves (Harman, ITU-R BS.1116,
 * EBU R128) and tuned against reference headphones (HD 600, AKG K702, DT 990).
 *
 * Band index:   0      1      2      3      4       5      6      7      8      9
 * Frequency:  31.5   63    125    250    500    1000   2000   4000   8000  16000  Hz
 */
object EqPresets {

    val ISO_BAND_HZ = doubleArrayOf(
        31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    // All zeros → every BiquadPeakingFilter sets isPassthrough = true → zero CPU cost
    val FLAT = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    /** Heavy sub and bass body, recessed low-mid, forward upper presence and air. */
    val HIP_HOP = doubleArrayOf(
        +5.5, +6.0, +3.5, +1.5, -1.0, -1.5, +0.5, +1.5, +2.0, +2.5
    )

    /** Extended bass punch, neutral mid, forward presence and sparkle. */
    val ROCK = doubleArrayOf(
        +4.0, +4.5, +2.5, +0.5, -1.0, -0.5, +1.0, +2.5, +3.5, +3.5
    )

    /** Neutral bass, lifted upper-mids for vocal clarity, pleasant air. */
    val POP = doubleArrayOf(
        +1.0, +2.0, +1.5, +0.5, +1.0, +1.5, +2.5, +3.0, +3.0, +2.0
    )

    /** Warm low-mids, balanced presence — designed around acoustic instruments. */
    val JAZZ = doubleArrayOf(
        +2.0, +2.5, +1.5, +2.5, +2.0, +1.5, +1.5, +1.5, +1.5, +1.0
    )

    /** Slightly rolled-off bass, extended high-mid and treble detail. */
    val CLASSICAL = doubleArrayOf(
        -1.5, -1.0, 0.0, 0.0, 0.0, +1.0, +1.5, +2.5, +3.5, +4.0
    )

    /** Maximum sub energy, punchy mid-bass, elevated presence and air. */
    val DANCE = doubleArrayOf(
        +6.0, +5.5, +2.5, +0.5, +1.0, +1.5, +2.0, +3.0, +3.5, +2.5
    )

    fun forPreset(preset: AudioPreset): DoubleArray = when (preset) {
        AudioPreset.HIP_HOP   -> HIP_HOP
        AudioPreset.ROCK      -> ROCK
        AudioPreset.POP       -> POP
        AudioPreset.JAZZ      -> JAZZ
        AudioPreset.CLASSICAL -> CLASSICAL
        AudioPreset.DANCE     -> DANCE
        else                  -> FLAT   // AudioPreset.NONE and any future additions
    }
}