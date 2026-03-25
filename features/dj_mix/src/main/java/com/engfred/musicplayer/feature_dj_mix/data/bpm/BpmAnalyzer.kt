package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.UniversalAudioInputStream
import be.tarsos.dsp.onsets.ComplexOnsetDetector
import be.tarsos.dsp.onsets.OnsetHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Wraps TarsosDSP's onset-based BPM detection behind a single suspend function.
 *
 * ── Why NOT AudioDispatcherFactory ───────────────────────────────────────────
 * [be.tarsos.dsp:core:2.5] does NOT expose AudioDispatcherFactory.fromFloatArray
 * on Android — that factory method internally uses javax.sound.sampled.AudioFormat
 * which does not exist on Android and will throw at runtime. The correct Android
 * pattern is to construct AudioDispatcher directly using UniversalAudioInputStream.
 *
 * ── Pipeline ─────────────────────────────────────────────────────────────────
 * URI -> MediaExtractor + MediaCodec (decode to 16-bit PCM)
 * -> mix down to mono (if stereo / multi-channel)
 * -> trim first ANALYSIS_SKIP_SECONDS to skip non-percussive intros
 * -> K-weighted IIR amplitude (EBU R128 / ITU-R BS.1770-4)
 * -> UniversalAudioInputStream (TarsosDSP I/O bridge, core module)
 * -> AudioDispatcher (frames PCM into fixed-size buffers)
 * -> ComplexOnsetDetector (detects onset timestamps + salience)
 * -> salience-filtered first beat + median inter-onset BPM
 *
 * ── Key improvements ──────────────────────────────────────────────────────────
 * 1. INTRO SKIP: Tracks with ambient/non-percussive intros have the first
 *    ANALYSIS_SKIP_SECONDS of decoded audio discarded before onset detection.
 *    Timestamps are offset by the skipped amount to remain accurate.
 *
 * 2. SALIENCE FIRST BEAT: Collects onset salience values and selects the first
 *    onset whose salience is >= max(median_salience, MIN_STRONG_ONSET_SALIENCE),
 *    reliably identifying the first real percussive hit rather than room noise
 *    or reverb tail.
 *
 * 3. K-WEIGHTED AMPLITUDE (EBU R128 / ITU-R BS.1770-4): Replaces both the
 *    original raw RMS and the intermediate ZCR-weighted RMS with a proper
 *    two-stage biquad IIR filter chain matching the K-weighting spec:
 *      Stage 1 — High-shelf pre-filter: Fc=1681.974 Hz, gain=+4 dB, S=1
 *      Stage 2 — Butterworth high-pass: Fc=38.134 Hz, order=2, Q=1/√2
 *    This down-weights sub-bass energy that inflates raw RMS on modern
 *    loudness-normalised tracks, giving perceptually accurate gain matching.
 *    Coefficients are computed analytically per sample rate (no hardcoded
 *    lookup table) so any MediaCodec output rate is handled correctly.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BpmAnalyzer"

        /**
         * Decode up to 90 seconds. Extended from 60s so tracks with long intros
         * still get enough percussive content after the intro skip.
         */
        private const val MAX_ANALYSIS_DURATION_MS = 90_000L

        /**
         * Trim this many seconds from the start of decoded audio before onset detection.
         * Eliminates non-percussive intros which are the primary cause of BPM
         * misdetection in electronic, cinematic, and hip-hop tracks.
         * If the track is shorter than 2× this value, no trim is applied.
         */
        private const val ANALYSIS_SKIP_SECONDS = 15f

        /** FFT window: 1024 = ~23 ms at 44100 Hz. Power-of-2 required by TarsosDSP. */
        private const val BUFFER_SIZE = 1024

        /** Sensitivity of the onset detector (lower = more onsets detected). */
        private const val ONSET_THRESHOLD = 0.25

        /** Pairs closer than this are discarded as noise bursts. */
        private const val MIN_INTER_ONSET_SEC = 0.25f

        private const val MIN_BPM = 60f
        /** Extended to cover drum & bass / hardstyle. */
        private const val MAX_BPM = 210f

        /**
         * Minimum salience for an onset to qualify as a strong hit.
         * The actual threshold used is max(this, median_salience) so it adapts per track.
         */
        private const val MIN_STRONG_ONSET_SALIENCE = 0.4f
    }

    data class BpmAnalysisResult(
        val bpm: Float,
        val firstBeatMs: Long,
        val amplitude: Float
    )

    /** Internal model: an onset event with timestamp and detection confidence. */
    private data class OnsetEvent(val timeSeconds: Float, val salience: Float)

    /**
     * Normalised biquad IIR filter coefficients (Direct Form II Transposed).
     * a0 has been divided out — only a1 and a2 remain in the denominator.
     */
    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun analyzeBpm(uri: Uri): BpmAnalysisResult? = withContext(Dispatchers.IO) {
        try {
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri)
                ?: return@withContext null

            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            // ── K-weighted amplitude (full track, before intro trim) ────────────
            // Computed on the full mono signal for accurate auto-gain matching.
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

            runOnsetBpmDetection(analysisBytes, sampleRate, amplitude, skippedSeconds)

        } catch (e: Exception) {
            Log.e(TAG, "BPM analysis failed for $uri", e)
            null
        }
    }

    // ── K-weighted amplitude (EBU R128 / ITU-R BS.1770-4) ────────────────────

    /**
     * Designs an Audio EQ Cookbook high-shelf biquad filter.
     * Used for EBU R128 K-weighting Stage 1: Fc = 1681.974 Hz, gain = +4 dB, S = 1.
     */
    private fun designHighShelf(fc: Double, gainDb: Double, sampleRate: Double): BiquadCoeffs {
        val A     = Math.pow(10.0, gainDb / 40.0)          // linear amplitude gain
        val w0    = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val sqrtA = Math.sqrt(A)
        // With S = 1 (maximum shelf slope): (A + 1/A)*(1/S - 1) + 2 = 2
        // → alpha = sin(w0)/2 * sqrt(2) = sin(w0)/√2
        val alpha          = sinW0 * 0.7071067811865476
        val twoSqrtAAlpha  = 2.0 * sqrtA * alpha

        val b0  = A  * ((A + 1) + (A - 1) * cosW0 + twoSqrtAAlpha)
        val b1  = -2.0 * A * ((A - 1) + (A + 1) * cosW0)
        val b2  = A  * ((A + 1) + (A - 1) * cosW0 - twoSqrtAAlpha)
        val a0  =       (A + 1) - (A - 1) * cosW0 + twoSqrtAAlpha
        val a1  = 2.0 * ((A - 1) - (A + 1) * cosW0)
        val a2  =       (A + 1) - (A - 1) * cosW0 - twoSqrtAAlpha

        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    /**
     * Designs a 2nd-order Butterworth high-pass biquad filter.
     * Used for EBU R128 K-weighting Stage 2: Fc = 38.134 Hz, Q = 1/√2.
     */
    private fun designHighPass2nd(fc: Double, sampleRate: Double): BiquadCoeffs {
        val w0    = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        // Q = 1/√2 (Butterworth) → alpha = sin(w0) / (2 * Q) = sin(w0) / √2
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
     * Applies a biquad IIR filter using Direct Form II Transposed.
     * This form avoids the internal state overflow issues of Direct Form I
     * and is numerically better conditioned for double-precision arithmetic.
     */
    private fun applyBiquad(input: FloatArray, c: BiquadCoeffs): FloatArray {
        val output = FloatArray(input.size)
        var w1 = 0.0
        var w2 = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = c.b0 * x + w1
            w1    = c.b1 * x - c.a1 * y + w2
            w2    = c.b2 * x - c.a2 * y
            output[i] = y.toFloat()
        }
        return output
    }

    /**
     * Computes K-weighted RMS amplitude, approximating EBU R128 integrated loudness.
     *
     * Filter chain (ITU-R BS.1770-4 K-weighting):
     *   Stage 1 — High-shelf pre-filter: Fc = 1681.974 Hz, gain = +4 dB, S = 1
     *   Stage 2 — Butterworth high-pass: Fc =   38.134 Hz, order = 2, Q = 1/√2
     *
     * After filtering, returns sqrt(mean_square) of the filtered signal.
     * This is proportional to ITU-R BS.1770 integrated loudness and is
     * perceptually accurate for auto-gain matching:
     *
     *   — Bass-heavy EDM / hip-hop tracks: sub-bass attenuated → lower amplitude
     *     → CrossfadeEngine assigns higher base volume (correct — felt level is lower)
     *   — Loud, compressed pop: full gain applied → lower base volume (correct)
     *   — Older music with low dynamic compression: gains boosted appropriately
     *
     * Coefficients are computed analytically for [sampleRate], so any MediaCodec
     * output rate (44100, 48000, 32000, etc.) is handled without a lookup table.
     *
     * No RECORD_AUDIO permission required — operates entirely on decoded PCM bytes.
     */
    private fun calculateKWeightedAmplitude(pcmBytes: ByteArray, sampleRate: Int): Float {
        if (pcmBytes.isEmpty()) return 0f
        val numSamples = pcmBytes.size / 2
        if (numSamples == 0) return 0f

        // Decode 16-bit LE PCM to normalised float samples (-1.0 .. +1.0)
        val buf     = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(numSamples) { buf.short.toFloat() / Short.MAX_VALUE }

        val fs = sampleRate.toDouble()

        // Apply two-stage K-weighting filter
        val stage1 = designHighShelf(1681.974, 4.0, fs)
        val stage2 = designHighPass2nd(38.134, fs)
        val filtered = applyBiquad(applyBiquad(samples, stage1), stage2)

        // K-weighted mean-square → RMS
        var sumSq = 0.0
        for (s in filtered) sumSq += s.toDouble() * s

        return sqrt(sumSq / numSamples).toFloat()
    }

    // ── PCM decoding ──────────────────────────────────────────────────────────

    /**
     * Decodes audio from [uri] to raw 16-bit signed little-endian PCM bytes.
     * Returns Triple(pcmBytes, sampleRate, channelCount), or null on error.
     * Decoding stops after MAX_ANALYSIS_DURATION_MS.
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
                                codec.queueInputBuffer(
                                    inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER      -> Unit
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
     * Input layout per frame: [ch0_lo, ch0_hi, ch1_lo, ch1_hi, ...]
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

    // ── TarsosDSP onset → BPM + first beat ───────────────────────────────────

    private fun runOnsetBpmDetection(
        monoPcmBytes: ByteArray,
        sampleRate: Int,
        amplitude: Float,
        skippedSeconds: Float
    ): BpmAnalysisResult? {
        val minBytes = BUFFER_SIZE * 2 * 4
        if (monoPcmBytes.size < minBytes) {
            Log.w(TAG, "Audio too short for BPM detection (${monoPcmBytes.size} bytes)")
            return null
        }

        val format = TarsosDSPAudioFormat(
            sampleRate.toFloat(),
            16,
            1,
            true,
            false  // little-endian — Android MediaCodec always outputs LE
        )
        val inputStream  = UniversalAudioInputStream(ByteArrayInputStream(monoPcmBytes), format)
        val dispatcher   = AudioDispatcher(inputStream, BUFFER_SIZE, 0)
        val onsetEvents  = mutableListOf<OnsetEvent>()
        val onsetDetector = ComplexOnsetDetector(BUFFER_SIZE, ONSET_THRESHOLD)
        onsetDetector.setHandler(OnsetHandler { timeSeconds, salience ->
            onsetEvents.add(OnsetEvent(timeSeconds.toFloat(), salience.toFloat()))
        })
        dispatcher.addAudioProcessor(onsetDetector)
        dispatcher.run()  // synchronous, on Dispatchers.IO

        return estimateBpmAndFirstBeat(onsetEvents, amplitude, skippedSeconds)
    }

    /**
     * Derives BPM from onset timestamps and selects the first *strong* beat.
     *
     * BPM: median inter-onset interval — robust against missed/doubled beats.
     *
     * First beat: the first onset whose salience >= max(median_salience,
     * MIN_STRONG_ONSET_SALIENCE). This reliably identifies the first real
     * percussive hit rather than a pickup note or reverb tail.
     *
     * [skippedSeconds] is added back to all timestamps so they correctly
     * map to playback positions in the original (un-trimmed) audio.
     */
    private fun estimateBpmAndFirstBeat(
        onsetEvents: List<OnsetEvent>,
        amplitude: Float,
        skippedSeconds: Float
    ): BpmAnalysisResult? {
        if (onsetEvents.size < 4) {
            Log.w(TAG, "Too few onsets (${onsetEvents.size}) for reliable BPM")
            return null
        }

        val timestamps = onsetEvents.map { it.timeSeconds }
        val intervals  = timestamps
            .zipWithNext { a, b -> b - a }
            .filter { it >= MIN_INTER_ONSET_SEC }

        if (intervals.isEmpty()) return null

        val sortedIntervals = intervals.sorted()
        val median = sortedIntervals[sortedIntervals.size / 2]
        val bpm    = (60f / median).coerceIn(MIN_BPM, MAX_BPM)

        // Salience-based first beat selection
        val sortedSaliences   = onsetEvents.map { it.salience }.sorted()
        val medianSalience    = sortedSaliences[sortedSaliences.size / 2]
        val salienceThreshold = maxOf(medianSalience, MIN_STRONG_ONSET_SALIENCE)

        val firstStrongOnset = onsetEvents.firstOrNull { it.salience >= salienceThreshold }
            ?: onsetEvents.first()

        // Re-add the intro skip offset so the timestamp is correct in the full track
        val firstBeatMs = ((firstStrongOnset.timeSeconds + skippedSeconds) * 1000f).toLong()

        Log.d(TAG, "BPM=$bpm firstBeat=${firstBeatMs}ms " +
                "(salience=${String.format("%.2f", firstStrongOnset.salience)}, " +
                "threshold=${String.format("%.2f", salienceThreshold)}, " +
                "skipped=${skippedSeconds}s, onsets=${onsetEvents.size}, " +
                "kWeightedRms=${String.format("%.4f", amplitude)})")

        return BpmAnalysisResult(bpm = bpm, firstBeatMs = firstBeatMs, amplitude = amplitude)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}