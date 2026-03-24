package com.engfred.musicplayer.feature_dj_mix.domain.repository

import com.engfred.musicplayer.core.domain.model.AudioFile
import kotlinx.coroutines.flow.Flow

/**
 * Contract for all DJ Mix data operations.
 *
 * Implemented by [DjMixRepositoryImpl] in the data layer — the domain layer
 * depends only on this interface (Clean Architecture rule).
 */
interface DjMixRepository {

    /**
     * Reactive map of audioFileId → BPM, emitting a new value whenever any
     * entry is inserted or updated in the local Room cache.
     * Used by [DjMixViewModel] to track analysis progress and reorder the queue.
     */
    fun getBpmCacheFlow(): Flow<Map<Long, Float>>

    /**
     * One-shot lookup: returns the cached BPM entries for [audioFileIds] as a map.
     * Returns an empty map for IDs not yet analysed — callers must handle the gap.
     */
    suspend fun getBpmForAudios(audioFileIds: List<Long>): Map<Long, Float>

    /**
     * Enqueues a [BpmAnalysisWorker] for the supplied songs.
     * Already-cached songs are skipped inside the worker (idempotent).
     * Uses [ExistingWorkPolicy.KEEP] so tapping "DJ Mix" twice is safe.
     *
     * @param playlistId Used to build a unique work name so analysis runs
     *                   are scoped per playlist.
     * @param songs      Songs whose BPM is needed.
     */
    fun enqueueBpmAnalysis(playlistId: Long, songs: List<AudioFile>)
}