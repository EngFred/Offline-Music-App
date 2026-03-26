package com.engfred.musicplayer.feature_dj_mix.data.bpm

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_dj_mix.data.local.dao.BpmCacheDao
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Periodic worker (24 h) that builds a BPM-aware Mix of the Day from the user's library
 * and persists it atomically to Room under the reserved ID
 * [AutomaticPlaylistType.MIX_OF_THE_DAY_PLAYLIST_ID].
 *
 * ## Why atomic?
 * The previous implementation called deletePlaylist / createPlaylist / addSongsToPlaylist
 * as three separate DB operations. Room fires an invalidation signal after *each* write,
 * so observers received three emissions:
 *   1. Mix deleted  → mixOfTheDayPlaylist = null
 *   2. Metadata row inserted, songs = []  → guard drops the mix (songs.isEmpty())
 *   3. Songs inserted  → mix visible ✓
 *
 * If the ViewModel's Flow collector processed emission 2 before 3 arrived (race), the
 * UI was left with a null/missing card until the next recomposition trigger.
 *
 * [PlaylistRepository.replaceMixOfTheDay] delegates to a single @Transaction DAO method,
 * collapsing all three steps into one SQLite commit → one Room emission → one UI update.
 */
@HiltWorker
class MixOfTheDayWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val bpmCacheDao: BpmCacheDao,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val getSmartNextTrackUseCase: GetSmartNextTrackUseCase
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "MixOfTheDayWorker"
        const val WORK_NAME = "mix_of_the_day"
        private const val MAX_TRACKS = 35

        // Reuses the notification channel created in MusicPlayerApplication.onCreate().
        private const val CHANNEL_ID = "new_music_channel"
        private const val NOTIFICATION_ID = 1002

        /**
         * Schedules the worker to fire once per day, with an initial delay calculated so
         * the first run lands at approximately 08:00 local time.
         *
         * Uses [ExistingPeriodicWorkPolicy.KEEP] — if already scheduled (e.g. from a
         * previous app launch) this is a no-op, preserving the existing schedule anchor.
         */
        fun schedule(context: Context) {
            val now = System.currentTimeMillis()
            val nextEightAm = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }
            val initialDelayMs = nextEightAm.timeInMillis - now

            val request = PeriodicWorkRequestBuilder<MixOfTheDayWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Scheduled — first run in ~${initialDelayMs / 1000 / 60} min")
        }
    }

    // ── Worker entry point ────────────────────────────────────────────────────

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: starting Mix of the Day generation")

        // Guard: storage permission must be granted before we touch the library.
        val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(context, storagePerm) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Storage permission not granted — aborting")
            return Result.failure()
        }

        // getAllAudioFiles() respects the user's MP3-only filter automatically.
        val allFiles = libraryRepository.getAllAudioFiles().first()
        if (allFiles.isEmpty()) {
            Log.d(TAG, "No audio files found — skipping")
            return Result.success()
        }

        // Build a BPM lookup from successfully analysed, non-failed entries only.
        val validBpmMap: Map<Long, Float> = bpmCacheDao.getAllBpmEntries().first()
            .filter { !it.analysisFailed && it.bpm > 0f }
            .associate { it.audioFileId to it.bpm }

        val eligibleFiles = allFiles.filter { validBpmMap.containsKey(it.id) }
        Log.d(TAG, "${eligibleFiles.size} / ${allFiles.size} files have valid BPM data")

        if (eligibleFiles.isEmpty()) {
            Log.d(TAG, "No BPM-analysed files yet — mix skipped until next run")
            return Result.success()
        }

        // Build the ordered mix track list.
        val mixTracks = buildMix(eligibleFiles, validBpmMap)
        if (mixTracks.isEmpty()) {
            Log.w(TAG, "buildMix returned empty — aborting")
            return Result.success()
        }

        // ── Atomic DB write ───────────────────────────────────────────────────
        // A single @Transaction covers delete + insert playlist + insert songs,
        // so Room fires exactly ONE invalidation → exactly ONE Flow emission.
        // All observers (LibraryViewModel, PlaylistViewModel, etc.) see the
        // fully-populated mix or nothing — never the empty intermediate state.
        val mixPlaylist = Playlist(
            id = AutomaticPlaylistType.MIX_OF_THE_DAY_PLAYLIST_ID,
            name = "Mix of the Day",
            isAutomatic = true,
            type = AutomaticPlaylistType.MIX_OF_THE_DAY,
            createdAt = System.currentTimeMillis()
        )
        playlistRepository.replaceMixOfTheDay(mixPlaylist, mixTracks)

        // Persist the generation timestamp for staleness checks elsewhere.
        settingsRepository.updateLastMixOfTheDayTimestamp(System.currentTimeMillis())

        Log.d(TAG, "Mix of the Day saved — ${mixTracks.size} tracks")
        showNotification(mixTracks.size)

        return Result.success()
    }

    // ── Mix construction ──────────────────────────────────────────────────────
    // Mirrors DjMixViewModel.performRebuild / selectOpener so the mix quality
    // is consistent with what the user gets from the interactive DJ screen.

    private fun buildMix(
        eligible: List<AudioFile>,
        bpmMap: Map<Long, Float>
    ): List<AudioFile> {
        val cap = min(eligible.size, MAX_TRACKS)
        val pool = eligible.toMutableList()
        val result = mutableListOf<AudioFile>()

        // Opener: 20th–40th BPM percentile — warm but not the highest energy.
        val sorted = pool.sortedBy { bpmMap[it.id]!! }
        val n = sorted.size
        val lowerIdx = (n * 0.20).toInt().coerceIn(0, n - 1)
        val upperIdx = (n * 0.40).toInt().coerceIn(lowerIdx, n - 1)
        val opener = sorted.getOrNull((lowerIdx + upperIdx) / 2) ?: pool.first()

        result.add(opener)
        pool.remove(opener)

        // Sliding window of the last 3 BPMs used to smooth arc transitions.
        val recentBpms = ArrayDeque<Float>(4)
        recentBpms.addLast(bpmMap[opener.id] ?: 120f)

        while (result.size < cap && pool.isNotEmpty()) {
            val setProgressFraction = result.size.toFloat() / cap.toFloat()
            val lastBpm = bpmMap[result.last().id] ?: 120f

            val next = getSmartNextTrackUseCase(
                currentBpm = lastBpm,
                remainingQueue = pool,
                bpmCache = bpmMap,
                tolerance = 10f,
                recentBpms = recentBpms.toList(),
                setProgressFraction = setProgressFraction
            ) ?: pool.first()

            result.add(next)
            pool.remove(next)

            val nextBpm = bpmMap[next.id]
            if (nextBpm != null && nextBpm > 0f) {
                recentBpms.addLast(nextBpm)
                if (recentBpms.size > 3) recentBpms.removeFirst()
            }
        }

        return result
    }

    // ── Notification ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showNotification(trackCount: Int) {
        // Skip if notification permission is missing (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_MIX_OF_THE_DAY", true)
                putExtra("MIX_PLAYLIST_ID", AutomaticPlaylistType.MIX_OF_THE_DAY_PLAYLIST_ID)
            } ?: return   // Can't build a tap-target without the launch intent.

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("Your Mix of the Day is ready 🎵")
            .setContentText("$trackCount tracks, perfectly BPM-matched.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}