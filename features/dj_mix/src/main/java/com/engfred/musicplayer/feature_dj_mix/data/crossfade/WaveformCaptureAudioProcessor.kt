package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class WaveformCaptureAudioProcessor : AudioProcessor {

    companion object {
        const val BAND_COUNT = 32
        private const val WINDOW_SAMPLES = 256
        private const val SMOOTHING = 0.40f
        private const val TAG = "WaveformCapture"

        // Log one window every ~500ms to avoid flooding logcat.
        // At 44100Hz with 256 samples, one window = ~5.8ms → log every ~86 windows.
        private const val LOG_EVERY_N_WINDOWS = 86
    }

    private val _bands = AtomicReference(FloatArray(BAND_COUNT))
    fun getBands(): FloatArray = _bands.get().copyOf()

    private var format: AudioFormat = AudioFormat.NOT_SET
    private var sampleRate = 44100
    private var channelCount = 2
    private var isConfigured = false

    private val accumulator = FloatArray(WINDOW_SAMPLES)
    private var accumulatorPos = 0
    private val smoothed = FloatArray(BAND_COUNT)
    private lateinit var bandBoundaries: IntArray
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // Counters for debug logging
    private var windowCount = 0
    private var totalBytesReceived = 0L
    private var flatWindowCount = 0   // windows where peak was below 0.001f (all bands ~0)
    private var activeWindowCount = 0 // windows with real signal

    override fun configure(inputFormat: AudioFormat): AudioFormat {
        format       = inputFormat
        sampleRate   = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        isConfigured = true
        accumulatorPos = 0
        smoothed.fill(0f)
        windowCount = 0
        totalBytesReceived = 0L
        flatWindowCount = 0
        activeWindowCount = 0
        buildBandBoundaries()
        Log.i(TAG, "configure: sampleRate=$sampleRate channelCount=$channelCount " +
                "WINDOW_SAMPLES=$WINDOW_SAMPLES resolution=${sampleRate.toDouble() / WINDOW_SAMPLES}Hz/bin")
        return inputFormat
    }

    override fun isActive() = isConfigured

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isConfigured || !inputBuffer.hasRemaining()) return

        val bytes = inputBuffer.remaining()
        totalBytesReceived += bytes

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

        if (outputBuffer.capacity() < bytes) {
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() { /* pass-through */ }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded() = false

    override fun flush() {
        accumulatorPos = 0
        outputBuffer   = AudioProcessor.EMPTY_BUFFER
        Log.d(TAG, "flush: totalWindows=$windowCount active=$activeWindowCount flat=$flatWindowCount totalBytes=$totalBytesReceived")
    }

    override fun reset() {
        isConfigured   = false
        accumulatorPos = 0
        smoothed.fill(0f)
        _bands.set(FloatArray(BAND_COUNT))
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        Log.d(TAG, "reset: totalWindows=$windowCount active=$activeWindowCount flat=$flatWindowCount")
    }

    private fun processWindow() {
        windowCount++
        val result = FloatArray(BAND_COUNT)

        for (band in 0 until BAND_COUNT) {
            val loIdx = bandBoundaries[band]
            val hiIdx = bandBoundaries[band + 1]

            var energy = 0.0
            for (binIdx in loIdx until hiIdx) {
                val freq  = binIdx.toDouble() * sampleRate / WINDOW_SAMPLES
                val omega = 2.0 * Math.PI * freq / sampleRate
                val coeff = 2.0 * Math.cos(omega)
                var s1 = 0.0; var s2 = 0.0
                for (sample in accumulator) {
                    val s0 = sample.toDouble() + coeff * s1 - s2
                    s2 = s1; s1 = s0
                }
                energy += s1 * s1 + s2 * s2 - coeff * s1 * s2
            }

            val rms = sqrt(energy / ((hiIdx - loIdx) * WINDOW_SAMPLES)).toFloat().coerceAtLeast(0f)
            smoothed[band] = smoothed[band] * SMOOTHING + rms * (1f - SMOOTHING)
            result[band]   = smoothed[band]
        }

        val peak = result.max().takeIf { it > 0.001f } ?: 1f

        if (peak <= 0.001f) {
            flatWindowCount++
        } else {
            activeWindowCount++
            for (i in result.indices) result[i] = (result[i] / peak).coerceIn(0f, 1f)
        }

        _bands.set(result)

        // ── Periodic debug log every ~500ms ──────────────────────────────────
        if (windowCount % LOG_EVERY_N_WINDOWS == 0) {
            val bands = result
            val leftHalf  = bands.take(BAND_COUNT / 2)
            val rightHalf = bands.drop(BAND_COUNT / 2)
            val leftAvg   = leftHalf.average()
            val rightAvg  = rightHalf.average()
            val leftMax   = leftHalf.max()
            val rightMax  = rightHalf.max()

            // This is the key check: if leftAvg is near 0 while rightAvg is active,
            // the flat-left bug is still present.
            val isLeftFlat = leftAvg < 0.02

            Log.d(TAG, buildString {
                append("window=#$windowCount | ")
                append("peak=${"%.4f".format(peak)} | ")
                append("leftHalf avg=${"%.3f".format(leftAvg)} max=${"%.3f".format(leftMax)} | ")
                append("rightHalf avg=${"%.3f".format(rightAvg)} max=${"%.3f".format(rightMax)} | ")
                append("flatWindows=$flatWindowCount activeWindows=$activeWindowCount | ")
                if (isLeftFlat && rightAvg > 0.05) {
                    append("⚠️ LEFT HALF STILL FLAT — boundary bug not fixed!")
                } else if (isLeftFlat) {
                    append("left quiet (music may be treble-heavy — OK)")
                } else {
                    append("✅ both halves active")
                }
            })

            // Full band dump — lets you see exactly which bands are alive
            val bandDump = bands.mapIndexed { i, v ->
                val loHz = (bandBoundaries[i].toDouble() * sampleRate / WINDOW_SAMPLES).toInt()
                val hiHz = (bandBoundaries[i + 1].toDouble() * sampleRate / WINDOW_SAMPLES).toInt()
                "B$i[${loHz}-${hiHz}Hz]=${"%.2f".format(v)}"
            }.joinToString(" ")
            Log.v(TAG, "bands: $bandDump")
        }
    }

    private fun buildBandBoundaries() {
        val nyquist = sampleRate / 2.0
        val minFreq = sampleRate.toDouble() / WINDOW_SAMPLES
        val maxFreq = minOf(nyquist, 20000.0)
        val logMin  = ln(minFreq)
        val logMax  = ln(maxFreq)
        val maxBin  = WINDOW_SAMPLES / 2

        val raw = IntArray(BAND_COUNT + 1) { band ->
            val logFreq = logMin + (logMax - logMin) * band.toDouble() / BAND_COUNT
            val freq    = Math.exp(logFreq)
            (freq * WINDOW_SAMPLES / sampleRate).toInt().coerceIn(1, maxBin)
        }

        bandBoundaries = IntArray(BAND_COUNT + 1)
        bandBoundaries[0] = raw[0].coerceAtLeast(1)
        for (i in 1..BAND_COUNT) {
            bandBoundaries[i] = maxOf(bandBoundaries[i - 1] + 1, raw[i]).coerceAtMost(maxBin)
        }

        // Log the full boundary table once at startup so you can verify
        // every band has a non-zero width and no two share the same boundary.
        val boundaryDump = (0 until BAND_COUNT).joinToString("\n") { band ->
            val loHz = (bandBoundaries[band].toDouble()     * sampleRate / WINDOW_SAMPLES).toInt()
            val hiHz = (bandBoundaries[band + 1].toDouble() * sampleRate / WINDOW_SAMPLES).toInt()
            val binWidth = bandBoundaries[band + 1] - bandBoundaries[band]
            "  Band ${"%-2d".format(band)}: bins ${bandBoundaries[band]}–${bandBoundaries[band+1]} " +
                    "(${loHz}–${hiHz} Hz, width=$binWidth bins)" +
                    if (binWidth <= 0) "  ❌ COLLAPSED" else ""
        }
        Log.i(TAG, "buildBandBoundaries:\n$boundaryDump")
    }
}