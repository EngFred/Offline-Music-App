package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A pass-through [AudioProcessor] that computes a real-time multi-band
 * amplitude spectrum from the actual PCM data flowing through ExoPlayer.
 *
 * No RECORD_AUDIO permission needed — we own this audio pipeline.
 *
 * Architecture:
 * - Sits BEFORE RubberBandAudioProcessor in the chain so we measure
 * pre-stretch audio (natural spectral content, not time-stretched).
 * - Accumulates PCM frames into a 256-sample window, then computes
 * per-band RMS across [BAND_COUNT] logarithmically-spaced frequency bins.
 * - Results are written atomically via [AtomicReference] — safe to read
 * from any thread (waveform loop on Default dispatcher).
 *
 * Band layout (logarithmic, 44100 Hz assumed):
 * Band  0–3 : sub-bass  (20–80 Hz)
 * Band  4–9 : bass      (80–300 Hz)
 * Band 10–19: mids      (300–3000 Hz)
 * Band 20–31: highs     (3000–20000 Hz)
 */
@OptIn(UnstableApi::class)
class WaveformCaptureAudioProcessor : AudioProcessor {

    companion object {
        const val BAND_COUNT = 32
        private const val WINDOW_SAMPLES = 256  // ~5.8ms at 44100 Hz — enough for 60fps
        private const val SMOOTHING = 0.72f     // exponential smoothing per frame
    }

    // ── Output: read from waveform loop on any thread ─────────────────────────
    private val _bands = AtomicReference(FloatArray(BAND_COUNT))
    fun getBands(): FloatArray = _bands.get().copyOf()

    private var format: AudioFormat = AudioFormat.NOT_SET
    private var sampleRate = 44100
    private var channelCount = 2
    private var isConfigured = false

    // Accumulation buffer
    private val accumulator = FloatArray(WINDOW_SAMPLES)
    private var accumulatorPos = 0

    // Smoothed output (only touched on audio thread)
    private val smoothed = FloatArray(BAND_COUNT)

    // Pre-computed band boundaries in sample-index space (for the FFT bins)
    private lateinit var bandBoundaries: IntArray

    // ExoPlayer Pipeline Output Buffer
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // ── AudioProcessor interface ──────────────────────────────────────────────

    override fun configure(inputFormat: AudioFormat): AudioFormat {
        format = inputFormat
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        isConfigured = true
        accumulatorPos = 0
        smoothed.fill(0f)
        buildBandBoundaries()
        return inputFormat // pure pass-through
    }

    override fun isActive() = isConfigured

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isConfigured || !inputBuffer.hasRemaining()) return

        val bytes = inputBuffer.remaining()

        // Read PCM_16BIT samples, mix to mono on the fly, accumulate
        val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (view.hasRemaining()) {
            var mono = 0f
            repeat(channelCount) {
                mono += if (view.hasRemaining()) view.get().toFloat() / 32768f else 0f
            }
            mono /= channelCount

            accumulator[accumulatorPos++] = mono
            if (accumulatorPos >= WINDOW_SAMPLES) {
                processWindow()
                accumulatorPos = 0
            }
        }

        // Prepare the output buffer for the next processor in the chain
        if (outputBuffer.capacity() < bytes) {
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        // Copy input to output, which intrinsically advances the inputBuffer position,
        // signaling to ExoPlayer that we have successfully consumed it.
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() { /* pass-through, nothing to flush */ }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded() = false

    override fun flush() {
        accumulatorPos = 0
        outputBuffer = AudioProcessor.EMPTY_BUFFER
    }

    override fun reset() {
        isConfigured = false
        accumulatorPos = 0
        smoothed.fill(0f)
        _bands.set(FloatArray(BAND_COUNT))
        outputBuffer = AudioProcessor.EMPTY_BUFFER
    }

    // ── DSP ───────────────────────────────────────────────────────────────────

    /**
     * Runs a minimal DFT over [WINDOW_SAMPLES] mono PCM frames to extract
     * per-band energy. We use a Goertzel-style approach per band centre
     * rather than a full FFT — cheaper for 32 bands on 256 samples.
     */
    private fun processWindow() {
        val result = FloatArray(BAND_COUNT)

        for (band in 0 until BAND_COUNT) {
            val loIdx = bandBoundaries[band]
            val hiIdx = bandBoundaries[band + 1]
            // Goertzel energy for this frequency range
            var energy = 0.0
            for (binIdx in loIdx until hiIdx) {
                val freq = binIdx.toDouble() * sampleRate / WINDOW_SAMPLES
                val omega = 2.0 * Math.PI * freq / sampleRate
                var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
                val coeff = 2.0 * Math.cos(omega)
                for (sample in accumulator) {
                    s0 = sample.toDouble() + coeff * s1 - s2
                    s2 = s1; s1 = s0
                }
                energy += s1 * s1 + s2 * s2 - coeff * s1 * s2
            }
            val rms = if (hiIdx > loIdx)
                sqrt(energy / ((hiIdx - loIdx) * WINDOW_SAMPLES)).toFloat()
            else 0f

            // Exponential smoothing — prevents jitter at 60fps
            smoothed[band] = smoothed[band] * SMOOTHING + rms * (1f - SMOOTHING)
            result[band] = smoothed[band]
        }

        // Normalise so the loudest band = 1.0 (prevents silent tracks from looking flat)
        val peak = result.max().takeIf { it > 0.001f } ?: 1f
        for (i in result.indices) result[i] = (result[i] / peak).coerceIn(0f, 1f)

        _bands.set(result)
    }

    /**
     * Builds logarithmically-spaced band boundaries in FFT bin index space.
     * Log spacing mimics how human hearing perceives frequency — bass bands
     * get more width than treble bands, which matches the visual expectation.
     */
    private fun buildBandBoundaries() {
        val nyquist = sampleRate / 2.0
        val minFreq = 20.0
        val maxFreq = minOf(nyquist, 20000.0)
        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)

        bandBoundaries = IntArray(BAND_COUNT + 1) { band ->
            val logFreq = logMin + (logMax - logMin) * band.toDouble() / BAND_COUNT
            val freq = Math.exp(logFreq)
            (freq * WINDOW_SAMPLES / sampleRate).toInt().coerceIn(1, WINDOW_SAMPLES / 2)
        }
    }
}