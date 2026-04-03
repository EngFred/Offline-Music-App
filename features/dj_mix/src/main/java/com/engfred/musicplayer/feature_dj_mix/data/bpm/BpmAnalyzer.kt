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
 * ── Analysis pipeline ────────────────────────────────────────────────────────
 *
 * 1. Decode up to [MAX_ANALYSIS_DURATION_MS] of PCM via MediaCodec.
 * 2. Mix to mono (if stereo).
 * 3. Compute K-weighted amplitude (EBU R128) and waveform envelope from the
 *    FULL decoded PCM so they represent the whole track, not just the
 *    analysed portion.
 * 4. Detect the musical onset via [detectOnsetOffset]:
 *      • Scan 50 ms RMS windows forward.
 *      • Return the byte offset of the first window whose RMS ≥ 20 % of the
 *        track peak and that threshold is sustained for 250 ms.
 *      • Back up one window to preserve the attack transient.
 *    This replaces the old hard "skip 15 seconds" constant which was wrong
 *    for tracks that start immediately and still wrong for long ambient intros.
 * 5. Pass the onset-trimmed PCM to the native aubio beat tracker.
 * 6. The native layer returns beat-0 (extrapolated backward from the first
 *    converged beat — see aubio_bridge.c), not beats[WARMUP_BEATS].
 * 7. [snapToNearestOnset] validates the returned position against local PCM
 *    energy. If the position lands in a transient gap (< 30 % of local peak),
 *    the nearest louder frame within ±½ beat is used instead.
 * 8. Add the onset-skip offset back to map beat-0 into full-track time.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BpmAnalyzer"

        /** Decode at most this many ms — enough for reliable BPM even on long tracks. */
        private const val MAX_ANALYSIS_DURATION_MS = 90_000L

        /** Clamp the native result to a sane DJ range. */
        private const val MIN_BPM = 55f
        private const val MAX_BPM = 215f

        // ── Onset detection parameters ────────────────────────────────────────

        /** RMS window size used by [detectOnsetOffset], in seconds. */
        private const val ONSET_WINDOW_SEC = 0.050f      // 50 ms

        /**
         * Fraction of the track's peak RMS that a window must exceed to be
         * considered "musically active".
         */
        private const val ONSET_ENERGY_THRESHOLD = 0.20f // 20 % of peak

        /**
         * Minimum duration (in consecutive windows) the energy must remain above
         * [ONSET_ENERGY_THRESHOLD] before we declare an onset.
         * 5 × 50 ms = 250 ms.
         */
        private const val ONSET_SUSTAIN_WINDOWS = 5

        // ── Beat-snap parameters ──────────────────────────────────────────────

        /** Window width used by [snapToNearestOnset] to measure local RMS. */
        private const val SNAP_WINDOW_MS = 20L

        /**
         * Step size for the ±½-beat search in [snapToNearestOnset].
         * Finer than ½ a hop (≈12 ms), coarse enough to stay cheap.
         */
        private const val SNAP_STEP_MS = 10L

        /**
         * If the candidate frame's RMS is below this fraction of the local
         * maximum, we consider it a gap and snap to the loudest nearby frame.
         */
        private const val SNAP_SILENCE_RATIO = 0.30f
    }

    data class BpmAnalysisResult(
        val bpm: Float,
        val firstBeatMs: Long,
        val amplitude: Float,
        val waveformEnvelope: FloatArray = FloatArray(0)
    )

    init {
        System.loadLibrary("bpm_analyzer")
    }

    private external fun analyzeBeatsNative(monoSamples: FloatArray, sampleRate: Int): FloatArray?

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun analyzeBpm(uri: Uri): BpmAnalysisResult? = withContext(Dispatchers.IO) {
        try {
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri)
                ?: return@withContext null

            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            // ── Steps 3 & 4: amplitude + envelope use FULL decoded PCM ──────────
            // Done BEFORE the onset skip so they cover the whole track, not just
            // the analysed portion. The waveform visualiser and auto-gain both
            // need to reflect the complete audio content from start to finish.
            val amplitude        = calculateKWeightedAmplitude(monoBytes, sampleRate)
            val waveformEnvelope = computeWaveformEnvelope(monoBytes)

            // ── Step 5: Energy-onset intro detection ────────────────────────────
            // Replaces the old hard ANALYSIS_SKIP_SECONDS = 15f constant.
            val onsetSkipBytes = detectOnsetOffset(monoBytes, sampleRate)

            val (analysisBytes, effectiveSkipSeconds) = when {
                onsetSkipBytes <= 0 -> {
                    Log.d(TAG, "No ambient intro detected — analysing from start")
                    monoBytes to 0f
                }
                monoBytes.size > onsetSkipBytes * 2 -> {
                    val secs = onsetSkipBytes / 2f / sampleRate
                    Log.d(TAG, "Onset skip: ${(secs * 1000).toInt()} ms (${onsetSkipBytes} bytes)")
                    monoBytes.copyOfRange(onsetSkipBytes, monoBytes.size) to secs
                }
                else -> {
                    Log.d(TAG, "Track too short for onset skip — analysing from start")
                    monoBytes to 0f
                }
            }

            // ── Step 6: Convert trimmed bytes → float[] for native ──────────────
            val numSamples   = analysisBytes.size / 2
            val floatSamples = FloatArray(numSamples)
            val buf          = ByteBuffer.wrap(analysisBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floatSamples[i] = buf.short.toFloat() / Short.MAX_VALUE
            }

            // ── Step 7: Native beat tracking (aubio) ────────────────────────────
            val nativeResult = analyzeBeatsNative(floatSamples, sampleRate)
                ?: run {
                    Log.w(TAG, "analyzeBeatsNative returned null for $uri")
                    return@withContext null
                }

            val bpm                 = nativeResult[0].coerceIn(MIN_BPM, MAX_BPM)
            // beat-0 position relative to the START of analysisBytes (intro-skipped segment).
            // The native layer already performed the backward extrapolation; this is NOT
            // beats[WARMUP_BEATS] but the phase-corrected beat-0 position.
            val beat0RelativeMs     = nativeResult[1].toLong().coerceAtLeast(0L)
            val confidence          = nativeResult[2]

            // ── Step 8: Beat-snap validation ────────────────────────────────────
            // Verify the native beat-0 position lands on real PCM energy.
            // If it fell into a transient gap, search ±½ beat for the nearest onset.
            val halfBeatMs = (30_000f / bpm).toLong()   // half a beat in ms
            val snappedRelativeMs = snapToNearestOnset(
                candidateMs  = beat0RelativeMs,
                halfBeatMs   = halfBeatMs,
                monoBytes    = analysisBytes,
                sampleRate   = sampleRate
            )

            // ── Step 9: Map back to full-track time ─────────────────────────────
            val firstBeatMs = snappedRelativeMs + (effectiveSkipSeconds * 1000f).toLong()

            Log.d(TAG,
                "BPM=$bpm " +
                        "beat0_native=${beat0RelativeMs}ms " +
                        "beat0_snapped=${snappedRelativeMs}ms " +
                        "skipOffset=${(effectiveSkipSeconds * 1000f).toInt()}ms " +
                        "firstBeat_track=${firstBeatMs}ms " +
                        "confidence=${String.format("%.3f", confidence)} " +
                        "kRms=${String.format("%.4f", amplitude)}"
            )

            BpmAnalysisResult(
                bpm              = bpm,
                firstBeatMs      = firstBeatMs,
                amplitude        = amplitude,
                waveformEnvelope = waveformEnvelope
            )

        } catch (e: Exception) {
            Log.e(TAG, "BPM analysis failed for $uri", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ONSET DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Finds the byte offset in [monoBytes] where the musical content begins,
     * using short-term RMS energy.
     *
     * Algorithm:
     *  1. Divide [monoBytes] into [ONSET_WINDOW_SEC]-wide RMS windows.
     *  2. Compute the per-window RMS and the global peak RMS.
     *  3. Scan forward. The first window whose RMS ≥ [ONSET_ENERGY_THRESHOLD] × peak,
     *     sustained for [ONSET_SUSTAIN_WINDOWS] consecutive windows (250 ms), marks
     *     the onset.
     *  4. Return the byte offset of the window ONE before the onset (to preserve
     *     the attack transient). If no onset is found, return 0.
     *
     * Returns a byte offset that is always aligned to a 2-byte sample boundary.
     *
     * @param monoBytes  16-bit little-endian mono PCM.
     * @param sampleRate Sample rate of [monoBytes].
     * @return Byte offset into [monoBytes] to start analysis from, or 0.
     */
    private fun detectOnsetOffset(monoBytes: ByteArray, sampleRate: Int): Int {
        val windowSamples  = (sampleRate * ONSET_WINDOW_SEC).toInt()
        val windowBytes    = windowSamples * 2
        val minTotalBytes  = windowBytes * (ONSET_SUSTAIN_WINDOWS + 2)

        if (monoBytes.size < minTotalBytes) {
            Log.d(TAG, "detectOnsetOffset: track too short for onset scan — returning 0")
            return 0
        }

        val numWindows = monoBytes.size / windowBytes
        val rms        = FloatArray(numWindows)

        // ── Pass 1: compute per-window RMS ──────────────────────────────────
        for (w in 0 until numWindows) {
            val offset = w * windowBytes
            val bbuf   = ByteBuffer.wrap(monoBytes, offset, windowBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            var sumSq  = 0.0
            repeat(windowSamples) {
                val s = bbuf.short.toFloat() / Short.MAX_VALUE
                sumSq += s * s
            }
            rms[w] = sqrt(sumSq / windowSamples).toFloat()
        }

        val peakRms = rms.maxOrNull() ?: return 0
        if (peakRms == 0f) return 0
        val threshold = peakRms * ONSET_ENERGY_THRESHOLD

        Log.d(TAG,
            "detectOnsetOffset: peakRms=${String.format("%.4f", peakRms)} " +
                    "threshold=${String.format("%.4f", threshold)} " +
                    "windows=$numWindows (${numWindows * ONSET_WINDOW_SEC * 1000}ms)"
        )

        // ── Pass 2: find first sustained threshold crossing ──────────────────
        val scanLimit = numWindows - ONSET_SUSTAIN_WINDOWS
        for (w in 0 until scanLimit) {
            val allAbove = (0 until ONSET_SUSTAIN_WINDOWS).all { offset ->
                rms[w + offset] >= threshold
            }
            if (allAbove) {
                // Back up one window to preserve the attack
                val onsetWindow    = maxOf(0, w - 1)
                val onsetByteOffset = onsetWindow * windowBytes

                Log.d(TAG,
                    "detectOnsetOffset: onset at window $w " +
                            "(${w * ONSET_WINDOW_SEC * 1000}ms), " +
                            "backed up to window $onsetWindow " +
                            "(${onsetWindow * ONSET_WINDOW_SEC * 1000}ms)"
                )
                return onsetByteOffset
            }
        }

        Log.d(TAG, "detectOnsetOffset: no clear onset — returning 0")
        return 0
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BEAT-SNAP PASS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Validates [candidateMs] against local PCM energy and snaps it to the
     * nearest real onset if the candidate position falls in a transient gap.
     *
     * Algorithm:
     *  1. Measure the RMS in a [SNAP_WINDOW_MS]-wide window at [candidateMs].
     *  2. Find the local peak RMS across the entire ±[halfBeatMs] search zone.
     *  3. If the candidate RMS is ≥ [SNAP_SILENCE_RATIO] × local peak, the
     *     candidate is already on a real onset — return it unchanged.
     *  4. Otherwise, step through the search zone in [SNAP_STEP_MS] increments
     *     and return the ms position with the highest RMS.
     *
     * [monoBytes] must be the analysis segment (after onset skip).
     * [candidateMs] must be relative to the start of [monoBytes].
     * The returned value is also relative to [monoBytes].
     *
     * @param candidateMs  Candidate beat-0 position in ms, relative to [monoBytes].
     * @param halfBeatMs   Half a beat interval in ms (search radius).
     * @param monoBytes    16-bit LE mono PCM — the analysis segment only.
     * @param sampleRate   Sample rate of [monoBytes].
     * @return Validated (possibly snapped) beat-0 ms position relative to [monoBytes].
     */
    private fun snapToNearestOnset(
        candidateMs: Long,
        halfBeatMs:  Long,
        monoBytes:   ByteArray,
        sampleRate:  Int
    ): Long {
        val windowSamples = (sampleRate * SNAP_WINDOW_MS / 1000L).toInt().coerceAtLeast(1)
        val windowBytes   = windowSamples * 2
        val totalSamples  = monoBytes.size / 2
        val durationMs    = totalSamples.toLong() * 1000L / sampleRate

        /** Compute RMS of the [windowSamples]-wide window beginning at [ms]. */
        fun rmsAt(ms: Long): Float {
            val startSample = (ms * sampleRate / 1000L).toInt()
                .coerceIn(0, (totalSamples - windowSamples).coerceAtLeast(0))
            val startByte   = startSample * 2
            val available   = monoBytes.size - startByte
            if (available < 2) return 0f
            val readBytes = minOf(windowBytes, available)
            val bbuf      = ByteBuffer.wrap(monoBytes, startByte, readBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            var sumSq = 0.0
            var count = 0
            while (bbuf.remaining() >= 2) {
                val s = bbuf.short.toFloat() / Short.MAX_VALUE
                sumSq += s * s
                count++
            }
            return if (count > 0) sqrt(sumSq / count).toFloat() else 0f
        }

        val safeCandidate = candidateMs.coerceIn(0L, durationMs)
        val searchStart   = (safeCandidate - halfBeatMs).coerceAtLeast(0L)
        val searchEnd     = (safeCandidate + halfBeatMs)
            .coerceAtMost((durationMs - SNAP_WINDOW_MS).coerceAtLeast(0L))

        val candidateRms = rmsAt(safeCandidate)

        // ── Find local peak across the full search zone ──────────────────────
        var localPeak = candidateRms
        var t         = searchStart
        while (t <= searchEnd) {
            val r = rmsAt(t)
            if (r > localPeak) localPeak = r
            t += SNAP_STEP_MS
        }

        // Candidate is already on a real onset — accept it
        if (localPeak == 0f || candidateRms >= localPeak * SNAP_SILENCE_RATIO) {
            return safeCandidate
        }

        // ── Candidate is in a gap — find the loudest frame in the search zone
        var bestMs  = safeCandidate
        var bestRms = candidateRms
        t = searchStart
        while (t <= searchEnd) {
            val r = rmsAt(t)
            if (r > bestRms) {
                bestRms = r
                bestMs  = t
            }
            t += SNAP_STEP_MS
        }

        Log.d(TAG,
            "snapToNearestOnset: ${safeCandidate}ms → ${bestMs}ms " +
                    "(candidateRms=${String.format("%.4f", candidateRms)} " +
                    "localPeak=${String.format("%.4f", localPeak)} " +
                    "bestRms=${String.format("%.4f", bestRms)})"
        )

        return bestMs
    }

    // ═════════════════════════════════════════════════════════════════════════
    // K-WEIGHTED AMPLITUDE  (EBU R128 / ITU-R BS.1770-4)
    // ═════════════════════════════════════════════════════════════════════════

    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    private fun designHighShelf(fc: Double, gainDb: Double, sampleRate: Double): BiquadCoeffs {
        val A          = Math.pow(10.0, gainDb / 40.0)
        val w0         = 2.0 * Math.PI * fc / sampleRate
        val cosW0      = Math.cos(w0)
        val sinW0      = Math.sin(w0)
        val sqrtA      = Math.sqrt(A)
        val alpha      = sinW0 * 0.7071067811865476
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha

        val b0 = A  * ((A + 1) + (A - 1) * cosW0 + twoSqrtAAlpha)
        val b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0)
        val b2 = A  * ((A + 1) + (A - 1) * cosW0 - twoSqrtAAlpha)
        val a0 =       (A + 1) - (A - 1) * cosW0 + twoSqrtAAlpha
        val a1 = 2.0 * ((A - 1) - (A + 1) * cosW0)
        val a2 =       (A + 1) - (A - 1) * cosW0 - twoSqrtAAlpha

        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

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

    // ═════════════════════════════════════════════════════════════════════════
    // WAVEFORM ENVELOPE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Computes a 128-element normalised RMS amplitude envelope from mono PCM bytes.
     *
     * Each bar is the RMS of (numSamples/128) consecutive 16-bit samples.
     * The array is normalised so the loudest bar = 1.0.
     *
     * [monoBytes] MUST be the full decoded PCM (before onset skip) so the
     * envelope represents the entire track for the DJ scrubber.
     *
     * Note: [MAX_ANALYSIS_DURATION_MS] caps decoding at 90 s, so on tracks
     * longer than 90 s the envelope covers only the first 90 s. This is a
     * deliberate trade-off — decoding the full file would be prohibitively
     * slow for a background worker scanning a large library.
     */
    private fun computeWaveformEnvelope(
        monoBytes: ByteArray,
        numBars:   Int = 128
    ): FloatArray {
        if (monoBytes.size < 2) return FloatArray(numBars) { 0.1f }

        val numSamples    = monoBytes.size / 2
        val samplesPerBar = (numSamples.toFloat() / numBars).toInt().coerceAtLeast(1)
        val buf           = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN)
        val rawEnvelope   = FloatArray(numBars)
        var maxRms        = 0f

        for (bar in 0 until numBars) {
            var sumSq = 0.0
            var count = 0
            while (count < samplesPerBar && buf.hasRemaining()) {
                val sample = buf.short.toFloat() / Short.MAX_VALUE
                sumSq += sample * sample
                count++
            }
            if (count > 0) {
                val rms = sqrt(sumSq / count).toFloat()
                rawEnvelope[bar] = rms
                if (rms > maxRms) maxRms = rms
            }
        }

        return if (maxRms > 0f) {
            FloatArray(numBars) { i -> (rawEnvelope[i] / maxRms).coerceIn(0f, 1f) }
        } else {
            FloatArray(numBars) { 0.1f }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PCM DECODING
    // ═════════════════════════════════════════════════════════════════════════

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