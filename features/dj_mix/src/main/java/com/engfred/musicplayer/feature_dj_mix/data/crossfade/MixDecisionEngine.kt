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
 * ── TEST BUILD ────────────────────────────────────────────────────────────────
 * Decision space intentionally reduced to TWO strategies for evaluation:
 *   • HARMONIC       — covers all previously-TRANSPARENT, SMOOTH, and POWER_MIX cases
 *   • WIDE_TRANSITION — unchanged, fires for all >15 BPM delta cases
 *
 * All TRANSPARENT / SMOOTH / POWER_MIX logic is COMMENTED OUT, not deleted.
 * To restore full 5-strategy behaviour, un-comment every block marked [STRATEGY TEST].
 * ─────────────────────────────────────────────────────────────────────────────
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

        // [STRATEGY TEST] Commented out — not needed while only HARMONIC + WIDE_TRANSITION are active.
        // private const val TRANSPARENT_MULT = 0.20f
        // private const val SMOOTH_MULT      = 0.10f
        // private const val POWER_MIX_MULT   = 0.75f

        private const val HARMONIC_MULT         = 0.80f
        private const val WIDE_TRANSITION_MULT  = 1.60f

        const val MIN_CROSSFADE_MS = 2_000L
        const val MAX_CROSSFADE_MS = 14_000L

        // ── Tuning Block: BPM Delta Thresholds ───────────────────────────────

        // [STRATEGY TEST] Still referenced as the boundary between HARMONIC and WIDE_TRANSITION.
        // DELTA_TRANSPARENT and DELTA_SMOOTH are preserved for when the full matrix is restored.
        // private const val DELTA_TRANSPARENT = 3f
        // private const val DELTA_SMOOTH      = 8f
        private const val DELTA_POWER_MIX   = 15f   // kept — used as HARMONIC / WIDE_TRANSITION boundary

        // ── Tuning Block: Bass-Kill Triggers (BASE values) ───────────────────
        // These are further adjusted by energy in real time.

        // [STRATEGY TEST] Commented out — not in active decision path.
        // private const val BASS_KILL_TRANSPARENT     = 0.15f
        // private const val BASS_KILL_SMOOTH          = 0.15f
        // private const val BASS_KILL_POWER_MIX       = 0.32f

        private const val BASS_KILL_HARMONIC         = 0.55f
        private const val BASS_KILL_WIDE_TRANSITION  = 0.25f

        private const val HIGH_ENERGY_THRESHOLD = 0.55f
    }

    /**
     * Computes the perfect mix strategy for any two tracks.
     *
     * ── TEST BUILD behaviour ──────────────────────────────────────────────────
     * All tracks that previously resolved to TRANSPARENT, SMOOTH, or POWER_MIX
     * now fall through to HARMONIC. WIDE_TRANSITION fires as before (>15 BPM delta).
     * ─────────────────────────────────────────────────────────────────────────
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
        val isBpmValid      = outgoingBpm > 0f && incomingBpm > 0f
        val rawDelta        = if (isBpmValid) abs(outgoingBpm - incomingBpm) else 0f
        val effectiveDelta  = if (isBpmValid) smartNextTrack.minimumHarmonicDelta(outgoingBpm, incomingBpm) else 0f
        val isHarmonic      = if (isBpmValid) smartNextTrack.isHarmonicallyCompatible(outgoingBpm, incomingBpm) else false

        // ── Strategy selection ────────────────────────────────────────────────
        // [STRATEGY TEST] Only HARMONIC and WIDE_TRANSITION are active.
        // Original full-matrix branches are preserved below in commented form.
        val strategy = when {
            !isBpmValid                              -> MixStrategy.HARMONIC  // [was SMOOTH] — safe fallback

            isHarmonic && rawDelta > DELTA_POWER_MIX -> MixStrategy.HARMONIC  // explicit harmonic even at wide delta

            rawDelta > DELTA_POWER_MIX               -> MixStrategy.WIDE_TRANSITION

            // All remaining cases (previously TRANSPARENT / SMOOTH / POWER_MIX) → HARMONIC
            else                                     -> MixStrategy.HARMONIC

            // [STRATEGY TEST] Full original matrix — un-comment to restore:
            // isHarmonic && rawDelta > DELTA_TRANSPARENT -> MixStrategy.HARMONIC
            // rawDelta <= DELTA_TRANSPARENT              -> MixStrategy.TRANSPARENT
            // rawDelta <= DELTA_SMOOTH                   -> MixStrategy.SMOOTH
            // rawDelta <= DELTA_POWER_MIX                -> MixStrategy.POWER_MIX
            // else                                       -> MixStrategy.WIDE_TRANSITION
        }

        // ── Duration multiplier ───────────────────────────────────────────────
        // [STRATEGY TEST] TRANSPARENT / SMOOTH / POWER_MIX branches commented out.
        val durationMult = when (strategy) {
            // MixStrategy.TRANSPARENT -> TRANSPARENT_MULT  // [STRATEGY TEST]
            // MixStrategy.SMOOTH      -> SMOOTH_MULT       // [STRATEGY TEST]
            // MixStrategy.POWER_MIX   -> POWER_MIX_MULT    // [STRATEGY TEST]
            MixStrategy.HARMONIC         -> HARMONIC_MULT
            MixStrategy.WIDE_TRANSITION  -> WIDE_TRANSITION_MULT
        }

        val effectiveDurationMs = (userCrossfadeDurationMs * durationMult)
            .toLong().coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        // 🔥 Force tempo sync OFF — pure volume crossfading only
        val shouldTempoSync = false
        val stretchRatio    = 1.0

        // ── ENERGY-AWARE BASS KILL (this is the secret sauce for heavy beats) ──
        val avgEnergy        = (outgoingAmplitude + incomingAmplitude) / 2f
        val isHighEnergy     = avgEnergy > HIGH_ENERGY_THRESHOLD
        val energyAdjustment = if (isHighEnergy) -0.13f else 0f

        // [STRATEGY TEST] TRANSPARENT / SMOOTH / POWER_MIX bass-kill branches commented out.
        val baseBassKill = when (strategy) {
            // MixStrategy.TRANSPARENT -> BASS_KILL_TRANSPARENT  // [STRATEGY TEST]
            // MixStrategy.SMOOTH      -> BASS_KILL_SMOOTH        // [STRATEGY TEST]
            // MixStrategy.POWER_MIX   -> BASS_KILL_POWER_MIX     // [STRATEGY TEST]
            MixStrategy.HARMONIC         -> BASS_KILL_HARMONIC
            MixStrategy.WIDE_TRANSITION  -> BASS_KILL_WIDE_TRANSITION
        }

        val bassKillThreshold = (baseBassKill + energyAdjustment).coerceIn(0.10f, 0.85f)

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
            append(" | NO tempo-sync")
            append(" | bass kill at ${(bassKillThreshold * 100).toInt()}%")
            if (isHighEnergy) append(" 🔥 HIGH ENERGY (bass-heavy)")
            append(" | [TEST BUILD: 2-strategy mode]")
            append("\n ↳ ")
            when (strategy) {
                // MixStrategy.TRANSPARENT -> append("Silky smooth, fast cut — nothing to hide.")            // [STRATEGY TEST]
                // MixStrategy.SMOOTH      -> append("Standard club technique — fast cut.")                   // [STRATEGY TEST]
                // MixStrategy.POWER_MIX   -> append("Early bass kill gives incoming track space to breathe.") // [STRATEGY TEST]
                MixStrategy.HARMONIC         -> append("Half/double-time — harmonic lock does the work.")
                MixStrategy.WIDE_TRANSITION  -> append("Energy valley technique — the BPM jump IS the moment.")
            }
        }

        return MixDecision(
            outgoingBpm                 = outgoingBpm,
            incomingBpm                 = incomingBpm,
            rawBpmDelta                 = rawDelta,
            effectiveBpmDelta           = effectiveDelta,
            strategy                    = strategy,
            isHarmonic                  = isHarmonic,
            effectiveCrossfadeDurationMs = effectiveDurationMs,
            shouldTempoSync             = shouldTempoSync,
            stretchRatio                = stretchRatio,
            bassKillThresholdFraction   = bassKillThreshold,
            djNote                      = djNote
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
            bestBand >= 0 -> bestBand.toShort()
            else          -> 0.toShort()
        }
    }

    private fun Float.fmt() = String.format("%.1f", this)
}