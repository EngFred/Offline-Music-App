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
 *    FULL decoded PCM — these always reflect the initial decode window.
 * 4. Detect the musical onset via [detectOnsetOffset]:
 *      • Scan 50 ms RMS windows forward.
 *      • Return the byte offset of the first window whose RMS ≥ 20% of peak
 *        AND energy is visibly rising (≥ 120% of energy 200ms prior).
 *        The rise check filters sustained speech/ambient from real musical onsets.
 *      • Sustain must hold for 250 ms.
 *      • Back up one window to preserve the attack transient.
 *
 * 5. LATE ONSET DETECTION (new):
 *      If the onset lands in the last [LATE_ONSET_THRESHOLD] fraction of the
 *      decoded window (i.e. audio is mostly intro/speech within 90s), re-decode
 *      up to [EXTENDED_ANALYSIS_DURATION_MS] and re-run onset detection.
 *      This covers the "Havana-style" scenario: YouTube rips with 1-2 minutes
 *      of acting/speech before the actual song begins.
 *
 * 6. Pass the onset-trimmed PCM to the native aubio beat tracker.
 * 7. CONFIDENCE GATING (new):
 *      If aubio's confidence < [CONFIDENCE_THRESHOLD], set firstBeatMs = 0.
 *      The BPM itself is still cached (useful for queue ordering), but we do
 *      not trust a low-confidence cue point — starting from position 0 is
 *      safer than starting mid-speech 28 seconds in.
 * 8. [snapToNearestOnset] validates the beat-0 position against local PCM energy.
 * 9. Add the onset-skip offset back to map beat-0 into full-track time.
 * 10. FIRST-BEAT GUARD (fix — Issue #1):
 *      If the resulting cue point is earlier than [FIRST_BEAT_MIN_OFFSET_MS]
 *      (20 s), phase-advance it by whole beat intervals until it clears the
 *      window. Beat grids are periodic, so phase alignment is preserved exactly.
 *      This prevents the engine from cueing a track from its very first bar,
 *      which sounds abrupt on a real dance floor — a DJ would always start the
 *      incoming track a good distance into the intro.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BpmAnalyzer"

        /** Standard analysis window — covers the vast majority of tracks. */
        private const val MAX_ANALYSIS_DURATION_MS = 90_000L

        /**
         * Extended analysis window used when a late onset is detected.
         * 150s covers intros up to ~2.5 minutes (e.g. YouTube rips with
         * acting/dialogue before the song begins).
         * Only triggered when onset > [LATE_ONSET_THRESHOLD] × decoded window,
         * so normal tracks are not affected.
         */
        private const val EXTENDED_ANALYSIS_DURATION_MS = 150_000L

        /** Clamp the native result to a sane DJ range. */
        private const val MIN_BPM = 55f
        private const val MAX_BPM = 215f

        // ── Onset detection parameters ────────────────────────────────────────

        /** RMS window size used by [detectOnsetOffset], in seconds. */
        private const val ONSET_WINDOW_SEC = 0.050f // 50 ms

        /**
         * Fraction of the track's peak RMS that a window must exceed to be
         * considered "musically active".
         */
        private const val ONSET_ENERGY_THRESHOLD = 0.20f // 20% of peak

        /**
         * Minimum duration (in consecutive windows) the energy must remain above
         * [ONSET_ENERGY_THRESHOLD] before we declare an onset.
         * 5 × 50 ms = 250 ms.
         */
        private const val ONSET_SUSTAIN_WINDOWS = 5

        /**
         * Energy-rise ratio for onset validation.
         *
         * A candidate onset window must have RMS ≥ this multiple of the RMS
         * measured 200ms earlier (4 windows back). This single check is highly
         * effective at rejecting sustained speech and ambient sound, which have
         * relatively flat energy profiles, while accepting real musical onsets
         * (drum hits, bass drops) that arrive with a sharp energy increase.
         *
         * A value of 1.20 means "energy must have grown by at least 20% in the
         * 200ms leading up to the onset". This is deliberately conservative to
         * avoid rejecting gradual music fade-ins.
         */
        private const val ONSET_RISE_RATIO = 1.20f

        /**
         * If the onset is detected this far into the decoded window (as a fraction
         * of total decoded bytes), trigger a re-decode with the extended window.
         *
         * 0.72 = onset in the last 28% of decoded audio → likely a long intro
         * that has pushed the real music toward or beyond the analysis boundary.
         * Example: 90s window × 0.72 = onset after 64.8s. Anything later than
         * that warrants an extended re-analysis.
         */
        private const val LATE_ONSET_THRESHOLD = 0.72f

        /**
         * Minimum aubio confidence score required to trust the firstBeatMs cue.
         *
         * aubio returns a confidence value in [0, 1]. Below 0.30, the beat
         * tracker likely converged on noise, speech, or very sparse transients
         * rather than a real rhythmic pulse. In that case we keep the BPM estimate
         * (still useful for queue ordering) but zero out firstBeatMs so playback
         * starts from position 0 rather than an unreliable cue point.
         */
        private const val CONFIDENCE_THRESHOLD = 0.30f

        // ── Beat-snap parameters ──────────────────────────────────────────────

        /** Window width used by [snapToNearestOnset] to measure local RMS. */
        private const val SNAP_WINDOW_MS = 20L

        /**
         * Step size for the ±½-beat search in [snapToNearestOnset].
         */
        private const val SNAP_STEP_MS = 10L

        /**
         * If the candidate frame's RMS is below this fraction of the local
         * maximum, we consider it a gap and snap to the loudest nearby frame.
         */
        private const val SNAP_SILENCE_RATIO = 0.30f

        // ── First-beat guard (Issue #1 fix) ───────────────────────────────────

        /**
         * Minimum position (ms) for the first-beat cue point returned by
         * [analyzeBpm].
         *
         * aubio's beat tracker converges quickly and often extrapolates beat-0
         * to the very first seconds of a track. While technically correct, a
         * real DJ would never cue an incoming track from bar 1 — they want a
         * position well into the intro so the listener hears the track "arriving"
         * naturally rather than being slammed in from the top.
         *
         * If the computed [firstBeatMs] is earlier than this threshold it is
         * phase-advanced by whole beat intervals until it clears the window.
         * Because the beat grid is periodic (beat at T ≡ beat at T + N×interval),
         * advancing by N intervals preserves phase alignment exactly — the
         * CrossfadeEngine's phase-seek and beat-snap logic remains correct.
         *
         * 20 000 ms (20 s) gives the outgoing track room to breathe before the
         * incoming track's first audible bar is cued, which matches typical
         * professional DJ practice for long intros.
         *
         * NOTE: This guard is applied only when confidence is high enough to
         * trust firstBeatMs (see [CONFIDENCE_THRESHOLD]). Low-confidence results
         * already fall back to firstBeatMs = 0 and are unaffected.
         */
//        private const val FIRST_BEAT_MIN_OFFSET_MS = 20_000L
        private const val FIRST_BEAT_MIN_OFFSET_MS = 15_000L
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
            // ── Step 1: Initial decode (up to MAX_ANALYSIS_DURATION_MS) ─────────
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri)
                ?: return@withContext null

            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            // ── Steps 2 & 3: Amplitude + envelope from FULL initial PCM ─────────
            // These are computed BEFORE onset trimming and BEFORE any extended
            // re-decode. They represent the first MAX_ANALYSIS_DURATION_MS of the
            // track, which is the most musically dense portion and the correct
            // source for the waveform visualiser and auto-gain.
            val amplitude        = calculateKWeightedAmplitude(monoBytes, sampleRate)
            val waveformEnvelope = computeWaveformEnvelope(monoBytes)

            // ── Step 4: Initial onset detection ─────────────────────────────────
            var onsetSkipBytes = detectOnsetOffset(monoBytes, sampleRate)

            // ── Step 5: Late-onset check → extended re-decode if needed ──────────
            //
            // If the onset is in the last LATE_ONSET_THRESHOLD fraction of the
            // decoded audio, the real music likely starts close to or beyond the
            // 90-second boundary. Re-decode with an extended window and re-run
            // onset detection on the longer PCM.
            //
            // The amplitude and waveformEnvelope are deliberately NOT recomputed
            // from the extended decode — the initial window is fine for those.
            val analysisMonoBytes: ByteArray = run {
                val isLateOnset = onsetSkipBytes > 0 &&
                        (onsetSkipBytes.toFloat() / monoBytes.size) >= LATE_ONSET_THRESHOLD

                if (isLateOnset) {
                    val onsetMs = (onsetSkipBytes.toLong() / 2L * 1000L / sampleRate)
                    Log.d(TAG,
                        "Late onset at ~${onsetMs}ms " +
                                "(${(onsetSkipBytes.toFloat() / monoBytes.size * 100).toInt()}% of window) — " +
                                "re-decoding with extended ${EXTENDED_ANALYSIS_DURATION_MS / 1000}s window"
                    )

                    val extended = decodeToPcm(uri, EXTENDED_ANALYSIS_DURATION_MS)
                    if (extended != null) {
                        val (extPcm, _, extChannelCount) = extended
                        val extMono = if (extChannelCount > 1) mixToMono(extPcm, extChannelCount) else extPcm
                        val extOnset = detectOnsetOffset(extMono, sampleRate)
                        val extOnsetMs = if (extOnset > 0) extOnset.toLong() / 2L * 1000L / sampleRate else 0L
                        Log.d(TAG, "Extended decode onset at ~${extOnsetMs}ms " +
                                "(was ~${onsetMs}ms in 90s window)")
                        onsetSkipBytes = extOnset
                        extMono
                    } else {
                        Log.w(TAG, "Extended decode failed — continuing with initial 90s window")
                        monoBytes
                    }
                } else {
                    monoBytes
                }
            }

            // ── Step 6: Apply onset skip ──────────────────────────────────────────
            val (analysisBytes, effectiveSkipSeconds) = when {
                onsetSkipBytes <= 0 -> {
                    Log.d(TAG, "No ambient intro detected — analysing from start")
                    analysisMonoBytes to 0f
                }
                analysisMonoBytes.size > onsetSkipBytes * 2 -> {
                    val secs = onsetSkipBytes / 2f / sampleRate
                    Log.d(TAG, "Onset skip: ${(secs * 1000).toInt()} ms ($onsetSkipBytes bytes)")
                    analysisMonoBytes.copyOfRange(onsetSkipBytes, analysisMonoBytes.size) to secs
                }
                else -> {
                    Log.d(TAG, "Track too short for onset skip — analysing from start")
                    analysisMonoBytes to 0f
                }
            }

            // ── Step 7: Convert trimmed bytes → float[] for native ──────────────
            val numSamples   = analysisBytes.size / 2
            val floatSamples = FloatArray(numSamples)
            val buf          = ByteBuffer.wrap(analysisBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floatSamples[i] = buf.short.toFloat() / Short.MAX_VALUE
            }

            // ── Step 8: Native beat tracking (aubio) ─────────────────────────────
            val nativeResult = analyzeBeatsNative(floatSamples, sampleRate)
                ?: run {
                    Log.w(TAG, "analyzeBeatsNative returned null for $uri")
                    return@withContext null
                }

            val bpm        = nativeResult[0].coerceIn(MIN_BPM, MAX_BPM)
            val beat0Ms    = nativeResult[1].toLong().coerceAtLeast(0L)
            val confidence = nativeResult[2]

            // ── Step 9: Confidence gating ─────────────────────────────────────────
            //
            // A low confidence score means aubio couldn't find a clear rhythmic
            // pulse — this happens on speech, ambient sound, or very sparse music.
            // We keep the BPM (still useful for rough queue ordering) but zero out
            // firstBeatMs so the track plays from the start rather than an
            // unreliable cue point deep inside intro speech.
            val isConfident = confidence >= CONFIDENCE_THRESHOLD
            if (!isConfident) {
                Log.w(TAG,
                    "Low confidence (${String.format("%.3f", confidence)}) for $uri — " +
                            "BPM=$bpm retained, firstBeatMs forced to 0"
                )
            }

            // ── Step 10: Beat-snap validation (skipped if low confidence) ────────
            val halfBeatMs = (30_000f / bpm).toLong()

            val snappedRelativeMs = if (isConfident) {
                snapToNearestOnset(
                    candidateMs = beat0Ms,
                    halfBeatMs  = halfBeatMs,
                    monoBytes   = analysisBytes,
                    sampleRate  = sampleRate
                )
            } else {
                0L
            }

            // ── Step 11: Map back to full-track time ──────────────────────────────
            val firstBeatMs = if (isConfident) {
                snappedRelativeMs + (effectiveSkipSeconds * 1000f).toLong()
            } else {
                0L
            }

            // ── Step 12: First-beat guard (Issue #1 fix) ──────────────────────────
            //
            // aubio frequently extrapolates beat-0 to the first few seconds of the
            // track. That is arithmetically correct (the beat grid IS periodic from
            // bar 1), but cueing an incoming track from second 3 sounds abrupt — a
            // real DJ always starts well into the intro.
            //
            // Fix: if firstBeatMs < FIRST_BEAT_MIN_OFFSET_MS (20 s), advance it
            // by whole beat intervals until it clears the window. Adding N × interval
            // is equivalent to choosing beat N on the same grid — phase alignment is
            // preserved exactly, so the CrossfadeEngine's phase-seek and beat-snap
            // logic continues to work without any other changes.
            //
            // The guard is skipped when:
            //   • isConfident is false → firstBeatMs is already 0 (track plays from start).
            //   • firstBeatMs is 0 → same as above (low-confidence fallback).
            //   • firstBeatMs >= FIRST_BEAT_MIN_OFFSET_MS → already past the window.
            val guardedFirstBeatMs: Long = if (isConfident && firstBeatMs in 1L until FIRST_BEAT_MIN_OFFSET_MS && bpm > 0f) {
                val beatIntervalMs = (60_000.0 / bpm).toLong().coerceAtLeast(1L)
                var adjusted = firstBeatMs
                while (adjusted < FIRST_BEAT_MIN_OFFSET_MS) adjusted += beatIntervalMs
                Log.d(TAG,
                    "First-beat guard: ${firstBeatMs}ms → ${adjusted}ms " +
                            "(threshold=${FIRST_BEAT_MIN_OFFSET_MS}ms, interval=${beatIntervalMs}ms)"
                )
                adjusted
            } else {
                firstBeatMs
            }

            Log.d(TAG,
                "BPM=$bpm " +
                        "beat0_native=${beat0Ms}ms " +
                        "beat0_snapped=${snappedRelativeMs}ms " +
                        "skipOffset=${(effectiveSkipSeconds * 1000f).toInt()}ms " +
                        "firstBeat_track=${firstBeatMs}ms " +
                        "firstBeat_guarded=${guardedFirstBeatMs}ms " +
                        "confidence=${String.format("%.3f", confidence)} " +
                        "(${if (isConfident) "trusted" else "LOW — cue zeroed"}) " +
                        "kRms=${String.format("%.4f", amplitude)}"
            )

            BpmAnalysisResult(
                bpm              = bpm,
                firstBeatMs      = guardedFirstBeatMs,
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
     * using short-term RMS energy with an energy-rise validation step.
     *
     * Algorithm:
     *  1. Divide [monoBytes] into [ONSET_WINDOW_SEC]-wide RMS windows.
     *  2. Compute the per-window RMS and the global peak RMS.
     *  3. Scan forward. The first window whose RMS ≥ [ONSET_ENERGY_THRESHOLD] × peak,
     *     sustained for [ONSET_SUSTAIN_WINDOWS] consecutive windows (250 ms), is
     *     a candidate onset.
     *  4. ENERGY RISE CHECK (new): the candidate window's RMS must be ≥
     *     [ONSET_RISE_RATIO] × the RMS 200ms earlier (4 windows back). This
     *     rejects sustained speech/ambient that crosses the threshold without any
     *     sharp energy increase.
     *  5. Return the byte offset of the window ONE before the validated onset (to
     *     preserve the attack transient). If no onset is found, return 0.
     *
     * @param monoBytes  16-bit little-endian mono PCM.
     * @param sampleRate Sample rate of [monoBytes].
     * @return Byte offset into [monoBytes] to start analysis from, or 0.
     */
    private fun detectOnsetOffset(monoBytes: ByteArray, sampleRate: Int): Int {
        val windowSamples = (sampleRate * ONSET_WINDOW_SEC).toInt()
        val windowBytes   = windowSamples * 2
        val minTotalBytes = windowBytes * (ONSET_SUSTAIN_WINDOWS + 6) // +6 for rise lookback

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
                    "windows=$numWindows (${(numWindows * ONSET_WINDOW_SEC * 1000).toLong()}ms)"
        )

        // ── Pass 2: find first sustained, energy-rising threshold crossing ───
        //
        // For each window w, we check:
        //   a) The next ONSET_SUSTAIN_WINDOWS windows are all above threshold.
        //   b) Energy at w is ≥ ONSET_RISE_RATIO × energy 4 windows earlier.
        //      (4 windows × 50ms = 200ms lookback)
        //
        // Check (b) rejects sustained speech/ambient:
        //   • A speech sentence that happens to be loud will be at a steady
        //     RMS level. w and w-4 will have similar RMS → ratio ≈ 1.0 → rejected.
        //   • A real drum hit or bass drop has a sharp attack. RMS at w will be
        //     significantly higher than RMS 200ms earlier → ratio > 1.20 → accepted.
        //
        // Edge case: w < 4 (very start of track). In that case, we skip the rise
        // check because there is no prior context — if the track truly starts with
        // music on the first beat, that is valid.
        val scanLimit = numWindows - ONSET_SUSTAIN_WINDOWS
        for (w in 0 until scanLimit) {
            val allAbove = (0 until ONSET_SUSTAIN_WINDOWS).all { offset ->
                rms[w + offset] >= threshold
            }

            if (allAbove) {
                // Energy rise validation
                val lookbackWindow = 4 // 4 × 50ms = 200ms
                val isEnergyRising = if (w < lookbackWindow) {
                    // No prior context — accept onset at track start without rise check
                    true
                } else {
                    rms[w] >= rms[w - lookbackWindow] * ONSET_RISE_RATIO
                }

                if (!isEnergyRising) {
                    // Log at first rejection to aid debugging
                    Log.d(TAG,
                        "detectOnsetOffset: candidate at window $w " +
                                "(${(w * ONSET_WINDOW_SEC * 1000).toInt()}ms) rejected — " +
                                "no energy rise (rms[w]=${String.format("%.4f", rms[w])} " +
                                "rms[w-4]=${String.format("%.4f", rms[w - lookbackWindow])} " +
                                "required×${ONSET_RISE_RATIO})"
                    )
                    continue
                }

                // Back up one window to preserve the attack transient
                val onsetWindow     = maxOf(0, w - 1)
                val onsetByteOffset = onsetWindow * windowBytes

                Log.d(TAG,
                    "detectOnsetOffset: validated onset at window $w " +
                            "(${(w * ONSET_WINDOW_SEC * 1000).toInt()}ms), " +
                            "backed up to window $onsetWindow " +
                            "(${(onsetWindow * ONSET_WINDOW_SEC * 1000).toInt()}ms)"
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

        fun rmsAt(ms: Long): Float {
            val startSample = (ms * sampleRate / 1000L).toInt()
                .coerceIn(0, (totalSamples - windowSamples).coerceAtLeast(0))
            val startByte = startSample * 2
            val available = monoBytes.size - startByte
            if (available < 2) return 0f
            val readBytes = minOf(windowBytes, available)
            val bbuf = ByteBuffer.wrap(monoBytes, startByte, readBytes).order(ByteOrder.LITTLE_ENDIAN)
            var sumSq = 0.0; var count = 0
            while (bbuf.remaining() >= 2) {
                val s = bbuf.short.toFloat() / Short.MAX_VALUE
                sumSq += s * s; count++
            }
            return if (count > 0) sqrt(sumSq / count).toFloat() else 0f
        }

        val safeCandidate = candidateMs.coerceIn(0L, durationMs)
        val searchStart   = (safeCandidate - halfBeatMs).coerceAtLeast(0L)
        val searchEnd     = (safeCandidate + halfBeatMs)
            .coerceAtMost((durationMs - SNAP_WINDOW_MS).coerceAtLeast(0L))

        val candidateRms = rmsAt(safeCandidate)

        var localPeak = candidateRms
        var t = searchStart
        while (t <= searchEnd) { val r = rmsAt(t); if (r > localPeak) localPeak = r; t += SNAP_STEP_MS }

        if (localPeak == 0f || candidateRms >= localPeak * SNAP_SILENCE_RATIO) return safeCandidate

        var bestMs = safeCandidate; var bestRms = candidateRms
        t = searchStart
        while (t <= searchEnd) {
            val r = rmsAt(t)
            if (r > bestRms) { bestRms = r; bestMs = t }
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
        var w1 = 0.0; var w2 = 0.0
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

    private fun computeWaveformEnvelope(monoBytes: ByteArray, numBars: Int = 128): FloatArray {
        if (monoBytes.size < 2) return FloatArray(numBars) { 0.1f }

        val numSamples    = monoBytes.size / 2
        val samplesPerBar = (numSamples.toFloat() / numBars).toInt().coerceAtLeast(1)
        val buf           = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN)
        val rawEnvelope   = FloatArray(numBars)
        var maxRms        = 0f

        for (bar in 0 until numBars) {
            var sumSq = 0.0; var count = 0
            while (count < samplesPerBar && buf.hasRemaining()) {
                val sample = buf.short.toFloat() / Short.MAX_VALUE
                sumSq += sample * sample; count++
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

    /**
     * Decodes PCM from [uri] up to [maxDurationMs] milliseconds.
     *
     * The [maxDurationMs] parameter defaults to [MAX_ANALYSIS_DURATION_MS] (90s)
     * for normal analysis, but can be set to [EXTENDED_ANALYSIS_DURATION_MS]
     * (150s) for the late-onset re-decode pass.
     *
     * @return Triple(pcmBytes, sampleRate, channelCount) or null on failure.
     */
    private fun decodeToPcm(
        uri: Uri,
        maxDurationMs: Long = MAX_ANALYSIS_DURATION_MS
    ): Triple<ByteArray, Int, Int>? {
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
                            extractor.sampleTime / 1_000 >= maxDurationMs -> {
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
            Log.e(TAG, "PCM decode failed for $uri (maxDurationMs=$maxDurationMs)", e)
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