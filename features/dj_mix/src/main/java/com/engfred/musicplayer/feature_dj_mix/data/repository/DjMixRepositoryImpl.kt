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

@Singleton
class DjMixRepositoryImpl @Inject constructor(
    private val bpmCacheDao: BpmCacheDao,
    @param:ApplicationContext private val context: Context,
) : DjMixRepository {

    override fun getBpmCacheFlow(): Flow<Map<Long, BpmInfo>> =
        bpmCacheDao.getAllBpmEntries().map { entries ->
            entries.associate { entity ->
                entity.audioFileId to BpmInfo(
                    bpm              = entity.bpm,
                    firstBeatMs      = entity.firstBeatMs,
                    amplitude        = entity.amplitude,
                    waveformEnvelope = entity.waveformEnvelope,
                    analysisFailed   = entity.analysisFailed
                )
            }
        }

    override suspend fun getBpmForAudios(audioFileIds: List<Long>): Map<Long, BpmInfo> =
        bpmCacheDao.getBpmForAudios(audioFileIds).associate { entity ->
            entity.audioFileId to BpmInfo(
                bpm              = entity.bpm,
                firstBeatMs      = entity.firstBeatMs,
                amplitude        = entity.amplitude,
                waveformEnvelope = entity.waveformEnvelope,
                analysisFailed   = entity.analysisFailed
            )
        }

    override fun enqueueBpmAnalysis(playlistId: Long, songs: List<AudioFile>) {
        if (songs.isEmpty()) return

        val requests    = BpmAnalysisWorker.buildRequests(songs)
        val workManager = WorkManager.getInstance(context)
        val uniqueName  = BpmAnalysisWorker.TAG_PREFIX + playlistId

        if (requests.size == 1) {
            // Single chunk
            workManager.enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                requests.first()
            )
        } else {
            // Multiple chunks — chain them so they run sequentially.
            // Sequential execution avoids hammering the CPU with parallel
            // BPM analysis on large playlists (e.g. 256-track "Unknown Artist").
            var continuation = workManager.beginUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                requests.first()
            )
            requests.drop(1).forEach { request ->
                continuation = continuation.then(request)
            }
            continuation.enqueue()
        }
    }

    override suspend fun updateCustomCueIn(audioFileId: Long, cueInMs: Long) {
        bpmCacheDao.updateCustomCueIn(audioFileId, cueInMs)
    }

    override suspend fun updateCustomMixOut(audioFileId: Long, mixOutMs: Long) {
        bpmCacheDao.updateCustomMixOut(audioFileId, mixOutMs)
    }

    override suspend fun clearCustomCues(audioFileId: Long) {
        bpmCacheDao.clearCustomCues(audioFileId)
    }
}