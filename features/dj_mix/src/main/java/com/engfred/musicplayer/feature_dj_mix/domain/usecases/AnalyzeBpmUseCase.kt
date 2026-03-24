package com.engfred.musicplayer.feature_dj_mix.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.repository.DjMixRepository
import javax.inject.Inject

/**
 * Triggers background BPM analysis for a playlist's songs via [DjMixRepository].
 *
 * Encapsulates the "start analysis" intent so the ViewModel doesn't need to know
 * about WorkManager or [BpmAnalysisWorker] directly.
 */
class AnalyzeBpmUseCase @Inject constructor(
    private val repository: DjMixRepository
) {
    /**
     * Enqueues a [BpmAnalysisWorker] for all songs in [songs] that are not yet cached.
     * Safe to call repeatedly — already-cached songs are skipped inside the worker.
     *
     * @param playlistId Unique identifier for this playlist's analysis job.
     * @param songs      Full list of songs in the playlist.
     */
    operator fun invoke(playlistId: Long, songs: List<AudioFile>) {
        repository.enqueueBpmAnalysis(playlistId, songs)
    }
}