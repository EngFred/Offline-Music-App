package com.engfred.musicplayer.feature_dj_mix.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.bpm.BpmAnalysisWorker
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.domain.repository.DjMixRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [DjMixRepository].
 *
 * Data sources:
 *  - [BpmCacheDao]  — Room DAO for reading / writing cached BPM values.
 *  - [WorkManager]  — used to schedule background [BpmAnalysisWorker] jobs.
 */
@Singleton
class DjMixRepositoryImpl @Inject constructor(
    private val bpmCacheDao: BpmCacheDao,
    @ApplicationContext private val context: Context
) : DjMixRepository {

    override fun getBpmCacheFlow(): Flow<Map<Long, Float>> =
        bpmCacheDao.getAllBpmEntries().map { entries ->
            entries.associate { it.audioFileId to it.bpm }
        }

    override suspend fun getBpmForAudios(audioFileIds: List<Long>): Map<Long, Float> =
        bpmCacheDao.getBpmForAudios(audioFileIds).associate { it.audioFileId to it.bpm }

    override fun enqueueBpmAnalysis(playlistId: Long, songs: List<AudioFile>) {
        val request = BpmAnalysisWorker.buildRequest(songs)
        WorkManager.getInstance(context).enqueueUniqueWork(
            BpmAnalysisWorker.TAG_PREFIX + playlistId,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}