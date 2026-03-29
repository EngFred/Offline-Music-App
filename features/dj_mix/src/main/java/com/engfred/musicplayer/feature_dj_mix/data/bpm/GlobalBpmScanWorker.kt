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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Scans the entire device library for songs with no BPM cache entry and delegates
 * the actual analysis to [BpmAnalysisWorker] via chained WorkManager requests.
 *
 * This worker intentionally does NO analysis itself — it only discovers uncached
 * tracks and enqueues them. This ensures doWork() completes almost instantly and
 * is never killed by the OS's 10-minute background execution limit, regardless of
 * library size.
 *
 * Uses [LibraryRepository] (defined in :core) instead of ContentResolverDataSource
 * (which lives in :features:library) to avoid a cross-feature dependency.
 *
 * Safe to enqueue repeatedly — [BpmAnalysisWorker] skips tracks that are already
 * cached (success or tombstone), so re-runs only process the delta.
 */
@HiltWorker
class GlobalBpmScanWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val bpmCacheDao: BpmCacheDao,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "GlobalBpmScanWorker"
        const val WORK_NAME = "global_bpm_scan"

        // Unique name for the chunk chain so it doesn't collide with
        // playlist-specific BpmAnalysisWorker chains (which use "bpm_analysis_<playlistId>").
        private const val CHUNK_WORK_NAME = "global_bpm_scan_chunks"

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
        // ── 1. Permission check ───────────────────────────────────────────────
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Storage permission not granted — aborting")
            return Result.failure()
        }

        // ── 2. Load full library ──────────────────────────────────────────────
        val allFiles = libraryRepository.getAllAudioFiles().first()
        if (allFiles.isEmpty()) {
            Log.d(TAG, "No audio files found on device")
            return Result.success()
        }

        // ── 3. Filter to uncached tracks ──────────────────────────────────────
        // A track is considered cached if a BpmCacheEntity exists for it, whether
        // the analysis succeeded or it was tombstoned (analysisFailed = true).
        // Re-analysing tombstoned tracks is intentionally skipped here; a future
        // "retry failed" flow can handle that separately.
        val uncached = allFiles.filter { bpmCacheDao.getBpmForAudio(it.id) == null }
        Log.d(TAG, "${uncached.size} of ${allFiles.size} files need BPM analysis")

        if (uncached.isEmpty()) {
            Log.d(TAG, "All tracks already cached — nothing to do")
            return Result.success()
        }

        // ── 4. Delegate to BpmAnalysisWorker chunks ───────────────────────────
        // buildRequests() splits uncached into groups of 40 and returns one
        // OneTimeWorkRequest per group. Each chunk is well under WorkManager's
        // 10 KB Data limit and finishes long before the 10-minute execution cap.
        val requests = BpmAnalysisWorker.buildRequests(uncached)
        val workManager = WorkManager.getInstance(context)

        if (requests.size == 1) {
            workManager.enqueueUniqueWork(
                CHUNK_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                requests.first()
            )
        } else {
            // Chain so chunks run sequentially — avoids hammering the CPU with
            // parallel BPM analysis on very large libraries.
            var chain = workManager.beginUniqueWork(
                CHUNK_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                requests.first()
            )
            requests.drop(1).forEach { request ->
                chain = chain.then(request)
            }
            chain.enqueue()
        }

        Log.d(TAG, "Enqueued ${requests.size} BpmAnalysisWorker chunk(s) for ${uncached.size} tracks")

        // doWork() returns immediately; the real work happens inside BpmAnalysisWorker.
        return Result.success()
    }
}