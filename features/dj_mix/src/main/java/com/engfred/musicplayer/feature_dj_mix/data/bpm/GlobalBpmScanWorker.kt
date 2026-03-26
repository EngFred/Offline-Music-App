package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Scans the entire device library for songs with no BPM cache entry and analyses
 * them in the background.
 *
 * Uses [LibraryRepository] (defined in :core) instead of ContentResolverDataSource
 * (which lives in :features:library) to avoid a cross-feature dependency.
 *
 * Safe to enqueue repeatedly — already-cached tracks (success or tombstone) are
 * skipped, so re-runs only process the delta.
 */
@HiltWorker
class GlobalBpmScanWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val bpmCacheDao: BpmCacheDao,
    private val bpmAnalyzer: BpmAnalyzer
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "GlobalBpmScanWorker"
        const val WORK_NAME = "global_bpm_scan"

        fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<GlobalBpmScanWorker>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
            Log.d(TAG, "Enqueued with policy=$policy")
        }
    }

    override suspend fun doWork(): Result {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Storage permission not granted — aborting")
            return Result.failure()
        }

        val allFiles = libraryRepository.getAllAudioFiles().first()
        if (allFiles.isEmpty()) {
            Log.d(TAG, "No audio files found on device")
            return Result.success()
        }

        val uncached = allFiles.filter { bpmCacheDao.getBpmForAudio(it.id) == null }
        Log.d(TAG, "${uncached.size} of ${allFiles.size} files need BPM analysis")

        if (uncached.isEmpty()) return Result.success()

        var analysed = 0
        var failed = 0

        for (audioFile in uncached) {
            // Re-check in case a concurrent playlist-specific worker already cached this
            if (bpmCacheDao.getBpmForAudio(audioFile.id) != null) continue

            val result = bpmAnalyzer.analyzeBpm(audioFile.uri)
            if (result != null) {
                bpmCacheDao.insertBpm(
                    BpmCacheEntity(
                        audioFileId      = audioFile.id,
                        bpm              = result.bpm,
                        analyzedAt       = System.currentTimeMillis(),
                        firstBeatMs      = result.firstBeatMs,
                        amplitude        = result.amplitude,
                        waveformEnvelope = result.waveformEnvelope,
                        analysisFailed   = false
                    )
                )
                analysed++
                Log.d(TAG, "[$analysed] Cached BPM ${result.bpm} for '${audioFile.title}'")
            } else {
                bpmCacheDao.insertBpm(
                    BpmCacheEntity(
                        audioFileId    = audioFile.id,
                        bpm            = 0f,
                        analyzedAt     = System.currentTimeMillis(),
                        analysisFailed = true
                    )
                )
                failed++
                Log.w(TAG, "Analysis failed for '${audioFile.title}' — tombstone inserted")
            }
        }

        Log.d(TAG, "Global scan complete: $analysed analysed, $failed failed")
        return Result.success()
    }
}