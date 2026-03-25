package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that performs BPM analysis for a batch of audio files and
 * persists results in the local [BpmCacheDao].
 *
 * Follows the exact same `@HiltWorker` / `@AssistedInject` pattern used by
 * [com.engfred.musicplayer.feature_library.data.worker.NewAudioScanWorker].
 *
 * Enqueue with [BpmAnalysisWorker.buildRequest]; observe progress by collecting
 * [BpmCacheDao.getAllBpmEntries] in the ViewModel and diffing against the playlist.
 */
@HiltWorker
class BpmAnalysisWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val bpmCacheDao: BpmCacheDao,
    private val bpmAnalyzer: BpmAnalyzer
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val TAG = "BpmAnalysisWorker"
        /** Unique tag used to identify / cancel work for a specific playlist. */
        const val TAG_PREFIX = "bpm_analysis_"
        // Input data keys
        private const val KEY_AUDIO_IDS = "bpm_audio_ids" // LongArray
        private const val KEY_AUDIO_URIS = "bpm_audio_uris" // StringArray
        /**
         * Builds a one-time work request for the given [audioFiles].
         *
         * WorkManager's [Data] class natively supports LongArray and StringArray,
         * so we avoid any manual serialisation.
         */
        fun buildRequest(audioFiles: List<AudioFile>): OneTimeWorkRequest {
            val ids = audioFiles.map { it.id }.toLongArray()
            val uris = audioFiles.map { it.uri.toString() }.toTypedArray()
            val inputData: Data = workDataOf(
                KEY_AUDIO_IDS to ids,
                KEY_AUDIO_URIS to uris
            )
            return OneTimeWorkRequestBuilder<BpmAnalysisWorker>()
                .setInputData(inputData)
                .addTag(TAG_PREFIX)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val ids = inputData.getLongArray(KEY_AUDIO_IDS)
        val uriStrings = inputData.getStringArray(KEY_AUDIO_URIS)
        if (ids == null || uriStrings == null || ids.size != uriStrings.size) {
            Log.e(TAG, "Invalid input data — missing or mismatched IDs/URIs")
            return Result.failure()
        }
        Log.d(TAG, "Starting BPM analysis for ${ids.size} files")
        ids.zip(uriStrings.map { Uri.parse(it) }).forEach { (audioFileId, uri) ->
            // Skip if already cached — idempotent re-runs are safe
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
                        audioFileId = audioFileId,
                        bpm = result.bpm,
                        analyzedAt = System.currentTimeMillis(),
                        firstBeatMs = result.firstBeatMs,
                        amplitude = result.amplitude
                    )
                )
                Log.d(TAG, "Cached BPM ${result.bpm} + firstBeatMs=${result.firstBeatMs}ms RMS=${result.amplitude} for audioFileId=$audioFileId")
            } else {
                Log.w(TAG, "BPM analysis returned null for audioFileId=$audioFileId — will retry on next open")
            }
        }
        Log.d(TAG, "BPM analysis work complete")
        return Result.success()
    }
}