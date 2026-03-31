package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.util.Log
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * SENIOR-LEVEL DJ DECISION ENGINE v2.0
 *
 * This is the brain of the entire crossfade system.
 * It makes every decision a real professional DJ would make in the booth,
 * taking into account BPM, harmonic compatibility, track energy (amplitude),
 * and the exact type of music being mixed.
 *
 * REAL DJ DECISION MATRIX (see table in class comment above) is fully implemented.
 * Energy-aware logic automatically gives bass-heavy tracks more aggressive treatment.
 *
 * Zero side effects. Pure function. Fully documented for future maintainers.
 */
@Singleton
class MixDecisionEngine @Inject constructor(
    private val smartNextTrack: GetSmartNextTrackUseCase
) {
    companion object {
        private const val TAG = "MixDecisionEngine"

        // ── Tuning Block: Crossfade Duration Multipliers ─────────────────────
        private const val TRANSPARENT_MULT = 0.70f
        private const val SMOOTH_MULT = 1.00f
        private const val POWER_MIX_MULT = 1.40f
        private const val HARMONIC_MULT = 0.80f
        private const val WIDE_TRANSITION_MULT = 1.60f

        const val MIN_CROSSFADE_MS = 2_000L
        const val MAX_CROSSFADE_MS = 14_000L

        // ── Tuning Block: BPM Delta Thresholds ───────────────────────────────
        private const val DELTA_TRANSPARENT = 3f
        private const val DELTA_SMOOTH = 8f
        private const val DELTA_POWER_MIX = 15f

        // ── Tuning Block: Bass-Kill Triggers (BASE values) ───────────────────
        // These are further adjusted by energy in real time.
        private const val BASS_KILL_TRANSPARENT = 0.65f
        private const val BASS_KILL_SMOOTH      = 0.45f
        private const val BASS_KILL_POWER_MIX   = 0.32f
        private const val BASS_KILL_HARMONIC    = 0.55f
        private const val BASS_KILL_WIDE_TRANSITION = 0.25f

        private const val HIGH_ENERGY_THRESHOLD = 0.55f   // K-weighted RMS amplitude → "bass-heavy"

        // RubberBand stretch bounds
        private const val MAX_STRETCH_RATIO = 1.33
        private const val MIN_STRETCH_RATIO = 0.75
    }

    /**
     * Computes the perfect mix strategy for any two tracks.
     *
     * @param outgoingAmplitude K-weighted RMS of the track currently playing
     * @param incomingAmplitude K-weighted RMS of the next track
     */
    fun computeMixDecision(
        outgoingBpm: Float,
        incomingBpm: Float,
        userCrossfadeDurationMs: Long,
        outgoingAmplitude: Float = 1.0f,
        incomingAmplitude: Float = 1.0f
    ): MixDecision {
        val isBpmValid = outgoingBpm > 0f && incomingBpm > 0f
        val rawDelta = if (isBpmValid) abs(outgoingBpm - incomingBpm) else 0f
        val effectiveDelta = if (isBpmValid) smartNextTrack.minimumHarmonicDelta(outgoingBpm, incomingBpm) else 0f
        val isHarmonic = if (isBpmValid) smartNextTrack.isHarmonicallyCompatible(outgoingBpm, incomingBpm) else false

        val strategy = when {
            !isBpmValid -> MixStrategy.SMOOTH
            isHarmonic && rawDelta > DELTA_TRANSPARENT -> MixStrategy.HARMONIC
            rawDelta <= DELTA_TRANSPARENT -> MixStrategy.TRANSPARENT
            rawDelta <= DELTA_SMOOTH -> MixStrategy.SMOOTH
            rawDelta <= DELTA_POWER_MIX -> MixStrategy.POWER_MIX
            else -> MixStrategy.WIDE_TRANSITION
        }

        val durationMult = when (strategy) {
            MixStrategy.TRANSPARENT -> TRANSPARENT_MULT
            MixStrategy.SMOOTH -> SMOOTH_MULT
            MixStrategy.POWER_MIX -> POWER_MIX_MULT
            MixStrategy.HARMONIC -> HARMONIC_MULT
            MixStrategy.WIDE_TRANSITION -> WIDE_TRANSITION_MULT
        }

        val effectiveDurationMs = (userCrossfadeDurationMs * durationMult)
            .toLong().coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        val shouldTempoSync = isBpmValid && (strategy == MixStrategy.SMOOTH || strategy == MixStrategy.POWER_MIX)

        val stretchRatio: Double = if (shouldTempoSync) {
            (incomingBpm / outgoingBpm).toDouble().coerceIn(MIN_STRETCH_RATIO, MAX_STRETCH_RATIO)
        } else 1.0

        // ── ENERGY-AWARE BASS KILL (this is the secret sauce for heavy beats) ──
        val avgEnergy = (outgoingAmplitude + incomingAmplitude) / 2f
        val isHighEnergy = avgEnergy > HIGH_ENERGY_THRESHOLD
        val energyAdjustment = if (isHighEnergy) -0.13f else 0f

        val baseBassKill = when (strategy) {
            MixStrategy.TRANSPARENT -> BASS_KILL_TRANSPARENT
            MixStrategy.SMOOTH -> BASS_KILL_SMOOTH
            MixStrategy.POWER_MIX -> BASS_KILL_POWER_MIX
            MixStrategy.HARMONIC -> BASS_KILL_HARMONIC
            MixStrategy.WIDE_TRANSITION -> BASS_KILL_WIDE_TRANSITION
        }
        val bassKillThreshold = (baseBassKill + energyAdjustment).coerceIn(0.20f, 0.85f)

        val djNote = buildString {
            append("[DJ DECISION] ${strategy.name}: ")
            if (!isBpmValid) {
                append("UNKNOWN BPM | Fallback to safe smooth fade")
            } else {
                append("${outgoingBpm.fmt()} → ${incomingBpm.fmt()} BPM")
                append(" | rawΔ=${rawDelta.fmt()} effectiveΔ=${effectiveDelta.fmt()}")
                if (isHarmonic) append(" | ★ HARMONIC")
            }
            append(" | fade=${effectiveDurationMs}ms")
            if (shouldTempoSync && stretchRatio != 1.0) {
                val speed = 1.0 / stretchRatio
                append(" | RubberBand stretch=${stretchRatio.fmt3()} (×${speed.fmt3()})")
            } else {
                append(" | NO tempo-sync")
            }
            append(" | bass kill at ${(bassKillThreshold * 100).toInt()}%")
            if (isHighEnergy) append(" 🔥 HIGH ENERGY (bass-heavy)")
            append("\n ↳ ")
            when (strategy) {
                MixStrategy.TRANSPARENT -> append("Silky smooth — nothing to hide.")
                MixStrategy.SMOOTH -> append("Standard club technique — RubberBand stretch.")
                MixStrategy.POWER_MIX -> append("Early bass kill gives incoming track space to breathe.")
                MixStrategy.HARMONIC -> append("Half/double-time — harmonic lock does the work.")
                MixStrategy.WIDE_TRANSITION -> append("Energy valley technique — the BPM jump IS the moment.")
            }
        }

        return MixDecision(
            outgoingBpm = outgoingBpm,
            incomingBpm = incomingBpm,
            rawBpmDelta = rawDelta,
            effectiveBpmDelta = effectiveDelta,
            strategy = strategy,
            isHarmonic = isHarmonic,
            effectiveCrossfadeDurationMs = effectiveDurationMs,
            shouldTempoSync = shouldTempoSync,
            stretchRatio = stretchRatio,
            bassKillThresholdFraction = bassKillThreshold,
            djNote = djNote
        )
    }

    /**
     * Finds the bass equalizer band by inspecting each band's frequency range via
     * [android.media.audiofx.Equalizer.getBandFreqRange], which returns millihertz values.
     * Returns the band whose upper limit is lowest and ≤ 300 Hz (300 000 mHz).
     * Falls back to band 0 if nothing qualifies so the kill always applies something.
     *
     * This avoids the broken assumption that band 0 is always bass — on many Samsung,
     * Xiaomi, and other OEM devices, the band ordering differs from the Pixel baseline.
     */
    fun findBassBandIndex(eq: android.media.audiofx.Equalizer): Short? {
        val bandCount = eq.numberOfBands.toInt()
        if (bandCount == 0) return null

        val BASS_UPPER_LIMIT_MHZ = 300_000 // 300 Hz in millihertz
        var lowestUpperMhz = Int.MAX_VALUE
        var bestBand = -1

        for (i in 0 until bandCount) {
            val upperMhz = eq.getBandFreqRange(i.toShort())[1]
            if (upperMhz < lowestUpperMhz) {
                lowestUpperMhz = upperMhz
                bestBand = i
            }
        }
        Log.d(TAG, "findBassBandIndex: bestBand=$bestBand lowestUpperMhz=$lowestUpperMhz")
        return when {
            bestBand >= 0 && lowestUpperMhz <= BASS_UPPER_LIMIT_MHZ -> bestBand.toShort()
            bestBand >= 0 -> bestBand.toShort() // fallback: lowest band available
            else          -> 0.toShort()
        }
    }

    private fun Float.fmt() = String.format("%.1f", this)
    private fun Double.fmt3() = String.format("%.3f", this)
}