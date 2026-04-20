package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.util.Log
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MixDecisionEngine @Inject constructor(
    private val smartNextTrack: GetSmartNextTrackUseCase
) {
    companion object {
        private const val TAG = "MixDecisionEngine"

        private const val HARMONIC_MULT        = 0.80f
        private const val WIDE_TRANSITION_MULT = 1.60f

        const val MIN_CROSSFADE_MS = 2_000L
        const val MAX_CROSSFADE_MS = 14_000L

        private const val DELTA_POWER_MIX = 15f

        // ── OLD: Per-strategy bass kill threshold fractions ───────────────────
        // These drove the mid-loop timing of the bass kill inside executeCrossfade.
        // Bass kill is now applied immediately at crossfade start (Step 5.5),
        // so these thresholds are no longer consulted. Kept for reference in case
        // we want to restore threshold-based behaviour per strategy later.
        // private const val BASS_KILL_HARMONIC        = 0.55f
        // private const val BASS_KILL_WIDE_TRANSITION = 0.25f

        private const val HIGH_ENERGY_THRESHOLD = 0.55f

        /**
         * Maximum raw BPM delta for which gradual tempo blending is applied.
         *
         * Why 10 BPM:
         * • A 10 BPM delta at a base of 100 BPM = 10% speed shift. ExoPlayer's
         *   pitch-corrected time stretching handles this cleanly with no audible
         *   artefacts. Above 10 BPM the speed ratio starts to become perceptible
         *   as a "rushing" sensation during the crossfade.
         * • Wide harmonic relationships (half-time / double-time / 3:2 etc.) are
         *   intentionally excluded: those are musical MOMENTS, not mismatches.
         *   Stretching a 70 BPM track to match 140 BPM would ruin the impact.
         * • This threshold covers the most common real-world case: two tracks from
         *   the same genre (afrobeats, dancehall, pop) recorded at slightly
         *   different tempos. Their kick drums will clash without blending.
         */
        private const val TEMPO_BLEND_MAX_DELTA = 10f
    }

    fun computeMixDecision(
        outgoingBpm: Float,
        incomingBpm: Float,
        userCrossfadeDurationMs: Long,
        outgoingAmplitude: Float = 1.0f,
        incomingAmplitude: Float = 1.0f
    ): MixDecision {
        val isBpmValid     = outgoingBpm > 0f && incomingBpm > 0f
        val rawDelta       = if (isBpmValid) abs(outgoingBpm - incomingBpm) else 0f
        val effectiveDelta = if (isBpmValid) smartNextTrack.minimumHarmonicDelta(outgoingBpm, incomingBpm) else 0f
        val isHarmonic     = if (isBpmValid) smartNextTrack.isHarmonicallyCompatible(outgoingBpm, incomingBpm) else false

        // ── Strategy selection ────────────────────────────────────────────────
        val strategy = when {
            !isBpmValid                              -> MixStrategy.HARMONIC
            isHarmonic && rawDelta > DELTA_POWER_MIX -> MixStrategy.HARMONIC
            rawDelta > DELTA_POWER_MIX               -> MixStrategy.WIDE_TRANSITION
            else                                     -> MixStrategy.HARMONIC
        }

        // ── Crossfade duration ────────────────────────────────────────────────
        val durationMult = when (strategy) {
            MixStrategy.HARMONIC        -> HARMONIC_MULT
            MixStrategy.WIDE_TRANSITION -> WIDE_TRANSITION_MULT
        }

        val effectiveDurationMs = (userCrossfadeDurationMs * durationMult)
            .toLong().coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        // ── Tempo blending ────────────────────────────────────────────────────
        //
        // How it works in CrossfadeEngine:
        //   Over the FIRST HALF of the crossfade, the outgoing track's playback
        //   speed is gradually nudged from 1.0× toward stretchRatio via
        //   ExoPlayer PlaybackParameters (pitch-corrected time stretch = key lock).
        //   The incoming track always plays at its native speed. By the midpoint
        //   of the fade both tracks are effectively at the same BPM, so the
        //   second half is a clean equal-power volume blend with no rhythmic clash.
        //
        // Why only HARMONIC + rawDelta ≤ 10:
        //   WIDE_TRANSITION already uses the energy-valley technique; adding a
        //   speed shift on top would make a deliberate jump feel broken.
        //   For true harmonic ratios (half-time etc.) stretching is wrong by design.
        val shouldTempoSync = isBpmValid &&
                rawDelta > 0.1f &&
                rawDelta <= TEMPO_BLEND_MAX_DELTA &&
                strategy == MixStrategy.HARMONIC

        // stretchRatio = target speed for the outgoing track.
        // incomingBpm / outgoingBpm: if incoming is faster, we speed up the
        // outgoing so both arrive at incoming's beat grid.
        // Clamped to [0.90, 1.10] as a hard safety: even if BPM detection
        // is slightly off we never apply more than ±10% stretch.
        val stretchRatio = if (shouldTempoSync && outgoingBpm > 0f) {
            (incomingBpm.toDouble() / outgoingBpm.toDouble()).coerceIn(0.90, 1.10)
        } else {
            1.0
        }

        // ── Energy-aware bass kill ────────────────────────────────────────────
        // Bass kill now fires immediately at crossfade start (Step 5.5 in
        // CrossfadeEngine) rather than at a progress threshold mid-fade.
        // The energy/strategy calculation below is kept in case we want to
        // restore differentiated behaviour (e.g. skip bass kill on low-energy
        // tracks, or re-introduce a threshold for a specific strategy).
        val avgEnergy    = (outgoingAmplitude + incomingAmplitude) / 2f
        val isHighEnergy = avgEnergy > HIGH_ENERGY_THRESHOLD

        // ── OLD: Threshold fraction was passed to MixDecision and read by the
        // fade loop to decide when to fire the bass kill mid-crossfade.
        // Now unused by CrossfadeEngine — bass kill is always immediate.
        // val energyAdjustment = if (isHighEnergy) -0.13f else 0f
        // val baseBassKill = when (strategy) {
        //     MixStrategy.HARMONIC        -> BASS_KILL_HARMONIC
        //     MixStrategy.WIDE_TRANSITION -> BASS_KILL_WIDE_TRANSITION
        // }
        // val bassKillThreshold = (baseBassKill + energyAdjustment).coerceIn(0.10f, 0.85f)

        // ── Build readable decision note (visible in logcat) ──────────────────
        val djNote = buildString {
            append("[DJ DECISION] ${strategy.name}: ")
            if (!isBpmValid) {
                append("UNKNOWN BPM | Fallback to safe HARMONIC fade")
            } else {
                append("${outgoingBpm.fmt()} → ${incomingBpm.fmt()} BPM")
                append(" | rawΔ=${rawDelta.fmt()} effectiveΔ=${effectiveDelta.fmt()}")
                if (isHarmonic) append(" | ★ HARMONIC")
            }
            append(" | fade=${effectiveDurationMs}ms")
            if (shouldTempoSync && stretchRatio != 1.0) {
                append(" | tempo-blend ×${String.format("%.3f", stretchRatio)}")
                append(" (${outgoingBpm.fmt()} → ${incomingBpm.fmt()} BPM convergence)")
            } else {
                append(" | no tempo-blend")
            }
            append(" | bass kill: IMMEDIATE at crossfade start")
            if (isHighEnergy) append(" 🔥 HIGH ENERGY (bass-heavy)")
            append("]")
            append("\n ↳ ")
            when (strategy) {
                MixStrategy.HARMONIC ->
                    if (shouldTempoSync) append("Tempo converges over first half of fade, then clean equal-power blend.")
                    else append("Harmonic ratio handles the moment — clean equal-power blend.")
                MixStrategy.WIDE_TRANSITION ->
                    append("Energy valley technique — the BPM jump IS the moment.")
            }
        }

        return MixDecision(
            outgoingBpm                  = outgoingBpm,
            incomingBpm                  = incomingBpm,
            rawBpmDelta                  = rawDelta,
            effectiveBpmDelta            = effectiveDelta,
            strategy                     = strategy,
            isHarmonic                   = isHarmonic,
            effectiveCrossfadeDurationMs = effectiveDurationMs,
            shouldTempoSync              = shouldTempoSync,
            stretchRatio                 = stretchRatio,
            // ── OLD: bassKillThresholdFraction ───────────────────────────────
            // Was used by CrossfadeEngine's fade loop to fire bass kill at a
            // progress percentage. Now unused — bass kill fires immediately in
            // Step 5.5. Field kept on MixDecision so callers don't break;
            // hardcoded to 0f to make it obvious it's inert.
            bassKillThresholdFraction    = 0f,
            djNote                       = djNote
        )
    }

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
            bestBand >= 0                                            -> bestBand.toShort()
            else                                                     -> 0.toShort()
        }
    }

    private fun Float.fmt() = String.format("%.1f", this)
}