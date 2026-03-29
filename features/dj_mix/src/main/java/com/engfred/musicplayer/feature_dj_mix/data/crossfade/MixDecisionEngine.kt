package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.util.Log
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Pure stateless component that classifies a BPM pair into a [MixStrategy] and derives
 * every crossfade parameter from it.  No ExoPlayer references; no coroutines.
 *
 * Injected into [CrossfadeEngine] so the engine file stays focused on playback mechanics.
 */
@Singleton
class MixDecisionEngine @Inject constructor(
    private val smartNextTrack: GetSmartNextTrackUseCase
) {

    companion object {
        private const val TAG = "MixDecisionEngine"

        // ── Tuning Block: Crossfade Duration Multipliers ─────────────────────
        private const val TRANSPARENT_MULT      = 0.70f
        private const val SMOOTH_MULT           = 1.00f
        private const val POWER_MIX_MULT        = 1.40f
        private const val HARMONIC_MULT         = 0.80f
        private const val WIDE_TRANSITION_MULT  = 1.60f
        const val MIN_CROSSFADE_MS              = 2_000L
        const val MAX_CROSSFADE_MS              = 14_000L

        // ── Tuning Block: BPM Delta Thresholds ───────────────────────────────
        // Determines when a strategy upgrades to the next intensity level.
        private const val DELTA_TRANSPARENT     = 3f
        private const val DELTA_SMOOTH          = 8f
        private const val DELTA_POWER_MIX       = 15f

        // ── Tuning Block: Bass-Kill Triggers ─────────────────────────────────
        // Fraction of crossfade elapsed. Lower = earlier kill.
        private const val BASS_KILL_TRANSPARENT     = 0.70f
        private const val BASS_KILL_SMOOTH          = 0.50f
        private const val BASS_KILL_POWER_MIX       = 0.35f
        private const val BASS_KILL_HARMONIC        = 0.55f
        private const val BASS_KILL_WIDE_TRANSITION = 0.25f

        // RubberBand stretch ratio bounds for SMOOTH / POWER_MIX tempo sync.
        private const val MAX_STRETCH_RATIO = 1.33
        private const val MIN_STRETCH_RATIO = 0.75
    }

    /**
     * Classifies the BPM pair and derives all crossfade parameters.
     *
     * Decision order:
     * 1. Validate BPMs (Fallback to safe mix if metadata is missing/zero).
     * 2. Check harmonic compatibility first (120→60 is HARMONIC, not WIDE_TRANSITION).
     * 3. Classify by raw delta into TRANSPARENT / SMOOTH / POWER_MIX / WIDE_TRANSITION.
     * 4. Scale crossfade duration by strategy multiplier.
     * 5. Compute RubberBand stretch ratio = incomingBpm / outgoingBpm (coerced to safe range).
     * 6. Select bass-kill trigger point based on strategy.
     */
    fun computeMixDecision(
        outgoingBpm: Float,
        incomingBpm: Float,
        userCrossfadeDurationMs: Long
    ): MixDecision {
        // SAFETY GUARD: If a track lacks BPM metadata, do not attempt complex math.
        val isBpmValid = outgoingBpm > 0f && incomingBpm > 0f

        val rawDelta       = if (isBpmValid) abs(outgoingBpm - incomingBpm) else 0f
        val effectiveDelta = if (isBpmValid) smartNextTrack.minimumHarmonicDelta(outgoingBpm, incomingBpm) else 0f
        val isHarmonic     = if (isBpmValid) smartNextTrack.isHarmonicallyCompatible(outgoingBpm, incomingBpm) else false

        val strategy = when {
            !isBpmValid                                  -> MixStrategy.SMOOTH // Safe fallback for missing metadata
            isHarmonic && rawDelta > DELTA_TRANSPARENT   -> MixStrategy.HARMONIC
            rawDelta <= DELTA_TRANSPARENT                -> MixStrategy.TRANSPARENT
            rawDelta <= DELTA_SMOOTH                     -> MixStrategy.SMOOTH
            rawDelta <= DELTA_POWER_MIX                  -> MixStrategy.POWER_MIX
            else                                         -> MixStrategy.WIDE_TRANSITION
        }

        val durationMult = when (strategy) {
            MixStrategy.TRANSPARENT     -> TRANSPARENT_MULT
            MixStrategy.SMOOTH          -> SMOOTH_MULT
            MixStrategy.POWER_MIX       -> POWER_MIX_MULT
            MixStrategy.HARMONIC        -> HARMONIC_MULT
            MixStrategy.WIDE_TRANSITION -> WIDE_TRANSITION_MULT
        }
        val effectiveDurationMs = (userCrossfadeDurationMs * durationMult)
            .toLong().coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        // Tempo sync only for SMOOTH / POWER_MIX.
        // HARMONIC: the harmonic lock IS the mix — sync fights it.
        // WIDE_TRANSITION: delta too large; visible speed artefacts even with RubberBand.
        val shouldTempoSync = isBpmValid && (strategy == MixStrategy.SMOOTH || strategy == MixStrategy.POWER_MIX)

        // stretchRatio < 1 = speed up incoming; > 1 = slow it down to match outgoing BPM.
        val stretchRatio: Double = if (shouldTempoSync) {
            (incomingBpm / outgoingBpm).toDouble().coerceIn(MIN_STRETCH_RATIO, MAX_STRETCH_RATIO)
        } else 1.0

        val bassKillThreshold = when (strategy) {
            MixStrategy.TRANSPARENT     -> BASS_KILL_TRANSPARENT
            MixStrategy.SMOOTH          -> BASS_KILL_SMOOTH
            MixStrategy.POWER_MIX       -> BASS_KILL_POWER_MIX
            MixStrategy.HARMONIC        -> BASS_KILL_HARMONIC
            MixStrategy.WIDE_TRANSITION -> BASS_KILL_WIDE_TRANSITION
        }

        val djNote = buildString {
            append("[DJ DECISION] ${strategy.name}: ")
            if (!isBpmValid) {
                append("UNKNOWN BPM detected | Fallback to safe smooth fade")
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
            append("\n         ↳ ")
            when (strategy) {
                MixStrategy.TRANSPARENT     -> append("Silky smooth — nothing to hide.")
                MixStrategy.SMOOTH          -> append("Standard club technique — RubberBand stretch.")
                MixStrategy.POWER_MIX       -> append("Early bass kill gives incoming track space to breathe.")
                MixStrategy.HARMONIC        -> append("Half/double-time — harmonic lock does the work.")
                MixStrategy.WIDE_TRANSITION -> append("Energy valley technique — the BPM jump IS the moment.")
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
            bassKillThresholdFraction    = bassKillThreshold,
            djNote                       = djNote
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

    // ── Formatting helpers ───────────────────────────────────────────────────

    private fun Float.fmt()   = String.format("%.1f", this)
    private fun Double.fmt3() = String.format("%.3f", this)
}