package com.engfred.musicplayer.feature_dj_mix.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioFile
import javax.inject.Inject
import kotlin.math.abs

/**
 * Pure Kotlin use case that selects the best next track from the remaining queue
 * based on BPM proximity to the currently playing track.
 *
 * "Best" is defined as the track whose BPM is closest to [currentBpm] and within
 * the configured [tolerance]. If no in-tolerance candidates exist, falls back to
 * the globally closest BPM track. If no BPM data is available at all, returns the
 * natural first track in the remaining queue.
 *
 * NEW: Added "Double/Half-Time" mixing logic. A 90 BPM track mathematically matches
 * a 180 BPM track perfectly (2:1 ratio). This algorithm now recognizes those harmonic
 * tempo relationships as perfect matches within the tolerance window.
 *
 * Returns null only when [remainingQueue] is empty.
 *
 * This class is intentionally free of Android / coroutine dependencies so it can
 * be unit-tested without a device or Robolectric.
 */
class GetSmartNextTrackUseCase @Inject constructor() {

    /**
     * Calculates the "Effective Difference" between two BPMs, accounting for
     * Double-Time and Half-Time mixing.
     * * e.g., 90 vs 180 -> Effective difference is 0 (Because 90 * 2 = 180).
     * e.g., 100 vs 205 -> Effective difference is 5 (Because 100 * 2 = 200, 205-200 = 5).
     */
    private fun getEffectiveBpmDifference(bpmA: Float, bpmB: Float): Float {
        val diffNormal = abs(bpmA - bpmB)
        val diffDouble = abs((bpmA * 2) - bpmB)
        val diffHalf = abs((bpmA / 2) - bpmB)

        // Return whichever mathematical relationship is the closest match
        return minOf(diffNormal, diffDouble, diffHalf)
    }

    /**
     * @param currentBpm     BPM of the track currently playing.
     * @param remainingQueue Tracks still to be played (must NOT include current track).
     * @param bpmCache       Map of audioFileId → analysed BPM.
     * @param tolerance      Maximum BPM delta considered "compatible".
     * In-tolerance tracks are preferred, but if none qualify
     * we fall back to the overall closest match so the queue
     * never stalls.
     * @return The best next [AudioFile], or null when [remainingQueue] is empty.
     */
    operator fun invoke(
        currentBpm: Float,
        remainingQueue: List<AudioFile>,
        bpmCache: Map<Long, Float>,
        tolerance: Float
    ): AudioFile? {
        if (remainingQueue.isEmpty()) return null

        // Partition by BPM data availability
        val withBpm    = remainingQueue.filter { bpmCache.containsKey(it.id) }
        val withoutBpm = remainingQueue.filterNot { bpmCache.containsKey(it.id) }

        // No BPM data for any remaining track → natural order
        if (withBpm.isEmpty()) return remainingQueue.first()

        // Prefer in-tolerance candidates; fall back to closest-overall if none qualify.
        // We use the new getEffectiveBpmDifference to catch 90->180 jumps as "in tolerance".
        val inTolerance = withBpm.filter { song ->
            getEffectiveBpmDifference(bpmCache[song.id] ?: Float.MAX_VALUE, currentBpm) <= tolerance
        }
        val candidates = inTolerance.ifEmpty { withBpm }

        return candidates.minByOrNull { song ->
            getEffectiveBpmDifference(bpmCache[song.id] ?: Float.MAX_VALUE, currentBpm)
        } ?: withoutBpm.firstOrNull() ?: remainingQueue.first()
    }
}