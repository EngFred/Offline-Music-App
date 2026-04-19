package com.engfred.musicplayer.core.data.audio.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioPreset
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-grade 10-band parametric EQ as an ExoPlayer [AudioProcessor].
 *
 * ── Why this beats android.media.audiofx.Equalizer ───────────────────────────
 *
 *  • Device-independent: processes raw PCM before it reaches audio hardware,
 *    so OEM EQ overrides (Samsung Adapt Sound, LG HD Audio, etc.) don't interfere.
 *  • Session-safe: no audio session attach/detach — the processor lives in the
 *    ExoPlayer render pipeline for the lifetime of the service.
 *  • Format-aware: handles both PCM_16BIT and PCM_FLOAT without external config.
 *  • Zero-cost passthrough: when all band gains are 0 dB, each BiquadPeakingFilter
 *    sets isPassthrough = true and returns the sample unmodified — no floating point
 *    arithmetic, no memory bandwidth overhead.
 *  • Seek-safe: onFlush() resets filter delay elements to prevent clicks/pops
 *    after scrubbing.
 *
 * ── Thread model ─────────────────────────────────────────────────────────────
 *
 *  • onConfigure / queueInput / onFlush run on ExoPlayer's audio thread.
 *  • setPreset / setGains are called from the main (settings observer) thread.
 *  • @Volatile on [pendingGains] + [gainsChanged] provides the required JMM
 *    happens-before guarantee without locks or coroutines.
 *
 * ── Wiring ───────────────────────────────────────────────────────────────────
 *
 *  Inject this singleton into PlaybackService and pass it to ExoPlayer via
 *  DefaultRenderersFactory.buildAudioSink → DefaultAudioSink.setAudioProcessors.
 */
@UnstableApi
class BandEqAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    companion object {
        const val NUM_BANDS = 10

        /**
         * Q = 1.41 gives ~0.7 octave bandwidth per band.
         * At ISO 10-band spacing (1-octave steps), adjacent bands overlap at −3 dB —
         * the standard for graphic equalizers (IEC 61938).
         */
        private const val Q = 1.41

        private const val MAX_GAIN_DB =  12.0
        private const val MIN_GAIN_DB = -12.0
    }

    // ── Public API (main thread) ──────────────────────────────────────────────

    /** Pending gains written atomically from the main thread. */
    @Volatile private var pendingGains: DoubleArray = DoubleArray(NUM_BANDS)

    /** Set by main thread, read by audio thread at the start of each queueInput call. */
    @Volatile private var gainsChanged = false

    fun setPreset(preset: AudioPreset) {
        setGains(EqPresets.forPreset(preset))
    }

    /**
     * Update all band gains.
     * Gains outside [MIN_GAIN_DB.MAX_GAIN_DB] are clamped to protect hardware.
     */
    fun setGains(gainDb: DoubleArray) {
        require(gainDb.size == NUM_BANDS) { "Expected $NUM_BANDS gains, got ${gainDb.size}" }
        // Assign new array atomically (reference assignment is atomic on JVM)
        pendingGains = DoubleArray(NUM_BANDS) { gainDb[it].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
        gainsChanged = true
    }

    // ── Filter bank (audio thread) ────────────────────────────────────────────

    /** filters[channel][band] */
    private var filters: Array<Array<BiquadPeakingFilter>> = emptyArray()

    private fun buildFilters(sampleRate: Int, channelCount: Int, gains: DoubleArray) {
        filters = Array(channelCount) {
            Array(NUM_BANDS) { band ->
                BiquadPeakingFilter().apply {
                    configure(
                        fc     = EqPresets.ISO_BAND_HZ[band],
                        gainDb = gains[band],
                        q      = Q,
                        fs     = sampleRate.toDouble()
                    )
                }
            }
        }
    }

    /**
     * Check for pending gain updates and rebuild filters if needed.
     * Called at the top of every queueInput — audio thread only.
     */
    @OptIn(UnstableApi::class)
    private fun applyPendingGainsIfNeeded() {
        if (!gainsChanged) return
        gainsChanged = false                       // clear before reading gains (JMM ordering)
        val gains = pendingGains                   // single volatile read
        val fmt   = inputAudioFormat
        if (fmt.sampleRate > 0 && fmt.channelCount > 0) {
            buildFilters(fmt.sampleRate, fmt.channelCount, gains)
            // Reset delay elements to silence any transient from the coefficient change
            filters.forEach { ch -> ch.forEach { it.reset() } }
        }
    }

    // ── BaseAudioProcessor overrides ──────────────────────────────────────────

    /**
     * Always active — we manage passthrough internally via [BiquadPeakingFilter.isPassthrough].
     * This avoids the need for ExoPlayer to reconfigure the pipeline when the user
     * toggles the EQ on/off, which would cause a brief audio gap.
     */
    override fun isActive(): Boolean = super.isActive()

    @OptIn(UnstableApi::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                buildFilters(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, pendingGains)
                inputAudioFormat   // EQ is a gain-only effect; format is unchanged
            }
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
    }

    /** Reset delay elements on seek to prevent clicks. */
    override fun onFlush() {
        filters.forEach { ch -> ch.forEach { it.reset() } }
    }

    override fun onReset() {
        filters = emptyArray()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        applyPendingGainsIfNeeded()

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, output)
        }
        output.flip()
    }

    // ── PCM processing ────────────────────────────────────────────────────────

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        val chCount = inputAudioFormat.channelCount
        while (input.hasRemaining()) {
            for (ch in 0 until chCount) {
                if (!input.hasRemaining()) break
                output.putFloat(applyFilterChain(ch, input.float).coerceIn(-1f, 1f))
            }
        }
    }

    private fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        val chCount = inputAudioFormat.channelCount
        while (input.hasRemaining()) {
            for (ch in 0 until chCount) {
                if (!input.hasRemaining()) break
                val sample    = input.short.toFloat() / Short.MAX_VALUE
                val processed = applyFilterChain(ch, sample).coerceIn(-1f, 1f)
                output.putShort((processed * Short.MAX_VALUE).toInt().toShort())
            }
        }
    }

    /** Run sample through all 10 bands for the given channel. */
    private fun applyFilterChain(channel: Int, sample: Float): Float {
        if (filters.isEmpty() || channel >= filters.size) return sample
        var s = sample
        val chFilters = filters[channel]
        for (band in 0 until NUM_BANDS) s = chFilters[band].process(s)
        return s
    }
}