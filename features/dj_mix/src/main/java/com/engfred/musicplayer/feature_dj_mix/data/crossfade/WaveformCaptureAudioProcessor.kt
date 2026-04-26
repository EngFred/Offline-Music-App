package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 🚨 CRITICAL AUDIO PIPELINE COMPONENT 🚨
 * DO NOT ADD `new` OBJECT ALLOCATIONS IN `queueInput`!
 * This class runs directly on ExoPlayer's real-time audio render thread.
 */
@OptIn(UnstableApi::class)
class WaveformCaptureAudioProcessor : BaseAudioProcessor() {

    companion object {
        const val BAND_COUNT = 32
        private const val WINDOW_SAMPLES = 256
        private const val SMOOTHING = 0.40f

        // Better tuned for music / Afrobeats
        private const val LOW_ENERGY_THRESHOLD = 0.42f
        private const val DROP_TREND_THRESHOLD = -0.12f
    }

    data class AudioFeatures(
        val rms: Float,
        val trend: Float,
        val isLowEnergy: Boolean,
        val isDropping: Boolean
    )

    // The "Billboard": The audio thread writes to this, the UI background thread reads it.
    @Volatile private var latestWaveform = FloatArray(WINDOW_SAMPLES)
    @Volatile private var latestRms = 0.35f

    private val tempWaveform = FloatArray(WINDOW_SAMPLES)
    private var captureIndex = 0

    // Math state (Only accessed by the background Coroutine)
    private val smoothed = FloatArray(BAND_COUNT)
    private var bandBoundaries = IntArray(0)
    private var currentSampleRate = 44100

    // Feature Extraction State
    private var shortTermRms = 0.35f
    private var longTermRms = 0.35f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        currentSampleRate = inputAudioFormat.sampleRate
        captureIndex = 0
        smoothed.fill(0f)
        shortTermRms = 0.35f
        longTermRms = 0.35f
        buildBandBoundaries()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val startPosition = inputBuffer.position()
        val limit = inputBuffer.limit()
        val channels = inputAudioFormat.channelCount
        val step = 2 * channels

        var p = startPosition
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // FAST COPY
        while (p < limit && captureIndex < WINDOW_SAMPLES) {
            var mono = 0f
            for (c in 0 until channels) {
                mono += inputBuffer.getShort(p + c * 2).toFloat()
            }
            tempWaveform[captureIndex++] = mono / (32768f * channels)
            p += step
        }

        if (captureIndex >= WINDOW_SAMPLES) {
            // Cheaply calculate RMS directly on the audio thread
            var sumSq = 0.0
            for (i in 0 until WINDOW_SAMPLES) {
                val s = tempWaveform[i]
                sumSq += s * s
            }
            latestRms = sqrt(sumSq / WINDOW_SAMPLES).toFloat().coerceIn(0f, 1f)

            System.arraycopy(tempWaveform, 0, latestWaveform, 0, WINDOW_SAMPLES)
            captureIndex = 0
        }

        // SAFE PASS-THROUGH
        val buffer = replaceOutputBuffer(remaining)
        inputBuffer.position(startPosition)
        buffer.put(inputBuffer)
        buffer.flip()
    }

    override fun onFlush() {
        captureIndex = 0
    }

    override fun onReset() {
        captureIndex = 0
        smoothed.fill(0f)
        shortTermRms = 0.35f
        longTermRms = 0.35f
    }

    /**
     * Calculates real-time energy trends for the auto-DJ trigger logic.
     * EXECUTED ON THE BACKGROUND DISPATCHER.
     */
    fun computeCurrentFeatures(): AudioFeatures {
        val currentRms = latestRms   // use the pre-computed value from audio thread

        // EWMA smoothing
        shortTermRms = shortTermRms * 0.75f + currentRms * 0.25f   // faster reaction
        longTermRms = longTermRms * 0.96f + currentRms * 0.04f     // slower baseline

        val trend = if (longTermRms > 0.001f) {
            (shortTermRms - longTermRms) / longTermRms
        } else 0f

        return AudioFeatures(
            rms = currentRms,
            trend = trend.coerceIn(-1f, 1f),
            isLowEnergy = shortTermRms < LOW_ENERGY_THRESHOLD,
            isDropping = trend < DROP_TREND_THRESHOLD && shortTermRms < 0.68f
        )
    }

    fun computeCurrentBands(): FloatArray {
        val window = latestWaveform
        val result = FloatArray(BAND_COUNT)

        if (bandBoundaries.isEmpty()) return result

        for (band in 0 until BAND_COUNT) {
            val loIdx = bandBoundaries[band]
            val hiIdx = bandBoundaries[band + 1]
            var energy = 0.0

            for (binIdx in loIdx until hiIdx) {
                val freq = binIdx.toDouble() * currentSampleRate / WINDOW_SAMPLES
                val omega = 2.0 * Math.PI * freq / currentSampleRate
                val coeff = 2.0 * Math.cos(omega)
                var s1 = 0.0
                var s2 = 0.0
                for (sample in window) {
                    val s0 = sample.toDouble() + coeff * s1 - s2
                    s2 = s1
                    s1 = s0
                }
                energy += s1 * s1 + s2 * s2 - coeff * s1 * s2
            }

            val rms = sqrt(energy / ((hiIdx - loIdx) * WINDOW_SAMPLES)).toFloat().coerceAtLeast(0f)
            smoothed[band] = smoothed[band] * SMOOTHING + rms * (1f - SMOOTHING)
            result[band] = smoothed[band]
        }

        val peak = result.maxOrNull()?.takeIf { it > 0.001f } ?: 1f
        if (peak > 0.001f) {
            for (i in result.indices) result[i] = (result[i] / peak).coerceIn(0f, 1f)
        }
        return result
    }

    private fun buildBandBoundaries() {
        val nyquist = currentSampleRate / 2.0
        val minFreq = currentSampleRate.toDouble() / WINDOW_SAMPLES
        val maxFreq = minOf(nyquist, 20000.0)
        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)
        val maxBin = WINDOW_SAMPLES / 2

        val raw = IntArray(BAND_COUNT + 1) { band ->
            val logFreq = logMin + (logMax - logMin) * band.toDouble() / BAND_COUNT
            val freq = Math.exp(logFreq)
            (freq * WINDOW_SAMPLES / currentSampleRate).toInt().coerceIn(1, maxBin)
        }

        bandBoundaries = IntArray(BAND_COUNT + 1)
        bandBoundaries[0] = raw[0].coerceAtLeast(1)
        for (i in 1..BAND_COUNT) {
            bandBoundaries[i] = maxOf(bandBoundaries[i - 1] + 1, raw[i]).coerceAtMost(maxBin)
        }
    }
}