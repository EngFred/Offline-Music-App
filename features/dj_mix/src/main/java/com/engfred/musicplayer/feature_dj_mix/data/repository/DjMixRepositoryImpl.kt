package com.engfred.musicplayer.feature_dj_mix.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.bpm.BpmAnalysisWorker
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo
import com.engfred.musicplayer.feature_dj_mix.domain.repository.DjMixRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [DjMixRepository].
 *
 * ── BUG FIX ──────────────────────────────────────────────────────────────────
 * Both [getBpmCacheFlow] and [getBpmForAudios] were building [BpmInfo] objects
 * without mapping the [amplitude] column from the database entity. This meant
 * every track was presented to the CrossfadeEngine with amplitude = 0f, causing
 * Auto-Gain normalization to always compute a base volume of 1.0f — effectively
 * disabling the feature entirely.
 *
 * Fixed: BpmCacheEntity.amplitude is now included in both mappings.
 */
@Singleton
class DjMixRepositoryImpl @Inject constructor(
    private val bpmCacheDao: BpmCacheDao,
    @param:ApplicationContext private val context: Context,
) : DjMixRepository {

    /**
     * Returns a reactive map of audioFileId → [BpmInfo].
     * Emits a new value every time any BPM cache row is inserted or replaced
     * (i.e., as BpmAnalysisWorker processes each file in the playlist).
     *
     * FIX: amplitude is now correctly mapped from the entity.
     */
    override fun getBpmCacheFlow(): Flow<Map<Long, BpmInfo>> =
        bpmCacheDao.getAllBpmEntries().map { entries ->
            entries.associate { entity ->
                entity.audioFileId to BpmInfo(
                    bpm         = entity.bpm,
                    firstBeatMs = entity.firstBeatMs,
                    amplitude   = entity.amplitude  // FIX: was omitted before
                )
            }
        }

    /**
     * One-shot lookup for a set of file IDs.
     * FIX: amplitude is now correctly mapped from the entity.
     */
    override suspend fun getBpmForAudios(audioFileIds: List<Long>): Map<Long, BpmInfo> =
        bpmCacheDao.getBpmForAudios(audioFileIds).associate { entity ->
            entity.audioFileId to BpmInfo(
                bpm         = entity.bpm,
                firstBeatMs = entity.firstBeatMs,
                amplitude   = entity.amplitude  // FIX: was omitted before
            )
        }

    override fun enqueueBpmAnalysis(playlistId: Long, songs: List<AudioFile>) {
        val request = BpmAnalysisWorker.buildRequest(songs)
        WorkManager.getInstance(context).enqueueUniqueWork(
            BpmAnalysisWorker.TAG_PREFIX + playlistId,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}