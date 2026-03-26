package com.engfred.musicplayer.feature_dj_mix.domain.repository

import com.engfred.musicplayer.core.domain.model.AudioFile
import kotlinx.coroutines.flow.Flow

/**
 * Small value object that holds everything we now cache for a track.
 * This keeps the public API clean while giving us both BPM and the first-beat cue point.
 */
/**
 * Waveform envelope is a 128-element FloatArray of normalised (0.0–1.0) RMS values
 * representing the track's amplitude shape. Empty when not yet analysed.
 * The crossfade engine uses this as the static shape for visualiser bars, then
 * multiplies beat-pulse animation on top so bars reflect the real track content.
 */
data class BpmInfo(
    val bpm: Float,
    val firstBeatMs: Long = 0L,
    val amplitude: Float = 0f,
    val waveformEnvelope: FloatArray = FloatArray(0),
    val analysisFailed: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BpmInfo) return false
        return bpm == other.bpm &&
                firstBeatMs == other.firstBeatMs &&
                amplitude == other.amplitude &&
                waveformEnvelope.contentEquals(other.waveformEnvelope) &&
                analysisFailed == other.analysisFailed
    }

    override fun hashCode(): Int {
        var result = bpm.hashCode()
        result = 31 * result + firstBeatMs.hashCode()
        result = 31 * result + amplitude.hashCode()
        result = 31 * result + waveformEnvelope.contentHashCode()
        result = 31 * result + analysisFailed.hashCode()
        return result
    }
}

/**
 * Contract for all DJ Mix data operations.
 *
 * Implemented by [DjMixRepositoryImpl] in the data layer — the domain layer
 * depends only on this interface (Clean Architecture rule).
 */
interface DjMixRepository {

    /**
     * Reactive map of audioFileId → BPM info, emitting a new value whenever any
     * entry is inserted or updated in the local Room cache.
     * Used by [DjMixViewModel] to track analysis progress and reorder the queue.
     */
    fun getBpmCacheFlow(): Flow<Map<Long, BpmInfo>>

    /**
     * One-shot lookup: returns the cached BPM + firstBeatMs entries for [audioFileIds]
     * as a map. Returns an empty map for IDs not yet analysed.
     */
    suspend fun getBpmForAudios(audioFileIds: List<Long>): Map<Long, BpmInfo>

    /**
     * Enqueues a [BpmAnalysisWorker] for the supplied songs.
     * Already-cached songs are skipped inside the worker (idempotent).
     * Uses [ExistingWorkPolicy.KEEP] so tapping "DJ Mix" twice is safe.
     *
     * @param playlistId Used to build a unique work name so analysis runs
     * are scoped per playlist.
     * @param songs Songs whose BPM is needed.
     */
    fun enqueueBpmAnalysis(playlistId: Long, songs: List<AudioFile>)
}