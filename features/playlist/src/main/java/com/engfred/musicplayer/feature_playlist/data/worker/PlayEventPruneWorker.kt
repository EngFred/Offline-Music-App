package com.engfred.musicplayer.feature_playlist.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.engfred.musicplayer.feature_playlist.data.local.dao.PlaylistDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Prunes [SongPlayEventEntity] rows older than [RETENTION_DAYS] days.
 *
 * WHY THIS EXISTS:
 * Every play of every song inserts a row into song_play_events. Without cleanup
 * the table grows unbounded. On a device with 500 songs played daily over a year
 * that's ~180,000 rows — enough to make the GROUP BY query measurably slower.
 *
 * SCHEDULE: runs once per week. The prune window is 90 days, which is longer
 * than the 30-day query window in PlaylistRepositoryImpl so no queryable data
 * is ever deleted. Adjust RETENTION_DAYS if the query window changes.
 *
 * WIRING: call [PlayEventPruneWorker.schedule] once from your Application or
 * MainActivity onCreate.
 */
@HiltWorker
class PlayEventPruneWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val playlistDao: PlaylistDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG           = "PlayEventPruneWorker"
        const val WORK_NAME             = "play_event_prune_work"
        // Keep 90 days of events. The query window is 30 days, so this gives
        // 3× headroom before any queryable data is touched.
        private const val RETENTION_DAYS = 90L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PlayEventPruneWorker>(7, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Scheduled weekly play-event prune (retaining last $RETENTION_DAYS days)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val cutoffMs = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)
            playlistDao.deleteOldPlayEvents(cutoffMs)
            Log.d(TAG, "Pruned play events older than $RETENTION_DAYS days (cutoff=$cutoffMs)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune play events: ${e.message}", e)
            // Return failure so WorkManager retries, but don't crash the app.
            Result.retry()
        }
    }
}