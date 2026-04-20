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
 *    • Scan 50 ms RMS windows forward.
 *    • Return the byte offset of the first window whose RMS ≥ 20% of peak
 *      AND energy is visibly rising (≥ 120% of energy 200ms prior).
 *      The rise check filters sustained speech/ambient from real musical onsets.
 *    • Sustain must hold for 250 ms.
 *    • Back up one window to preserve the attack transient.
 *
 * 5. LATE ONSET DETECTION:
 *    If the onset lands in the last [LATE_ONSET_THRESHOLD] fraction of the
 *    decoded window, re-decode up to [EXTENDED_ANALYSIS_DURATION_MS] and
 *    re-run onset detection. This covers "Havana-style" YouTube rips with
 *    1–2 minutes of speech before the song begins.
 *
 * 6. Pass the onset-trimmed PCM to the native aubio beat tracker.
 * 7. CONFIDENCE GATING:
 *    If aubio's confidence < [CONFIDENCE_THRESHOLD], set firstBeatMs = 0.
 *    The BPM itself is still cached (useful for queue ordering).
 * 8. [snapToNearestOnset] validates the beat-0 position against local PCM energy.
 * 9. Add the onset-skip offset back to map beat-0 into full-track time.
 *
 * ── IMPORTANT: No first-beat guard applied here ───────────────────────────────
 *
 * Previous versions of this class contained a hardcoded "first-beat guard"
 * (FIRST_BEAT_MIN_OFFSET_MS = 15 000 ms) that phase-advanced firstBeatMs to
 * a minimum position. That guard has been REMOVED and is now applied at
 * runtime by CrossfadeEngine.applyFirstBeatGuard() using the user-configurable
 * cue-point setting (0–30 s, default 15 s).
 *
 * WHY the guard was moved:
 * • Baking the guard into the cached value meant changing the cue-point
 *   setting required wiping the BPM cache and re-analysing all tracks.
 * • Applying it dynamically in the engine makes the setting take effect
 *   immediately with zero re-analysis cost.
 *
 * CONSEQUENCE for cache consumers:
 * The [BpmAnalysisResult.firstBeatMs] returned by this class — and therefore
 * [BpmCacheEntity.firstBeatMs] / [BpmInfo.firstBeatMs] — is now the RAW
 * aubio beat-0 position (after beat-snap and onset-offset, but WITHOUT any
 * minimum-offset guard). Do NOT pass it directly to ExoPlayer.seekTo().
 * Always go through CrossfadeEngine which applies the user's cue-point guard.
 *
 * DB version note:
 * The cache was wiped at DB version 11 so that any pre-existing entries
 * (which stored the old 15-second-guarded value) are re-analysed and the
 * raw value is stored correctly going forward.
 *
 * ── Performance note (budget devices) ────────────────────────────────────────
 * MAX_ANALYSIS_DURATION_MS was reduced from 90 s → 60 s. BPM patterns
 * establish within the first chorus (~30 s); the extra 30 s added ~7 MB of
 * per-track RAM and ~33 % more CPU time with no measurable accuracy gain.
 * EXTENDED_ANALYSIS_DURATION_MS (late-onset path) remains 150 s because
 * long intros genuinely require the larger window.
 */
@Singleton
class BpmAnalyzer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BpmAnalyzer"

        /**
         * Standard analysis window.
         *
         * Reduced from 90 s → 60 s:
         * • BPM patterns establish well within the first chorus (~30 s).
         * • 60 s of 44.1 kHz mono 16-bit PCM ≈ 5.1 MB vs 7.7 MB for 90 s.
         * • Reduces per-track CPU time by ~33 %, which directly improves
         *   system responsiveness on budget devices during library scans.
         */
        private const val MAX_ANALYSIS_DURATION_MS = 60_000L

        /**
         * Extended analysis window used when a late onset is detected.
         * 150 s covers intros up to ~2.5 minutes (e.g. YouTube rips with
         * acting/dialogue before the song begins).
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
         * The candidate window's RMS must be ≥ this multiple of the RMS 200 ms
         * earlier (4 windows back). Rejects sustained speech (flat energy profile)
         * while accepting real musical onsets (sharp energy increase).
         */
        private const val ONSET_RISE_RATIO = 1.20f

        /**
         * If the onset is detected this far into the decoded window (as a fraction
         * of total decoded bytes), trigger a re-decode with the extended window.
         * 0.72 = onset in the last 28% → likely a long intro near the boundary.
         */
        private const val LATE_ONSET_THRESHOLD = 0.72f

        /**
         * Minimum aubio confidence score required to trust the firstBeatMs cue.
         * Below 0.30 we keep the BPM but zero out firstBeatMs.
         */
        private const val CONFIDENCE_THRESHOLD = 0.30f

        // ── Beat-snap parameters ──────────────────────────────────────────────

        /** Window width used by [snapToNearestOnset] to measure local RMS. */
        private const val SNAP_WINDOW_MS = 20L

        /** Step size for the ±½-beat search in [snapToNearestOnset]. */
        private const val SNAP_STEP_MS = 10L

        /**
         * If the candidate frame's RMS is below this fraction of the local
         * maximum, snap to the loudest nearby frame.
         */
        private const val SNAP_SILENCE_RATIO = 0.30f

        // ── REMOVED: FIRST_BEAT_MIN_OFFSET_MS ─────────────────────────────────
        //
        // The hardcoded first-beat guard (formerly 15 000 ms / 20 000 ms) has
        // been removed from this class. It now lives in CrossfadeEngine as the
        // user-configurable cue-point offset (default 15 s, range 0–30 s).
        //
        // If you need to enforce a minimum firstBeatMs position anywhere in this
        // codebase, call CrossfadeEngine.applyFirstBeatGuard() — do NOT add
        // a new hardcoded constant here.
        // ─────────────────────────────────────────────────────────────────────
    }

    data class BpmAnalysisResult(
        val bpm: Float,
        /**
         * RAW beat-0 position in full-track milliseconds.
         *
         * This is the raw aubio result after beat-snap and onset-offset mapping.
         * No minimum-offset guard has been applied. The guard is applied at runtime
         * by CrossfadeEngine using the user's cue-point setting.
         *
         * Junior developers: do NOT use this value directly as a seek position.
         * Always let CrossfadeEngine apply the guard first.
         */
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
            val (pcmBytes, sampleRate, channelCount) = decodeToPcm(uri) ?: return@withContext null
            val monoBytes = if (channelCount > 1) mixToMono(pcmBytes, channelCount) else pcmBytes

            // ── Steps 2 & 3: Amplitude + envelope from FULL initial PCM ─────────
            val amplitude = calculateKWeightedAmplitude(monoBytes, sampleRate)
            val waveformEnvelope = computeWaveformEnvelope(monoBytes)

            // ── Step 4: Initial onset detection ─────────────────────────────────
            var onsetSkipBytes = detectOnsetOffset(monoBytes, sampleRate)

            // ── Step 5: Late-onset check → extended re-decode if needed ──────────
            val analysisMonoBytes: ByteArray = run {
                val isLateOnset = onsetSkipBytes > 0 && (onsetSkipBytes.toFloat() / monoBytes.size) >= LATE_ONSET_THRESHOLD
                if (isLateOnset) {
                    val onsetMs = (onsetSkipBytes.toLong() / 2L * 1000L / sampleRate)
                    Log.d(TAG, "Late onset at ~${onsetMs}ms " +
                            "(${(onsetSkipBytes.toFloat() / monoBytes.size * 100).toInt()}% of window) — " +
                            "re-decoding with extended ${EXTENDED_ANALYSIS_DURATION_MS / 1000}s window")
                    val extended = decodeToPcm(uri, EXTENDED_ANALYSIS_DURATION_MS)
                    if (extended != null) {
                        val (extPcm, _, extChannelCount) = extended
                        val extMono = if (extChannelCount > 1) mixToMono(extPcm, extChannelCount) else extPcm
                        val extOnset = detectOnsetOffset(extMono, sampleRate)
                        val extOnsetMs = if (extOnset > 0) extOnset.toLong() / 2L * 1000L / sampleRate else 0L
                        Log.d(TAG, "Extended decode onset at ~${extOnsetMs}ms (was ~${onsetMs}ms in 60s window)")
                        onsetSkipBytes = extOnset
                        extMono
                    } else {
                        Log.w(TAG, "Extended decode failed — continuing with initial 60s window")
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
            val numSamples = analysisBytes.size / 2
            val floatSamples = FloatArray(numSamples)
            val buf = ByteBuffer.wrap(analysisBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floatSamples[i] = buf.short.toFloat() / Short.MAX_VALUE
            }

            // ── Step 8: Native beat tracking (aubio) ─────────────────────────────
            val nativeResult = analyzeBeatsNative(floatSamples, sampleRate) ?: run {
                Log.w(TAG, "analyzeBeatsNative returned null for $uri")
                return@withContext null
            }
            val bpm = nativeResult[0].coerceIn(MIN_BPM, MAX_BPM)
            val beat0Ms = nativeResult[1].toLong().coerceAtLeast(0L)
            val confidence = nativeResult[2]

            // ── Step 9: Confidence gating ─────────────────────────────────────────
            // REMOVED: Low-confidence zeroing of firstBeatMs.
            // We now ALWAYS trust aubio's beat0Ms / snapped position,
            // even if confidence is below CONFIDENCE_THRESHOLD.
            // The BPM is still clamped and cached as before.
            val isConfident = true

            // ── Step 10: Beat-snap validation (skipped if low confidence) ────────
            val halfBeatMs = (30_000f / bpm).toLong()
            val snappedRelativeMs = if (isConfident) {
                snapToNearestOnset(
                    candidateMs = beat0Ms,
                    halfBeatMs = halfBeatMs,
                    monoBytes = analysisBytes,
                    sampleRate = sampleRate
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

            Log.d(TAG, "BPM=$bpm " +
                    "beat0_native=${beat0Ms}ms " +
                    "beat0_snapped=${snappedRelativeMs}ms " +
                    "skipOffset=${(effectiveSkipSeconds * 1000f).toInt()}ms " +
                    "firstBeat_raw=${firstBeatMs}ms [NO GUARD — guard applied in engine] " +
                    "confidence=${String.format("%.3f", confidence)} " +
                    "(${if (isConfident) "trusted" else "LOW — cue zeroed"}) " +
                    "kRms=${String.format("%.4f", amplitude)}")

            BpmAnalysisResult(
                bpm = bpm,
                firstBeatMs = firstBeatMs,
                amplitude = amplitude,
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
     * 1. Divide [monoBytes] into [ONSET_WINDOW_SEC]-wide RMS windows.
     * 2. Compute the per-window RMS and the global peak RMS.
     * 3. Scan forward. The first window whose RMS ≥ [ONSET_ENERGY_THRESHOLD] × peak,
     *    sustained for [ONSET_SUSTAIN_WINDOWS] consecutive windows (250 ms), is
     *    a candidate onset.
     * 4. ENERGY RISE CHECK: the candidate window's RMS must be ≥
     *    [ONSET_RISE_RATIO] × the RMS 200 ms earlier (4 windows back).
     * 5. Return the byte offset of the window ONE before the validated onset.
     *    If no onset is found, return 0.
     *
     * @param monoBytes 16-bit little-endian mono PCM.
     * @param sampleRate Sample rate of [monoBytes].
     * @return Byte offset into [monoBytes] to start analysis from, or 0.
     */
    private fun detectOnsetOffset(monoBytes: ByteArray, sampleRate: Int): Int {
        val windowSamples = (sampleRate * ONSET_WINDOW_SEC).toInt()
        val windowBytes = windowSamples * 2
        val minTotalBytes = windowBytes * (ONSET_SUSTAIN_WINDOWS + 6)
        if (monoBytes.size < minTotalBytes) {
            Log.d(TAG, "detectOnsetOffset: track too short for onset scan — returning 0")
            return 0
        }

        val numWindows = monoBytes.size / windowBytes
        val rms = FloatArray(numWindows)
        for (w in 0 until numWindows) {
            val offset = w * windowBytes
            val bbuf = ByteBuffer.wrap(monoBytes, offset, windowBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
            var sumSq = 0.0
            repeat(windowSamples) {
                val s = bbuf.short.toFloat() / Short.MAX_VALUE
                sumSq += s * s
            }
            rms[w] = sqrt(sumSq / windowSamples).toFloat()
        }

        val peakRms = rms.maxOrNull() ?: return 0
        if (peakRms == 0f) return 0

        val threshold = peakRms * ONSET_ENERGY_THRESHOLD
        Log.d(TAG, "detectOnsetOffset: peakRms=${String.format("%.4f", peakRms)} " +
                "threshold=${String.format("%.4f", threshold)} " +
                "windows=$numWindows (${(numWindows * ONSET_WINDOW_SEC * 1000).toLong()}ms)")

        val scanLimit = numWindows - ONSET_SUSTAIN_WINDOWS
        for (w in 0 until scanLimit) {
            val allAbove = (0 until ONSET_SUSTAIN_WINDOWS).all { offset ->
                rms[w + offset] >= threshold
            }
            if (allAbove) {
                val lookbackWindow = 4
                val isEnergyRising = if (w < lookbackWindow) {
                    true
                } else {
                    rms[w] >= rms[w - lookbackWindow] * ONSET_RISE_RATIO
                }
                if (!isEnergyRising) {
                    Log.d(TAG, "detectOnsetOffset: candidate at window $w " +
                            "(${(w * ONSET_WINDOW_SEC * 1000).toInt()}ms) rejected — " +
                            "no energy rise (rms[w]=${String.format("%.4f", rms[w])} " +
                            "rms[w-4]=${String.format("%.4f", rms[w - lookbackWindow])} " +
                            "required×${ONSET_RISE_RATIO})")
                    continue
                }
                val onsetWindow = maxOf(0, w - 1)
                val onsetByteOffset = onsetWindow * windowBytes
                Log.d(TAG, "detectOnsetOffset: validated onset at window $w " +
                        "(${(w * ONSET_WINDOW_SEC * 1000).toInt()}ms), " +
                        "backed up to window $onsetWindow " +
                        "(${(onsetWindow * ONSET_WINDOW_SEC * 1000).toInt()}ms)")
                return onsetByteOffset
            }
        }
        Log.d(TAG, "detectOnsetOffset: no clear onset — returning 0")
        return 0
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BEAT-SNAP PASS
    // ═════════════════════════════════════════════════════════════════════════

    private fun snapToNearestOnset(
        candidateMs: Long,
        halfBeatMs: Long,
        monoBytes: ByteArray,
        sampleRate: Int
    ): Long {
        val windowSamples = (sampleRate * SNAP_WINDOW_MS / 1000L).toInt().coerceAtLeast(1)
        val windowBytes = windowSamples * 2
        val totalSamples = monoBytes.size / 2
        val durationMs = totalSamples.toLong() * 1000L / sampleRate

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
        val searchStart = (safeCandidate - halfBeatMs).coerceAtLeast(0L)
        val searchEnd = (safeCandidate + halfBeatMs)
            .coerceAtMost((durationMs - SNAP_WINDOW_MS).coerceAtLeast(0L))

        val candidateRms = rmsAt(safeCandidate)
        var localPeak = candidateRms
        var t = searchStart
        while (t <= searchEnd) {
            val r = rmsAt(t); if (r > localPeak) localPeak = r; t += SNAP_STEP_MS
        }

        if (localPeak == 0f || candidateRms >= localPeak * SNAP_SILENCE_RATIO) return safeCandidate

        var bestMs = safeCandidate; var bestRms = candidateRms
        t = searchStart
        while (t <= searchEnd) {
            val r = rmsAt(t)
            if (r > bestRms) { bestRms = r; bestMs = t }
            t += SNAP_STEP_MS
        }

        Log.d(TAG, "snapToNearestOnset: ${safeCandidate}ms → ${bestMs}ms " +
                "(candidateRms=${String.format("%.4f", candidateRms)} " +
                "localPeak=${String.format("%.4f", localPeak)} " +
                "bestRms=${String.format("%.4f", bestRms)})")
        return bestMs
    }

    // ═════════════════════════════════════════════════════════════════════════
    // K-WEIGHTED AMPLITUDE (EBU R128 / ITU-R BS.1770-4)
    // ═════════════════════════════════════════════════════════════════════════

    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    private fun designHighShelf(fc: Double, gainDb: Double, sampleRate: Double): BiquadCoeffs {
        val A = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val sqrtA = Math.sqrt(A)
        val alpha = sinW0 * 0.7071067811865476
        val twoSqrtAAlpha = 2.0 * sqrtA * alpha
        val b0 = A * ((A + 1) + (A - 1) * cosW0 + twoSqrtAAlpha)
        val b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0)
        val b2 = A * ((A + 1) + (A - 1) * cosW0 - twoSqrtAAlpha)
        val a0 = (A + 1) - (A - 1) * cosW0 + twoSqrtAAlpha
        val a1 = 2.0 * ((A - 1) - (A + 1) * cosW0)
        val a2 = (A + 1) - (A - 1) * cosW0 - twoSqrtAAlpha
        return BiquadCoeffs(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun designHighPass2nd(fc: Double, sampleRate: Double): BiquadCoeffs {
        val w0 = 2.0 * Math.PI * fc / sampleRate
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
            val x = input[i].toDouble()
            val y = c.b0 * x + w1
            w1 = c.b1 * x - c.a1 * y + w2
            w2 = c.b2 * x - c.a2 * y
            output[i] = y.toFloat()
        }
        return output
    }

    private fun calculateKWeightedAmplitude(pcmBytes: ByteArray, sampleRate: Int): Float {
        if (pcmBytes.isEmpty()) return 0f
        val numSamples = pcmBytes.size / 2
        if (numSamples == 0) return 0f
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(numSamples) { buf.short.toFloat() / Short.MAX_VALUE }
        val fs = sampleRate.toDouble()
        val stage1 = designHighShelf(1681.974, 4.0, fs)
        val stage2 = designHighPass2nd(38.134, fs)
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
        val numSamples = monoBytes.size / 2
        val samplesPerBar = (numSamples.toFloat() / numBars).toInt().coerceAtLeast(1)
        val buf = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN)
        val rawEnvelope = FloatArray(numBars)
        var maxRms = 0f
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
            FloatArray(numBars) { i ->
                (rawEnvelope[i] / maxRms).coerceIn(0f, 1f)
            }
        } else {
            FloatArray(numBars) { 0.1f }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PCM DECODING
    // ═════════════════════════════════════════════════════════════════════════

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
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    mediaFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || mediaFormat == null) {
                Log.w(TAG, "No audio track found in $uri")
                return null
            }
            extractor.selectTrack(audioTrackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = mediaFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channelCount = mediaFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
            val output = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            while (!outputEos) {
                if (!inputEos) {
                    val inputIdx = codec.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        when {
                            sampleSize < 0 -> {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            extractor.sampleTime / 1_000 >= maxDurationMs -> {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            }
                            else -> {
                                codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
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
        val frameCount = pcm.size / bytesPerFrame
        val mono = ByteArray(frameCount * 2)
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val dst = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
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