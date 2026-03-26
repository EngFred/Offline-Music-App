package com.engfred.musicplayer.feature_player.data.service.eq

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Direct Form II Transposed biquad peaking EQ filter.
 *
 * Implements the "Peaking EQ" section of the Audio EQ Cookbook (R. Bristow-Johnson).
 * This is the same algorithm used in professional DAWs (Pro Tools, Logic, Ableton).
 *
 * Direct Form II Transposed is chosen over Direct Form I because it has
 * lower coefficient sensitivity and is numerically more stable at the
 * extreme frequencies (sub-bass, air bands) that matter most for music.
 */
internal class BiquadPeakingFilter {

    // Normalised coefficients (a0 divided out)
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Delay elements (filter memory) — must be reset on seek/flush
    private var w1 = 0.0
    private var w2 = 0.0

    /** True when gain = 0 dB — sample passes through unmodified, zero arithmetic cost. */
    var isPassthrough = true
        private set

    /**
     * Compute biquad coefficients for a peaking EQ filter.
     *
     * @param fc     Centre frequency in Hz
     * @param gainDb Boost (+) or cut (-) in dB. 0.0 = passthrough.
     * @param q      Quality factor controlling bandwidth.
     *               Q = 1.41 gives ~0.7 octave (good for 10-band EQ with ISO spacing).
     * @param fs     Sample rate in Hz (must match the audio stream)
     */
    fun configure(fc: Double, gainDb: Double, q: Double, fs: Double) {
        if (gainDb == 0.0 || fc <= 0.0 || fc >= fs / 2.0) {
            isPassthrough = true
            return
        }
        isPassthrough = false

        // A = linear amplitude ratio (gain in dB maps to ±half at amplitude level)
        val A = 10.0.pow(gainDb / 40.0)
        val w0    = 2.0 * Math.PI * fc / fs
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * q)

        // Normalise by a0 immediately to avoid division in process()
        val a0Inv = 1.0 / (1.0 + alpha / A)
        b0 =  (1.0 + alpha * A) * a0Inv
        b1 =  (-2.0 * cosW0)   * a0Inv
        b2 =  (1.0 - alpha * A) * a0Inv
        a1 =  (-2.0 * cosW0)   * a0Inv
        a2 =  (1.0 - alpha / A) * a0Inv
    }

    /** Reset filter memory. Call after every seek or flush to prevent transient pops. */
    fun reset() {
        w1 = 0.0
        w2 = 0.0
    }

    /** Process one sample. Returns the sample unchanged when [isPassthrough] is true. */
    fun process(x: Float): Float {
        if (isPassthrough) return x
        val xd = x.toDouble()
        val y  = b0 * xd + w1
        w1     = b1 * xd - a1 * y + w2
        w2     = b2 * xd - a2 * y
        return y.toFloat()
    }
}