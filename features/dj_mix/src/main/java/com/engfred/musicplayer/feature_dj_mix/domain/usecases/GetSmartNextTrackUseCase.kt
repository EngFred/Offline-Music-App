package com.engfred.musicplayer.feature_dj_mix.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.util.DjConstants
import javax.inject.Inject
import kotlin.math.abs

/**
 * Selects the best next track from the remaining queue — building an ascending
 * energy arc from slow to fast, the way a real DJ structures a set.
 */
class GetSmartNextTrackUseCase @Inject constructor() {

    private val HARMONIC_TOLERANCE_BPM = 2.5f

    // ── Score constants ───────────────────────────────────────────────────────
    private val HARMONIC_BONUS = 50f
    private val PERFECT_MATCH_SCORE = 80f
    private val BPM_DELTA_PENALTY_PER_BPM = 4.5f
    private val PROXIMITY_SCORE_FLOOR = -30f
    private val PROGRESSION_BONUS_PER_BPM = 2.5f
    private val REGRESSION_PENALTY_PER_BPM = 3.0f
    private val MAX_PROGRESSION_BONUS = 60f
    private val MAX_REGRESSION_PENALTY = -60f

    // ── Main operator ─────────────────────────────────────────────────────────
    operator fun invoke(
        currentBpm: Float,
        remainingQueue: List<AudioFile>,
        bpmCache: Map<Long, Float>,
        tolerance: Float,
        recentBpms: List<Float> = emptyList()
    ): AudioFile? {
        if (remainingQueue.isEmpty()) return null

        val withBpm    = remainingQueue.filter { bpmCache.containsKey(it.id) }
        val withoutBpm = remainingQueue.filterNot { bpmCache.containsKey(it.id) }

        if (withBpm.isEmpty()) return remainingQueue.first()

        val scored = withBpm.map { track ->
            val candidateBpm = bpmCache[track.id]!!
            track to scoreCandidate(currentBpm, candidateBpm)
        }

        return scored.maxByOrNull { it.second }?.first
            ?: withoutBpm.firstOrNull()
            ?: remainingQueue.first()
    }

    // ── Scoring ───────────────────────────────────────────────────────────────
    private fun scoreCandidate(currentBpm: Float, candidateBpm: Float): Float {
        var score = 0f

        if (isHarmonicallyCompatible(currentBpm, candidateBpm)) score += HARMONIC_BONUS

        val effectiveDelta = minimumHarmonicDelta(currentBpm, candidateBpm)
        val proximityScore = (PERFECT_MATCH_SCORE - effectiveDelta * BPM_DELTA_PENALTY_PER_BPM)
            .coerceAtLeast(PROXIMITY_SCORE_FLOOR)
        score += proximityScore

        val rawDelta = candidateBpm - currentBpm
        val progressionScore = if (rawDelta >= 0f) {
            (rawDelta * PROGRESSION_BONUS_PER_BPM).coerceAtMost(MAX_PROGRESSION_BONUS)
        } else {
            (rawDelta * REGRESSION_PENALTY_PER_BPM).coerceAtLeast(MAX_REGRESSION_PENALTY)
        }
        score += progressionScore

        return score
    }

    // ── Harmonic utilities ────────────────────────────────────────────────────
    fun isHarmonicallyCompatible(bpmA: Float, bpmB: Float): Boolean {
        if (bpmA <= 0f || bpmB <= 0f) return false
        return DjConstants.HARMONIC_RATIOS.any { ratio ->
            abs(bpmA * ratio - bpmB) <= HARMONIC_TOLERANCE_BPM
        }
    }

    fun minimumHarmonicDelta(currentBpm: Float, candidateBpm: Float): Float {
        if (currentBpm <= 0f || candidateBpm <= 0f) return abs(currentBpm - candidateBpm)
        return DjConstants.HARMONIC_RATIOS.minOf { ratio -> abs(currentBpm * ratio - candidateBpm) }
    }
}