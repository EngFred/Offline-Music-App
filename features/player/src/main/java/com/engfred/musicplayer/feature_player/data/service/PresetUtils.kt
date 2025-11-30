package com.engfred.musicplayer.feature_player.data.service

import android.media.audiofx.Equalizer
import android.util.Log
import com.engfred.musicplayer.core.domain.model.AudioPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Utility to apply audio presets to an Android Equalizer in a robust, device-independent way.
 *
 * Usage:
 * EqualizerPresetApplier.applyPreset(
 *   eq = equalizerInstance,
 *   scope = serviceScope,
 *   preset = AudioPreset.HIP_HOP,
 *   onApplied = { appliedPreset -> lastAppliedPreset = appliedPreset },
 *   onError = { t -> Log.w(TAG, "EQ error", t) }
 * )
 */
object EqualizerPresetApplier {

    private const val TAG = "EQPresetApplier"

    /**
     * Apply a preset to the provided Equalizer.
     *
     * @param eq the Equalizer instance (may be null)
     * @param scope CoroutineScope to launch background work (e.g. serviceScope)
     * @param preset desired AudioPreset
     * @param intensity multiplier for preset strength (0f = no effect, 1f = as-defined). Default 1f.
     * @param steps number of intermediate steps when animating band changes (higher = smoother)
     * @param stepDelayMs delay between steps in ms (controls animation speed)
     * @param onApplied optional callback invoked when done (on success)
     * @param onError optional callback invoked on unrecoverable errors
     */
    fun applyPreset(
        eq: Equalizer?,
        scope: CoroutineScope,
        preset: AudioPreset,
        intensity: Float = 1.0f,
        steps: Int = 8,
        stepDelayMs: Long = 30L,
        onApplied: ((AudioPreset) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        try {
            if (eq == null) {
                Log.w(TAG, "Equalizer is null; cannot apply preset: $preset")
                return
            }

            // If NONE -> disable equalizer and notify
            if (preset == AudioPreset.NONE) {
                scope.launch {
                    try {
                        eq.enabled = false
                        onApplied?.invoke(AudioPreset.NONE)
                        Log.d(TAG, "Equalizer disabled (preset=NONE)")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error disabling Equalizer: ${t.message}", t)
                        onError?.invoke(t)
                    }
                }
                return
            }

            // Ensure EQ enabled
            try {
                eq.enabled = true
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't enable Equalizer before applying levels: ${t.message}")
            }

            // Launch background job to compute and animate application
            scope.launch {
                try {
                    val bandCount = eq.numberOfBands.toInt()
                    val bandLevelRange = eq.bandLevelRange // ShortArray[min,max] in mB
                    val minLevel = bandLevelRange[0]
                    val maxLevel = bandLevelRange[1]

                    // Compute raw target levels (in millibels) per band then scale by intensity
                    val rawTargets = computePresetTargetLevels(eq, preset, bandCount, minLevel, maxLevel)
                    val scaledTargets = ShortArray(bandCount) { idx ->
                        val scaled = (rawTargets[idx].toInt() * intensity).toInt().toShort()
                        scaled.coerceIn(minLevel, maxLevel)
                    }

                    // Animate application so users perceive the change
                    animateApplyBandLevels(eq, scaledTargets, steps = steps.coerceAtLeast(1), stepDelayMs = stepDelayMs)

                    onApplied?.invoke(preset)
                    Log.d(TAG, "Applied preset: $preset (intensity=$intensity)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error applying preset $preset: ${t.message}", t)
                    onError?.invoke(t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "applyPreset top-level error: ${t.message}", t)
            onError?.invoke(t)
        }
    }

    /**
     * Compute desired band levels (in millibels) per band for the given preset.
     * This queries each band's center frequency and maps a profile function to dB then to mB.
     */
    private fun computePresetTargetLevels(
        eq: Equalizer,
        preset: AudioPreset,
        bandCount: Int,
        minLevel: Short,
        maxLevel: Short
    ): ShortArray {
        val result = ShortArray(bandCount)
        for (b in 0 until bandCount) {
            result[b] = try {
                val centerFreqMilliHz = eq.getCenterFreq(b.toShort()) // int milliHz
                val freqHz = centerFreqMilliHz / 1000f

                val desiredDb = when (preset) {
                    AudioPreset.HIP_HOP -> hipHopProfileDb(freqHz)
                    AudioPreset.ROCK -> rockProfileDb(freqHz)
                    AudioPreset.POP -> popProfileDb(freqHz)
                    AudioPreset.JAZZ -> jazzProfileDb(freqHz)
                    AudioPreset.CLASSICAL -> classicalProfileDb(freqHz)
                    AudioPreset.DANCE -> danceProfileDb(freqHz)
                    AudioPreset.NONE -> 0f
                    else -> 0f
                }

                val desiredMb = (desiredDb * 100f).toInt().toShort()
                desiredMb.coerceIn(minLevel, maxLevel)
            } catch (t: Throwable) {
                Log.w(TAG, "computePresetTargetLevels: error for band $b: ${t.message}")
                0
            }
        }
        return result
    }

    /**
     * Animate current band levels -> targetLevels in small discrete steps to make the change audible.
     * Uses suspend-friendly delay (call from coroutine).
     */
    private suspend fun animateApplyBandLevels(
        eq: Equalizer,
        targetLevels: ShortArray,
        steps: Int = 6,
        stepDelayMs: Long = 40L
    ) {
        val bandCount = targetLevels.size
        val current = ShortArray(bandCount)

        for (b in 0 until bandCount) {
            current[b] = try {
                eq.getBandLevel(b.toShort())
            } catch (t: Throwable) {
                0
            }
        }

        for (step in 1..steps) {
            val fraction = step.toFloat() / steps.toFloat()
            for (b in 0 until bandCount) {
                try {
                    val start = current[b].toInt()
                    val end = targetLevels[b].toInt()
                    val value = (start + ((end - start) * fraction)).toInt().toShort()
                    eq.setBandLevel(b.toShort(), value)
                } catch (t: Throwable) {
                    Log.w(TAG, "animateApplyBandLevels: setBandLevel failed (b=$b): ${t.message}")
                }
            }
            delay(stepDelayMs)
        }

        // ensure exact final target
        for (b in 0 until bandCount) {
            try {
                eq.setBandLevel(b.toShort(), targetLevels[b])
            } catch (t: Throwable) {
                Log.w(TAG, "animateApplyBandLevels: final setBandLevel failed for band $b: ${t.message}")
            }
        }
    }

    //
    // --- Preset profile functions (return desired dB at frequency Hz)
    //     Tweak numbers if you want stronger/weaker effects.
    //

    private fun hipHopProfileDb(freqHz: Float): Float {
        return when {
            freqHz <= 60f -> +6.5f
            freqHz <= 140f -> +5.0f
            freqHz <= 400f -> +1.5f
            freqHz <= 2000f -> -1.0f
            freqHz <= 6000f -> +1.0f
            else -> +1.5f
        }
    }

    private fun jazzProfileDb(freqHz: Float): Float {
        return when {
            freqHz <= 80f -> +1.5f
            freqHz <= 300f -> +2.0f
            freqHz <= 1200f -> +3.0f
            freqHz <= 5000f -> +1.5f
            else -> +0.8f
        }
    }

    private fun classicalProfileDb(freqHz: Float): Float {
        return when {
            freqHz <= 100f -> -2.0f
            freqHz <= 400f -> -0.5f
            freqHz <= 2000f -> +0.5f
            freqHz <= 8000f -> +2.0f
            else -> +3.0f
        }
    }

    private fun rockProfileDb(freqHz: Float): Float {
        return when {
            freqHz <= 100f -> +4.0f
            freqHz <= 300f -> +2.0f
            freqHz <= 1500f -> 0f
            freqHz <= 6000f -> +1.5f
            else -> +2.5f
        }
    }

    private fun popProfileDb(freqHz: Float): Float {
        return when {
            freqHz <= 90f -> +1.5f
            freqHz <= 400f -> +1.0f
            freqHz <= 3000f -> +2.5f
            freqHz <= 8000f -> +2.0f
            else -> +1.0f
        }
    }

    private fun danceProfileDb(freqHz: Float): Float {
        return when {
            freqHz <= 80f -> +7.0f
            freqHz <= 250f -> +4.0f
            freqHz <= 1200f -> +0.5f
            freqHz <= 5000f -> +2.0f
            else -> +2.5f
        }
    }
}
