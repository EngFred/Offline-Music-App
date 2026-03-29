package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BpmAnalysisWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val bpmCacheDao: BpmCacheDao,
    private val bpmAnalyzer: BpmAnalyzer
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BpmAnalysisWorker"
        const val TAG_PREFIX = "bpm_analysis_"
        private const val KEY_AUDIO_IDS  = "bpm_audio_ids"
        private const val KEY_AUDIO_URIS = "bpm_audio_uris"

        // WorkManager's Data hard limit is 10 KB.
        // A single content URI is ~70–100 chars; 40 tracks ≈ 3–4 KB — safely under the cap.
        private const val CHUNK_SIZE = 40

        /**
         * Splits [audioFiles] into chunks of [CHUNK_SIZE] and returns one
         * [OneTimeWorkRequest] per chunk.  Each request is well under the 10 KB
         * WorkManager Data limit even for very long URIs.
         */
        fun buildRequests(audioFiles: List<AudioFile>): List<OneTimeWorkRequest> =
            audioFiles.chunked(CHUNK_SIZE).map { chunk ->
                val ids  = chunk.map { it.id }.toLongArray()
                val uris = chunk.map { it.uri.toString() }.toTypedArray()
                OneTimeWorkRequestBuilder<BpmAnalysisWorker>()
                    .setInputData(workDataOf(
                        KEY_AUDIO_IDS  to ids,
                        KEY_AUDIO_URIS to uris
                    ))
                    .addTag(TAG_PREFIX)
                    .build()
            }
    }

    override suspend fun doWork(): Result {
        val ids        = inputData.getLongArray(KEY_AUDIO_IDS)
        val uriStrings = inputData.getStringArray(KEY_AUDIO_URIS)

        if (ids == null || uriStrings == null || ids.size != uriStrings.size) {
            Log.e(TAG, "Invalid input data — missing or mismatched IDs/URIs")
            return Result.failure()
        }

        Log.d(TAG, "Starting BPM analysis for ${ids.size} files")

        ids.zip(uriStrings.map { Uri.parse(it) }).forEach { (audioFileId, uri) ->
            val cached = bpmCacheDao.getBpmForAudio(audioFileId)
            if (cached != null) {
                Log.d(TAG, "BPM already cached for $audioFileId (${cached.bpm} bpm) — skipping")
                return@forEach
            }

            Log.d(TAG, "Analysing BPM for audioFileId=$audioFileId uri=$uri")
            val result = bpmAnalyzer.analyzeBpm(uri)

            if (result != null) {
                bpmCacheDao.insertBpm(
                    BpmCacheEntity(
                        audioFileId      = audioFileId,
                        bpm              = result.bpm,
                        analyzedAt       = System.currentTimeMillis(),
                        firstBeatMs      = result.firstBeatMs,
                        amplitude        = result.amplitude,
                        waveformEnvelope = result.waveformEnvelope,
                        analysisFailed   = false
                    )
                )
                Log.d(TAG, "Cached BPM ${result.bpm} for audioFileId=$audioFileId")
            } else {
                bpmCacheDao.insertBpm(
                    BpmCacheEntity(
                        audioFileId    = audioFileId,
                        bpm            = 0f,
                        analyzedAt     = System.currentTimeMillis(),
                        analysisFailed = true
                    )
                )
                Log.w(TAG, "BPM analysis failed for audioFileId=$audioFileId — tombstone inserted")
            }
        }

        Log.d(TAG, "BPM analysis chunk complete")
        return Result.success()
    }
}