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
 * Returns null only when [remainingQueue] is empty.
 *
 * This class is intentionally free of Android / coroutine dependencies so it can
 * be unit-tested without a device or Robolectric.
 */
class GetSmartNextTrackUseCase @Inject constructor() {

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
        // Uses standard absolute difference between BPMs.
        val inTolerance = withBpm.filter { song ->
            val songBpm = bpmCache[song.id] ?: Float.MAX_VALUE
            abs(songBpm - currentBpm) <= tolerance
        }
        val candidates = inTolerance.ifEmpty { withBpm }

        return candidates.minByOrNull { song ->
            val songBpm = bpmCache[song.id] ?: Float.MAX_VALUE
            abs(songBpm - currentBpm)
        } ?: withoutBpm.firstOrNull() ?: remainingQueue.first()
    }
}