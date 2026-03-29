package com.engfred.musicplayer.feature_dj_mix.data.sampler

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Generates synthesized DJ transition sounds as 16-bit mono WAV files and caches them
 * in the app's internal storage so they are only built once per install.
 *
 * All synthesis is pure Kotlin — no NDK, no external library.
 * The generated files are loaded into [SamplerEngine]'s SoundPool just like the
 * downloaded CC0 OGG assets.
 *
 * Samples produced:
 *  - RISER_SWEEP      — exponential sine sweep 300 Hz → 1 200 Hz over 2 s
 *  - REWIND_SWEEP     — downward vinyl-brake sweep 1 400 Hz → 180 Hz, 1.2 s, with pitch flutter
 *  - WHITE_NOISE_UP   — ★ PROFESSIONAL DJ RISER: pink noise + resonant LPF sweep 150 Hz → 12 kHz
 *                        + 55 Hz sub-bass swell + soft saturation (1.5 s)
 *  - WHITE_NOISE_DOWN — pink noise with falling amplitude envelope (1.5 s)
 *  - IMPACT_HIT       — 80 Hz sine burst with fast exponential decay (0.5 s)
 *  - STUTTER_HIT      — gated noise stutter with decaying envelope (0.5 s)
 *
 * ── Cache versioning ─────────────────────────────────────────────────────────
 * Bump [CACHE_VERSION] whenever any generator changes. On next launch the engine
 * detects the version mismatch, deletes stale files, and re-synthesizes fresh ones.
 */
@Singleton
class SynthesizedSampleGenerator @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG          = "SynthSampleGen"
        private const val SAMPLE_RATE  = 44100
        private const val BIT_DEPTH    = 16
        private const val CHANNELS     = 1
        private const val WAV_HEADER   = 44 // bytes

        /**
         * Increment this whenever any generator is changed.
         * Old cached files are deleted automatically on the next [getOrGenerate] call.
         */
        private const val CACHE_VERSION = 2
    }

    /** Internal cache directory: <filesDir>/synth_samples/ */
    private val cacheDir = File(context.filesDir, "synth_samples").also { it.mkdirs() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the WAV [File] for [sampleId], generating and caching it on first call.
     * Subsequent calls return the cached file immediately (no re-generation).
     *
     * Must be called from a background thread (IO/Default).
     */
    fun getOrGenerate(sampleId: SampleId): File {
        val file = versionedFile(sampleId)

        // Purge any stale files from older cache versions for this sampleId.
        purgeStaleCacheFiles(sampleId, currentFile = file)

        if (file.exists() && file.length() > WAV_HEADER) {
            Log.d(TAG, "Cache hit: ${file.name}")
            return file
        }

        Log.d(TAG, "Generating v$CACHE_VERSION: ${sampleId.name}")
        val pcm = generatePcm(sampleId)
        writeWav(file, pcm)
        Log.d(TAG, "Done: ${file.name} (${file.length()} bytes)")
        return file
    }

    // ── Cache helpers ─────────────────────────────────────────────────────────

    private fun versionedFile(sampleId: SampleId): File =
        File(cacheDir, "${sampleId.name.lowercase()}_v$CACHE_VERSION.wav")

    /**
     * Deletes any cached WAV files for [sampleId] that belong to a previous
     * cache version (i.e. same sample name prefix but a different version suffix).
     */
    private fun purgeStaleCacheFiles(sampleId: SampleId, currentFile: File) {
        val prefix = "${sampleId.name.lowercase()}_v"
        cacheDir.listFiles { f ->
            f.name.startsWith(prefix) && f != currentFile
        }?.forEach { stale ->
            if (stale.delete()) Log.d(TAG, "Purged stale cache: ${stale.name}")
        }
    }

    // ── PCM dispatch ──────────────────────────────────────────────────────────

    private fun generatePcm(sampleId: SampleId): ShortArray = when (sampleId) {
        SampleId.RISER_SWEEP      -> generateRiserSweep()
        SampleId.REWIND_SWEEP     -> generateRewindSweep()
        SampleId.WHITE_NOISE_UP   -> generateDjRiserNoise()          // ★ replaced
        SampleId.WHITE_NOISE_DOWN -> generateWhiteNoiseDown()
        SampleId.IMPACT_HIT       -> generateImpactHit()
        SampleId.STUTTER_HIT      -> generateStutterHit()
        else -> ShortArray(0).also { Log.w(TAG, "No PCM generator for ${sampleId.name}") }
    }

    // ── Generators ────────────────────────────────────────────────────────────

    /**
     * ★ PROFESSIONAL DJ RISER NOISE — replaces the old flat white-noise fade-in.
     *
     * What was wrong with the old version:
     *   White noise has equal energy at all frequencies including 4–20 kHz — exactly
     *   the range the human ear perceives as "ssss" hiss. During the quiet amplitude
     *   fade-in, low/mid frequencies fell below our perceptual threshold while highs
     *   remained audible, producing a sibilant glitch sound. Users thought the audio
     *   was broken.
     *
     * What a real DJ riser does:
     *   1. PINK NOISE base  — 1/f spectrum, rolls off 3 dB/octave as frequency rises.
     *      Sounds like wind, crowd rumble, or a waterfall. Warm, not harsh.
     *      Generated via Paul Kellett's 7-state IIR approximation (pure arithmetic).
     *
     *   2. RESONANT LOW-PASS FILTER SWEEP — biquad LPF whose cutoff exponentially
     *      sweeps from 150 Hz (dark, sub-only) → 12 kHz (fully open) over 1.5 s.
     *      Q = 0.9 adds a subtle resonance peak at the cutoff that tracks upward,
     *      creating the iconic "whoooosh" character of professional risers.
     *      The spectrum literally opens up — the ear hears energy building.
     *
     *   3. 55 Hz SUB-BASS SINE SWELL — peaks at ~60 % through and fades out before
     *      the drop, giving the physical "chest feel" that separates pro risers from
     *      amateur ones. Inaudible on phone speakers but felt on any headphones or
     *      club rig.
     *
     *   4. AMPLITUDE ENVELOPE — starts at 35 % (never silence, so there is always
     *      body/warmth from the filtered pink noise) and rises to 100 % via a
     *      sqrt curve (perceptually linear). 10 ms attack / 50 ms release guards
     *      against digital clicks.
     *
     *   5. SOFT SATURATION (tanh) — gently limits peaks without hard clipping,
     *      adding harmonic richness that makes the sound feel "analogue" and
     *      prevents it from ever sounding like a synthesis artifact.
     *
     * Duration: 1.5 s — matches the POWER_MIX crossfade lead-in window.
     */
    private fun generateDjRiserNoise(): ShortArray {
        val totalSamples = SAMPLE_RATE * 3 / 2  // 1.5 s
        val samples      = ShortArray(totalSamples)
        val rng          = Random(42L)

        // ── Pink noise state (Paul Kellett 7-state IIR) ───────────────────────
        var pb0 = 0.0; var pb1 = 0.0; var pb2 = 0.0; var pb3 = 0.0
        var pb4 = 0.0; var pb5 = 0.0; var pb6 = 0.0

        // ── Biquad LPF state ──────────────────────────────────────────────────
        var bx1 = 0.0; var bx2 = 0.0; var by1 = 0.0; var by2 = 0.0

        // ── Sub-bass sine state ───────────────────────────────────────────────
        var subPhase  = 0.0
        val subFreq   = 55.0  // Hz — felt more than heard

        // ── Filter sweep parameters ───────────────────────────────────────────
        val startCutoff = 150.0    // Hz — dark, only sub passes through
        val endCutoff   = 12_000.0 // Hz — fully open
        val sweepRatio  = endCutoff / startCutoff
        val filterQ     = 0.90     // slight resonance — "whoosh" character

        // ── Amplitude envelope ────────────────────────────────────────────────
        val minAmp       = 0.35    // never fully silent — always has body
        val fadeInEnd    = (SAMPLE_RATE * 0.010).toInt()  // 10 ms click guard
        val fadeOutStart = totalSamples - (SAMPLE_RATE * 0.050).toInt()  // 50 ms release

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples

            // 1. Pink noise (Paul Kellett)
            val white = rng.nextGaussian().coerceIn(-1.0, 1.0)
            pb0 =  0.99886 * pb0 + white * 0.0555179
            pb1 =  0.99332 * pb1 + white * 0.0750759
            pb2 =  0.96900 * pb2 + white * 0.1538520
            pb3 =  0.86650 * pb3 + white * 0.3104856
            pb4 =  0.55000 * pb4 + white * 0.5329522
            pb5 = -0.76160 * pb5 - white * 0.0168980
            val pink = (pb0 + pb1 + pb2 + pb3 + pb4 + pb5 + pb6 + white * 0.5362) * 0.11
            pb6 = white * 0.115926

            // 2. Biquad LPF with exponentially sweeping cutoff
            //    Coefficients recomputed per-sample (acceptable in offline synthesis).
            //    Standard biquad low-pass formulas (Audio EQ Cookbook, R. Bristow-Johnson).
            val cutoff = startCutoff * sweepRatio.pow(progress)
            val w0     = 2.0 * PI * cutoff / SAMPLE_RATE
            val cosW0  = cos(w0)
            val sinW0  = sin(w0)
            val alpha  = sinW0 / (2.0 * filterQ)
            val a0inv  = 1.0 / (1.0 + alpha)
            val fb0    = (1.0 - cosW0) * 0.5 * a0inv
            val fb1    = (1.0 - cosW0) * a0inv
            val fb2    = (1.0 - cosW0) * 0.5 * a0inv
            val fa1    = -2.0 * cosW0 * a0inv
            val fa2    = (1.0 - alpha) * a0inv

            val filtered = fb0 * pink + fb1 * bx1 + fb2 * bx2 - fa1 * by1 - fa2 * by2
            bx2 = bx1; bx1 = pink; by2 = by1; by1 = filtered

            // 3. Sub-bass swell: bell curve peaks at ~60 % through, then fades
            //    sin(π * p^0.7) gives an asymmetric bell — rises quickly, decays slowly,
            //    which feels "natural" and doesn't clash with the drop.
            subPhase += 2.0 * PI * subFreq / SAMPLE_RATE
            val subEnv = sin(PI * progress.pow(0.70)) * 0.28
            val sub    = sin(subPhase) * subEnv

            // 4. Amplitude envelope: minAmp → 1.0 via perceptually linear sqrt curve
            val ampEnv = minAmp + (1.0 - minAmp) * sqrt(progress)

            // 5. Click-guard at boundaries
            val clickGuard = when {
                i < fadeInEnd    -> i.toDouble() / fadeInEnd
                i > fadeOutStart -> (totalSamples - i).toDouble() / (totalSamples - fadeOutStart)
                else             -> 1.0
            }

            // 6. Mix + soft saturation (tanh normalised so unity gain = unity out)
            val mixed     = (filtered * 0.68 + sub) * ampEnv * clickGuard
            val saturated = tanh(mixed * 1.85) / tanh(1.85)

            samples[i] = (saturated * 0.74 * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * Pink noise with a falling amplitude envelope (post-drop wind-down).
     * Uses the same Paul Kellett pink noise core as [generateDjRiserNoise] for
     * tonal consistency, but without the filter sweep or sub swell.
     */
    private fun generateWhiteNoiseDown(): ShortArray {
        val totalSamples = SAMPLE_RATE * 3 / 2  // 1.5 s
        val samples      = ShortArray(totalSamples)
        val rng          = Random(99L)

        var pb0 = 0.0; var pb1 = 0.0; var pb2 = 0.0; var pb3 = 0.0
        var pb4 = 0.0; var pb5 = 0.0; var pb6 = 0.0

        val fadeOutStart = totalSamples - (SAMPLE_RATE * 0.05).toInt()

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples
            val white    = rng.nextGaussian().coerceIn(-1.0, 1.0)
            pb0 =  0.99886 * pb0 + white * 0.0555179
            pb1 =  0.99332 * pb1 + white * 0.0750759
            pb2 =  0.96900 * pb2 + white * 0.1538520
            pb3 =  0.86650 * pb3 + white * 0.3104856
            pb4 =  0.55000 * pb4 + white * 0.5329522
            pb5 = -0.76160 * pb5 - white * 0.0168980
            val pink = (pb0 + pb1 + pb2 + pb3 + pb4 + pb5 + pb6 + white * 0.5362) * 0.11
            pb6 = white * 0.115926

            val ampEnv     = sqrt(1.0 - progress)
            val clickGuard = if (i > fadeOutStart)
                (totalSamples - i).toDouble() / (totalSamples - fadeOutStart) else 1.0

            samples[i] = (pink * ampEnv * clickGuard * 0.40 * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * Exponential sine sweep: 300 Hz → 1 200 Hz over 2 seconds.
     * Amplitude fades in over the first 200 ms and out over the last 100 ms to
     * avoid clicks.
     */
    private fun generateRiserSweep(): ShortArray {
        val totalSamples = SAMPLE_RATE * 2
        val samples      = ShortArray(totalSamples)
        val startHz      = 300.0
        val endHz        = 1_200.0
        val sweepRatio   = endHz / startHz
        val fadeInEnd    = (SAMPLE_RATE * 0.20).toInt()
        val fadeOutStart = totalSamples - (SAMPLE_RATE * 0.10).toInt()
        var phase        = 0.0

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples
            val freq     = startHz * sweepRatio.pow(progress)
            phase       += 2.0 * PI * freq / SAMPLE_RATE

            val gain = when {
                i < fadeInEnd    -> i.toDouble() / fadeInEnd
                i > fadeOutStart -> (totalSamples - i).toDouble() / (totalSamples - fadeOutStart)
                else             -> 1.0
            } * 0.65

            samples[i] = (sin(phase) * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * Downward vinyl-brake sweep: 1 400 Hz → 180 Hz over 1.2 seconds.
     *
     * Design choices that make this feel authentic:
     *  - Starts HIGH (1 400 Hz) so the ear hears a dramatic "falling" sensation.
     *  - Ends LOW (180 Hz) — not at 0 Hz — so the last resonance has some thud.
     *  - Pitch flutter (18 Hz modulation, ±4%) mimics the wow-and-flutter of a
     *    real vinyl platter slowing under brake pressure.
     *  - 20 ms attack / 80 ms release avoids any digital click at start and end.
     *  - Peak amplitude 70% to leave headroom when layered with music.
     *
     * Used exclusively for [MixStrategy.HARMONIC] — the tempo shift IS the moment;
     * a "time is reversing" sweep primes the crowd for the incoming half/double-time.
     */
    private fun generateRewindSweep(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 1.2).toInt()
        val samples      = ShortArray(totalSamples)
        val startHz      = 1_400.0
        val endHz        = 180.0
        val sweepRatio   = endHz / startHz
        val fadeInEnd    = (SAMPLE_RATE * 0.020).toInt()
        val fadeOutStart = totalSamples - (SAMPLE_RATE * 0.08).toInt()

        val flutterRateHz = 18.0
        val flutterDepth  = 0.04
        val amplitudeSag  = 0.06
        var phase         = 0.0

        for (i in 0 until totalSamples) {
            val progress    = i.toDouble() / totalSamples
            val baseFreq    = startHz * sweepRatio.pow(progress)
            val flutterPhase = 2.0 * PI * flutterRateHz * (1.0 - progress * 0.6) * i / SAMPLE_RATE
            val pitchFactor  = 1.0 + flutterDepth * sin(flutterPhase)

            phase += 2.0 * PI * (baseFreq * pitchFactor) / SAMPLE_RATE

            val sagFactor = 1.0 - amplitudeSag * sin(PI * progress)
            val gain = when {
                i < fadeInEnd    -> i.toDouble() / fadeInEnd
                i > fadeOutStart -> (totalSamples - i).toDouble() / (totalSamples - fadeOutStart)
                else             -> 1.0
            } * sagFactor * 0.70

            samples[i] = (sin(phase) * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * Low-frequency sine burst at 80 Hz with fast exponential decay.
     * Emulates a kick-like impact hit — punchy and short (0.5 s).
     */
    private fun generateImpactHit(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.5).toInt()
        val samples      = ShortArray(totalSamples)
        val freq         = 80.0
        val decayRate    = 9.0

        for (i in 0 until totalSamples) {
            val t         = i.toDouble() / SAMPLE_RATE
            val amplitude = exp(-decayRate * t)
            samples[i]    = (sin(2.0 * PI * freq * t) * amplitude * 0.85 * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * Gated noise stutter — classic DJ "machine-gun" gate effect.
     * 16 gates per second, decaying overall envelope over 0.5 s.
     */
    private fun generateStutterHit(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.5).toInt()
        val samples      = ShortArray(totalSamples)
        val gateHz       = 16.0
        val rng          = Random(42L)

        for (i in 0 until totalSamples) {
            val t        = i.toDouble() / SAMPLE_RATE
            val gateOpen = (t * gateHz).toInt() % 2 == 0
            if (gateOpen) {
                val noise    = rng.nextGaussian().coerceIn(-1.0, 1.0)
                val envelope = (1.0 - i.toDouble() / totalSamples).pow(1.5)
                samples[i]   = (noise * envelope * 0.55 * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return samples
    }

    // ── WAV writer ────────────────────────────────────────────────────────────

    /**
     * Writes [pcm] as a standard RIFF/WAVE 16-bit mono file.
     * Uses [ByteOrder.LITTLE_ENDIAN] throughout, as the WAV spec requires.
     */
    private fun writeWav(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * 2
        val byteRate  = SAMPLE_RATE * CHANNELS * (BIT_DEPTH / 8)

        val buffer = ByteBuffer.allocate(WAV_HEADER + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort((CHANNELS * BIT_DEPTH / 8).toShort())
        buffer.putShort(BIT_DEPTH.toShort())

        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        pcm.forEach { buffer.putShort(it) }

        FileOutputStream(file).use { it.write(buffer.array()) }
    }
}