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

        private const val HARMONIC_MULT         = 0.80f
        private const val WIDE_TRANSITION_MULT  = 1.60f

        const val MIN_CROSSFADE_MS = 2_000L
        const val MAX_CROSSFADE_MS = 14_000L

        private const val DELTA_POWER_MIX   = 15f

        private const val BASS_KILL_HARMONIC         = 0.55f
        private const val BASS_KILL_WIDE_TRANSITION  = 0.25f

        private const val HIGH_ENERGY_THRESHOLD = 0.55f
    }

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
        val strategy = when {
            !isBpmValid                              -> MixStrategy.HARMONIC

            isHarmonic && rawDelta > DELTA_POWER_MIX -> MixStrategy.HARMONIC

            rawDelta > DELTA_POWER_MIX               -> MixStrategy.WIDE_TRANSITION

            else                                     -> MixStrategy.HARMONIC
        }

        // ── Duration multiplier ───────────────────────────────────────────────
        val durationMult = when (strategy) {
            MixStrategy.HARMONIC         -> HARMONIC_MULT
            MixStrategy.WIDE_TRANSITION  -> WIDE_TRANSITION_MULT
        }

        val effectiveDurationMs = (userCrossfadeDurationMs * durationMult)
            .toLong().coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        // Force tempo sync OFF — pure volume crossfading only
        val shouldTempoSync = false
        val stretchRatio    = 1.0

        // ── ENERGY-AWARE BASS KILL (this is the secret sauce for heavy beats) ──
        val avgEnergy        = (outgoingAmplitude + incomingAmplitude) / 2f
        val isHighEnergy     = avgEnergy > HIGH_ENERGY_THRESHOLD
        val energyAdjustment = if (isHighEnergy) -0.13f else 0f

        val baseBassKill = when (strategy) {
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
            append(" | 2-strategy mode]")
            append("\n ↳ ")
            when (strategy) {
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