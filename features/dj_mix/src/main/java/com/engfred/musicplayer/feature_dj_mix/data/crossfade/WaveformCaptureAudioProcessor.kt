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
 * * DO NOT ADD `new` OBJECT ALLOCATIONS IN `queueInput`!
 * * This class runs directly on ExoPlayer's real-time audio render thread.
 * If you allocate memory here (e.g., creating arrays, allocating ByteBuffers),
 * the Garbage Collector will pause this thread. A 5ms GC pause during a Bluetooth
 * codec renegotiation WILL starve the OS AudioTrack and permanently freeze playback.
 * * ARCHITECTURE:
 * - Audio Thread: Quickly copies bytes to `tempWaveform`, updates `latestWaveform`, and passes buffer through.
 * - UI/Background Thread: Calls `computeCurrentBands()` to read the volatile array safely on its own time.
 */
@OptIn(UnstableApi::class)
class WaveformCaptureAudioProcessor : BaseAudioProcessor() {

    companion object {
        const val BAND_COUNT = 32
        private const val WINDOW_SAMPLES = 256
        private const val SMOOTHING = 0.40f
    }

    // The "Billboard": The audio thread writes to this, the UI background thread reads it.
    @Volatile private var latestWaveform = FloatArray(WINDOW_SAMPLES)
    private val tempWaveform = FloatArray(WINDOW_SAMPLES)
    private var captureIndex = 0

    // Math state (Only accessed by the background Coroutine)
    private val smoothed = FloatArray(BAND_COUNT)
    private var bandBoundaries = IntArray(0)
    private var currentSampleRate = 44100

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        currentSampleRate = inputAudioFormat.sampleRate
        captureIndex = 0
        smoothed.fill(0f)
        buildBandBoundaries()
        return inputAudioFormat // Pass-through directly
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val startPosition = inputBuffer.position()
        val limit = inputBuffer.limit()
        val channels = inputAudioFormat.channelCount
        val step = 2 * channels // 16-bit PCM

        var p = startPosition
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // 1. FAST COPY: Snatch a quick copy of the audio without blocking or allocating
        while (p < limit && captureIndex < WINDOW_SAMPLES) {
            var mono = 0f
            for (c in 0 until channels) {
                mono += inputBuffer.getShort(p + c * 2).toFloat()
            }
            tempWaveform[captureIndex++] = mono / (32768f * channels)
            p += step
        }

        if (captureIndex >= WINDOW_SAMPLES) {
            // Commit to the volatile array so the UI can read it
            System.arraycopy(tempWaveform, 0, latestWaveform, 0, WINDOW_SAMPLES)
            captureIndex = 0
        }

        // 2. SAFE PASS-THROUGH: Uses BaseAudioProcessor's internal memory management
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
    }

    /**
     * EXECUTED ON THE BACKGROUND DISPATCHER.
     * Keeps heavy Goertzel math off the audio render thread.
     */
    fun computeCurrentBands(): FloatArray {
        val window = latestWaveform // Read volatile array state
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