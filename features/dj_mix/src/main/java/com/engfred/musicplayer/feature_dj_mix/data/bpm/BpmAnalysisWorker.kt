package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.R
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.data.local.entity.BpmCacheEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

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
         * Notification channel ID for BPM analysis progress.
         *
         * Must match the channel registered in MusicPlayerApplication
         * (NEW_MUSIC_CHANNEL_ID = "new_music_channel") in the :app module.
         * We duplicate the string here rather than importing MusicPlayerApplication
         * because :features:dj_mix cannot depend on :app — that would create a
         * circular module dependency. The string value must stay in sync manually
         * if the channel ID ever changes in MusicPlayerApplication.
         */
        private const val NOTIFICATION_CHANNEL_ID = "new_music_channel"

        /**
         * Notification ID used for BPM analysis progress.
         * Distinct from MixOfTheDayWorker (1002) and AutoMixService (505).
         */
        private const val NOTIFICATION_ID = 1003

        /**
         * Minimum number of tracks in a chunk before we bother showing the
         * progress notification. For 1–2 new tracks the analysis is fast enough
         * that a notification would flash and disappear — more annoying than helpful.
         */
        private const val NOTIFICATION_MIN_TRACKS = 5

        /**
         * Yield time between consecutive track analyses.
         *
         * WHY 150 ms:
         * Each BPM analysis is CPU-intensive (MediaCodec decode + aubio native call).
         * On budget devices (e.g. Samsung Galaxy A05, Helio G85) running back-to-back
         * analyses at normal thread priority saturates the CPU and causes the Android
         * system UI (notification panel, quick settings) to become noticeably laggy.
         *
         * 150 ms per track costs ~90 s extra on a 600-track library — completely
         * acceptable for a background task the user never waits on. The sleep lets
         * the system scheduler run higher-priority work (UI rendering, input handling)
         * between analysis jobs.
         */
        private const val INTER_TRACK_DELAY_MS = 150L

        /**
         * Splits [audioFiles] into chunks of [CHUNK_SIZE] and returns one
         * [OneTimeWorkRequest] per chunk. Each request is well under the 10 KB
         * WorkManager Data limit even for very long URIs.
         *
         * Constraints applied:
         * • requiresBatteryNotLow — avoids hammering the CPU when the device is
         *   already under battery stress, which compounds thermal throttling on
         *   budget chipsets.
         */
        fun buildRequests(audioFiles: List<AudioFile>): List<OneTimeWorkRequest> {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            return audioFiles.chunked(CHUNK_SIZE).map { chunk ->
                val ids  = chunk.map { it.id }.toLongArray()
                val uris = chunk.map { it.uri.toString() }.toTypedArray()
                OneTimeWorkRequestBuilder<BpmAnalysisWorker>()
                    .setInputData(workDataOf(
                        KEY_AUDIO_IDS  to ids,
                        KEY_AUDIO_URIS to uris
                    ))
                    .setConstraints(constraints)
                    .addTag(TAG_PREFIX)
                    .build()
            }
        }
    }

    override suspend fun doWork(): Result {
        // ── Priority: yield CPU to system UI and foreground apps ─────────────
        // THREAD_PRIORITY_BACKGROUND tells the Linux scheduler this thread
        // should lose every CPU contest against normal-priority work. The user
        // never waits on BPM analysis, so slower-but-responsive is always
        // better than faster-but-laggy for this task.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

        val ids        = inputData.getLongArray(KEY_AUDIO_IDS)
        val uriStrings = inputData.getStringArray(KEY_AUDIO_URIS)

        if (ids == null || uriStrings == null || ids.size != uriStrings.size) {
            Log.e(TAG, "Invalid input data — missing or mismatched IDs/URIs")
            return Result.failure()
        }

        val totalTracks = ids.size
        Log.d(TAG, "Starting BPM analysis for $totalTracks files")

        // ── Show progress notification for large batches ──────────────────────
        // Small batches (< NOTIFICATION_MIN_TRACKS) are silent — they finish
        // fast enough that a notification would just flash annoyingly.
        val showNotification = totalTracks >= NOTIFICATION_MIN_TRACKS
        if (showNotification) showProgressNotification(0, totalTracks)

        var processed = 0

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

            processed++

            // Update progress notification every 5 tracks to avoid notification
            // spam (NotificationManager rate-limits rapid updates on some ROMs).
            if (showNotification && processed % 5 == 0) {
                showProgressNotification(processed, totalTracks)
            }

            // ── Yield between tracks ──────────────────────────────────────────
            // Gives the system scheduler a window to service UI threads.
            // See INTER_TRACK_DELAY_MS KDoc for full rationale.
            delay(INTER_TRACK_DELAY_MS)
        }

        // ── Dismiss or finalise notification ──────────────────────────────────
        if (showNotification) {
            cancelProgressNotification()
        }

        Log.d(TAG, "BPM analysis chunk complete ($processed/$totalTracks processed)")
        return Result.success()
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showProgressNotification(done: Int, total: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = NotificationManagerCompat.from(context)

        val notification = NotificationCompat.Builder(
            context, NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Auto Mix — Analysing Library")
            .setContentText(
                if (done == 0) "Starting analysis…"
                else "Analysed $done of $total tracks"
            )
            .setProgress(total, done, done == 0)
            .setOngoing(true)          // cannot be dismissed by swipe
            .setSilent(true)           // never makes a sound or vibrates
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelProgressNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel progress notification: ${e.message}")
        }
    }
}