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
 *  - RISER_SWEEP    — exponential sine sweep 300 Hz → 1 200 Hz over 2 s
 *  - WHITE_NOISE_UP — white noise with rising amplitude envelope (1.5 s)
 *  - WHITE_NOISE_DOWN — white noise with falling amplitude envelope (1.5 s)
 *  - IMPACT_HIT     — 80 Hz sine burst with fast exponential decay (0.5 s)
 *  - STUTTER_HIT    — gated noise stutter with decaying envelope (0.5 s)
 */
@Singleton
class SynthesizedSampleGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG         = "SynthSampleGen"
        private const val SAMPLE_RATE = 44100
        private const val BIT_DEPTH   = 16
        private const val CHANNELS    = 1
        private const val WAV_HEADER  = 44 // bytes
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
        val file = File(cacheDir, "${sampleId.name.lowercase()}.wav")
        if (file.exists() && file.length() > WAV_HEADER) {
            Log.d(TAG, "Cache hit: ${file.name}")
            return file
        }
        Log.d(TAG, "Generating: ${sampleId.name}")
        val pcm = generatePcm(sampleId)
        writeWav(file, pcm)
        Log.d(TAG, "Done: ${file.name} (${file.length()} bytes)")
        return file
    }

    // ── PCM dispatch ──────────────────────────────────────────────────────────

    private fun generatePcm(sampleId: SampleId): ShortArray = when (sampleId) {
        SampleId.RISER_SWEEP     -> generateRiserSweep()
        SampleId.WHITE_NOISE_UP  -> generateWhiteNoise(fadingIn = true,  durationMs = 1500)
        SampleId.WHITE_NOISE_DOWN-> generateWhiteNoise(fadingIn = false, durationMs = 1500)
        SampleId.IMPACT_HIT      -> generateImpactHit()
        SampleId.STUTTER_HIT     -> generateStutterHit()
        else -> ShortArray(0).also { Log.w(TAG, "No PCM generator for ${sampleId.name}") }
    }

    // ── Generators ────────────────────────────────────────────────────────────

    /**
     * Exponential sine sweep: 300 Hz → 1 200 Hz over 2 seconds.
     * Amplitude fades in over the first 200 ms and out over the last 100 ms to
     * avoid clicks.
     */
    private fun generateRiserSweep(): ShortArray {
        val totalSamples  = SAMPLE_RATE * 2
        val samples       = ShortArray(totalSamples)
        val startHz       = 300.0
        val endHz         = 1_200.0
        val sweepRatio    = endHz / startHz
        val fadeInEnd     = (SAMPLE_RATE * 0.20).toInt()
        val fadeOutStart  = totalSamples - (SAMPLE_RATE * 0.10).toInt()
        var phase         = 0.0

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples
            val freq     = startHz * sweepRatio.pow(progress)
            phase       += 2.0 * PI * freq / SAMPLE_RATE

            val gain = when {
                i < fadeInEnd    -> i.toDouble() / fadeInEnd
                i > fadeOutStart -> (totalSamples - i).toDouble() / (totalSamples - fadeOutStart)
                else             -> 1.0
            } * 0.65 // peak at 65% to leave headroom

            samples[i] = (sin(phase) * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    /**
     * White noise with a smooth amplitude envelope.
     * [fadingIn] = true  → quiet → loud (use before the drop)
     * [fadingIn] = false → loud → quiet (use after the drop)
     * Square-root envelope gives a perceptually linear ramp.
     */
    private fun generateWhiteNoise(fadingIn: Boolean, durationMs: Int): ShortArray {
        val totalSamples = SAMPLE_RATE * durationMs / 1000
        val samples      = ShortArray(totalSamples)
        val rng          = Random(42L) // fixed seed → reproducible output

        for (i in 0 until totalSamples) {
            val progress  = i.toDouble() / totalSamples
            val amplitude = if (fadingIn) sqrt(progress) else sqrt(1.0 - progress)
            val noise     = rng.nextGaussian().coerceIn(-1.0, 1.0)
            samples[i]    = (noise * amplitude * 0.40 * Short.MAX_VALUE).toInt()
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
        val decayRate    = 9.0 // higher = shorter tail

        for (i in 0 until totalSamples) {
            val t          = i.toDouble() / SAMPLE_RATE
            val amplitude  = exp(-decayRate * t)
            samples[i]     = (sin(2.0 * PI * freq * t) * amplitude * 0.85 * Short.MAX_VALUE).toInt()
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
            val t          = i.toDouble() / SAMPLE_RATE
            val gateOpen   = (t * gateHz).toInt() % 2 == 0
            if (gateOpen) {
                val noise     = rng.nextGaussian().coerceIn(-1.0, 1.0)
                val envelope  = (1.0 - i.toDouble() / totalSamples).pow(1.5)
                samples[i]    = (noise * envelope * 0.55 * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            // Gate-closed samples stay 0 (already initialised to 0)
        }
        return samples
    }

    // ── WAV writer ────────────────────────────────────────────────────────────

    /**
     * Writes [pcm] as a standard RIFF/WAVE 16-bit mono file.
     * Uses [ByteOrder.LITTLE_ENDIAN] throughout, as the WAV spec requires.
     */
    private fun writeWav(file: File, pcm: ShortArray) {
        val dataBytes = pcm.size * 2 // 16-bit = 2 bytes per sample
        val byteRate  = SAMPLE_RATE * CHANNELS * (BIT_DEPTH / 8)

        val buffer = ByteBuffer.allocate(WAV_HEADER + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        // ── RIFF chunk descriptor ─────────────────────────────────────────────
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)          // overall file size − 8
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // ── fmt sub-chunk (16 bytes) ──────────────────────────────────────────
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)                       // sub-chunk size
        buffer.putShort(1)                      // PCM = 1
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort((CHANNELS * BIT_DEPTH / 8).toShort()) // block align
        buffer.putShort(BIT_DEPTH.toShort())

        // ── data sub-chunk ────────────────────────────────────────────────────
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        pcm.forEach { buffer.putShort(it) }

        FileOutputStream(file).use { it.write(buffer.array()) }
    }
}