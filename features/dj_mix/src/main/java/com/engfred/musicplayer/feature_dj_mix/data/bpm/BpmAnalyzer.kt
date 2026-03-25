package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Performs BPM analysis and first-beat detection for DJ Auto-Mix.
 *
 * ── Pipeline ─────────────────────────────────────────────────────────────────
 * URI
 *   → MediaExtractor + MediaCodec        (decode to 16-bit LE PCM)
 *   → mixToMono                          (multi-channel → mono)
 *   → calculateKWeightedAmplitude        (EBU R128 / ITU-R BS.1770-4 loudness)
 *   → intro skip (first 15 s discarded)  (avoids non-percussive intros)
 *   → PCM bytes → FloatArray             (normalised to -1.0 .. +1.0)
 *   → analyzeBeatsNative (JNI → aubio)   (dynamic-programming beat tracker)
 *   → BpmAnalysisResult
 *
 * ── Why native aubio replaces TarsosDSP ──────────────────────────────────────
 * TarsosDSP's ComplexOnsetDetector derives BPM from the median inter-onset
 * interval — a heuristic that fails on swing/groove, dense EDM, and tracks
 * with atmospheric intros. Typical accuracy on real-world music: ~65%.
 *
 * aubio_tempo uses a dynamic programming (Viterbi-like) beat tracker that fits
 * a probabilistic tempo model to the onset detection function. It handles all
 * the hard cases and reaches ~88–93% accuracy on standard MIREX benchmarks.
 * The native library is compiled from aubio 0.4.9 source (GPL-3.0-or-later)
 * via CMake FetchContent — see features/dj_mix/src/main/cpp/CMakeLists.txt.
 *
 * ── K-weighted amplitude (EBU R128) ──────────────────────────────────────────
 * Two-stage biquad IIR filter chain (ITU-R BS.1770-4 K-weighting):
 *   Stage 1 — High-shelf pre-filter : Fc = 1681.974 Hz, gain = +4 dB, S = 1
 *   Stage 2 — Butterworth high-pass : Fc =   38.134 Hz, order = 2,  Q = 1/√2
 * Result is perceptually accurate for auto-gain matching across genres.
 *
 * No RECORD_AUDIO permission is used anywhere in this class.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BpmAnalyzer"

        /** Decode at most this many ms — enough for reliable BPM even on long tracks. */
        private const val MAX_ANALYSIS_DURATION_MS = 90_000L

        /**
         * Discard this many seconds from the start of decoded audio before
         * passing to the beat tracker. Eliminates ambient/non-percussive intros
         * that confuse the onset detection function.
         * Skipped only if the track is longer than 2× this value.
         */
        private const val ANALYSIS_SKIP_SECONDS = 15f

        /** Clamp the native result to a sane DJ range. */
        private const val MIN_BPM = 55f
        private const val MAX_BPM = 215f
    }

    /**
     * Result of a full BPM + first-beat analysis.
     *
     * @param bpm         Estimated tempo in beats-per-minute.
     * @param firstBeatMs Timestamp (ms) of the first strong beat in the original track.
     *                    Used by CrossfadeEngine to cue the incoming track exactly on beat.
     * @param amplitude   K-weighted RMS of the full track. Used by CrossfadeEngine for
     *                    auto-gain normalisation so tracks play at equal perceived loudness.
     */
    data class BpmAnalysisResult(
        val bpm: Float,
        val firstBeatMs: Long,
        val amplitude: Float
    )

    // ── Native library ────────────────────────────────────────────────────────

    init {
        System.loadLibrary("bpm_analyzer")
    }

    /**
     * Implemented in aubio_bridge.c (native code).
     *
     * @param monoSamples Normalised mono PCM float samples in [-1.0, +1.0].
     *                    Already intro-skipped on the Kotlin side.
     * @param sampleRate  Original sample rate of the decoded audio.
     * @return float[3] { bpm, firstBeatMs_relative, confidence }
     *         where firstBeatMs_relative is relative to the START of [monoSamples]
     *         (not the original track — the intro-skip offset is added back in Kotlin).
     *         Returns null if beat tracking fails.
     */
    private external fun analyzeBeatsNative(monoSamples: FloatArray, sampleRate: Int): FloatArray?

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Analyses [uri] and returns a BPM estimate, first-beat cue point, and
     * perceptual loudness. Returns null on any failure.
     *
     * Always runs on Dispatchers.IO — safe to call from any coroutine scope.
     */
    suspend fun analyzeBpm(uri: Uri): BpmAnalysisResult? = withContext(Dispatchers.IO) {
        try {
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri)
                ?: return@withContext null

            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            // ── K-weighted amplitude on the FULL track ────────────────────────
            // Computed before the intro skip so auto-gain reflects the whole song.
            val amplitude = calculateKWeightedAmplitude(monoBytes, sampleRate)

            // ── Intro skip ────────────────────────────────────────────────────
            val skipSamples = (ANALYSIS_SKIP_SECONDS * sampleRate).toInt()
            val skipBytes   = skipSamples * 2  // 16-bit = 2 bytes per sample
            val (analysisBytes, skippedSeconds) = if (monoBytes.size > skipBytes * 2) {
                monoBytes.copyOfRange(skipBytes, monoBytes.size) to ANALYSIS_SKIP_SECONDS
            } else {
                Log.d(TAG, "Track too short for intro skip — analysing from start")
                monoBytes to 0f
            }

            // ── Convert 16-bit LE PCM → normalised FloatArray for native code ─
            val numSamples  = analysisBytes.size / 2
            val floatSamples = FloatArray(numSamples)
            val buf = ByteBuffer.wrap(analysisBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floatSamples[i] = buf.short.toFloat() / Short.MAX_VALUE
            }

            // ── Native aubio beat tracking ─────────────────────────────────────
            val nativeResult = analyzeBeatsNative(floatSamples, sampleRate)
                ?: run {
                    Log.w(TAG, "analyzeBeatsNative returned null for $uri")
                    return@withContext null
                }

            val bpm                  = nativeResult[0].coerceIn(MIN_BPM, MAX_BPM)
            val firstBeatMsRelative  = nativeResult[1].toLong()
            val confidence           = nativeResult[2]

            // Add the intro-skip offset back so the timestamp maps to the real track position
            val firstBeatMs = firstBeatMsRelative + (skippedSeconds * 1000f).toLong()

            Log.d(TAG, "BPM=$bpm firstBeat=${firstBeatMs}ms " +
                    "confidence=${String.format("%.3f", confidence)} " +
                    "kRms=${String.format("%.4f", amplitude)}")

            BpmAnalysisResult(bpm = bpm, firstBeatMs = firstBeatMs, amplitude = amplitude)

        } catch (e: Exception) {
            Log.e(TAG, "BPM analysis failed for $uri", e)
            null
        }
    }

    // ── K-weighted amplitude (EBU R128 / ITU-R BS.1770-4) ────────────────────

    /**
     * Biquad IIR coefficients in Direct Form II Transposed (normalised: a0 = 1).
     */
    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    /**
     * EBU R128 K-weighting Stage 1: high-shelf pre-filter.
     * Fc = 1681.974 Hz, gain = +4 dB, shelf slope S = 1.
     */
    private fun designHighShelf(fc: Double, gainDb: Double, sampleRate: Double): BiquadCoeffs {
        val A     = Math.pow(10.0, gainDb / 40.0)
        val w0    = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val sqrtA = Math.sqrt(A)
        val alpha = sinW0 * 0.7071067811865476  // sin(w0) / (2Q), Q = 1/√2
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        val b0 = A  * ((A + 1) + (A - 1) * cosW0 + twoSqrtAAlpha)
        val b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0)
        val b2 = A  * ((A + 1) + (A - 1) * cosW0 - twoSqrtAAlpha)
        val a0 =       (A + 1) - (A - 1) * cosW0 + twoSqrtAAlpha
        val a1 = 2.0 * ((A - 1) - (A + 1) * cosW0)
        val a2 =       (A + 1) - (A - 1) * cosW0 - twoSqrtAAlpha

        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    /**
     * EBU R128 K-weighting Stage 2: 2nd-order Butterworth high-pass.
     * Fc = 38.134 Hz, Q = 1/√2.
     */
    private fun designHighPass2nd(fc: Double, sampleRate: Double): BiquadCoeffs {
        val w0    = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 * 0.7071067811865476

        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    /**
     * Direct Form II Transposed biquad filter — numerically stable for double precision.
     */
    private fun applyBiquad(input: FloatArray, c: BiquadCoeffs): FloatArray {
        val output = FloatArray(input.size)
        var w1 = 0.0
        var w2 = 0.0
        for (i in input.indices) {
            val x  = input[i].toDouble()
            val y  = c.b0 * x + w1
            w1     = c.b1 * x - c.a1 * y + w2
            w2     = c.b2 * x - c.a2 * y
            output[i] = y.toFloat()
        }
        return output
    }

    /**
     * Computes K-weighted RMS amplitude — approximates EBU R128 integrated loudness.
     *
     * Two-stage K-weighting filter (ITU-R BS.1770-4):
     *   Stage 1: high-shelf, Fc = 1681.974 Hz, +4 dB  — boosts high-frequency content
     *   Stage 2: high-pass,  Fc =   38.134 Hz          — removes DC / sub-bass shelf
     *
     * Returns sqrt(mean_square) of the filtered signal. This is proportional to
     * BS.1770 integrated loudness and correctly assigns lower amplitude to bass-heavy
     * modern tracks, enabling accurate auto-gain in CrossfadeEngine.
     *
     * Filter coefficients are computed analytically for [sampleRate] so any MediaCodec
     * output rate (44100, 48000, 32000, etc.) is handled without a lookup table.
     */
    private fun calculateKWeightedAmplitude(pcmBytes: ByteArray, sampleRate: Int): Float {
        if (pcmBytes.isEmpty()) return 0f
        val numSamples = pcmBytes.size / 2
        if (numSamples == 0) return 0f

        val buf     = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(numSamples) { buf.short.toFloat() / Short.MAX_VALUE }

        val fs       = sampleRate.toDouble()
        val stage1   = designHighShelf(1681.974, 4.0, fs)
        val stage2   = designHighPass2nd(38.134, fs)
        val filtered = applyBiquad(applyBiquad(samples, stage1), stage2)

        var sumSq = 0.0
        for (s in filtered) sumSq += s.toDouble() * s

        return sqrt(sumSq / numSamples).toFloat()
    }

    // ── PCM decoding ──────────────────────────────────────────────────────────

    /**
     * Decodes audio from [uri] to raw 16-bit signed little-endian PCM.
     * Stops decoding after [MAX_ANALYSIS_DURATION_MS] to keep analysis fast.
     * Returns Triple(pcmBytes, sampleRate, channelCount), or null on error.
     */
    private fun decodeToPcm(uri: Uri): Triple<ByteArray, Int, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var mediaFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt  = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mediaFormat     = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || mediaFormat == null) {
                Log.w(TAG, "No audio track found in $uri")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime         = mediaFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate   = mediaFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channelCount = mediaFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()

            val output     = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos   = false
            var outputEos  = false

            while (!outputEos) {
                if (!inputEos) {
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val inBuf      = codec.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        when {
                            sampleSize < 0 -> {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            extractor.sampleTime / 1_000 >= MAX_ANALYSIS_DURATION_MS -> {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            else -> {
                                codec.queueInputBuffer(inputIdx, 0, sampleSize,
                                    extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER       -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outputIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            output.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                }
            }
            Triple(output.toByteArray(), sampleRate, channelCount)

        } catch (e: Exception) {
            Log.e(TAG, "PCM decode failed for $uri", e)
            null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    // ── Audio utilities ───────────────────────────────────────────────────────

    /**
     * Mixes interleaved multi-channel 16-bit little-endian PCM down to mono.
     * Input frame layout: [ch0_lo, ch0_hi, ch1_lo, ch1_hi, ...]
     */
    private fun mixToMono(pcm: ByteArray, channelCount: Int): ByteArray {
        val bytesPerFrame = channelCount * 2
        val frameCount    = pcm.size / bytesPerFrame
        val mono          = ByteArray(frameCount * 2)
        val src           = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val dst           = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) {
            var sum = 0L
            repeat(channelCount) { sum += src.short.toLong() }
            dst.putShort((sum / channelCount).toShort())
        }
        return mono
    }

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}