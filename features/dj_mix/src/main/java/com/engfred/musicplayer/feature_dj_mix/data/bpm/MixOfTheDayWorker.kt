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
import com.engfred.musicplayer.core.domain.model.AppSettings
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_dj_mix.R
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
 * When the user enables "Short Tracks Only" in Settings, any track whose duration
 * exceeds [AppSettings.MIX_OF_THE_DAY_MAX_DURATION_MS] (5 minutes) is excluded
 * from the candidate pool before BPM scoring begins.
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
        private const val CHANNEL_ID = "new_music_channel"
        private const val NOTIFICATION_ID = 1002

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

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: starting Mix of the Day generation")

        val storagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(context, storagePerm) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Storage permission not granted — aborting")
            return Result.failure()
        }

        val settings = settingsRepository.getAppSettings().first()

        val allFiles = libraryRepository.getAllAudioFiles().first()
        if (allFiles.isEmpty()) {
            Log.d(TAG, "No audio files found — skipping")
            return Result.success()
        }

        val validBpmMap: Map<Long, Float> = bpmCacheDao.getAllBpmEntries().first()
            .filter { !it.analysisFailed && it.bpm > 0f }
            .associate { it.audioFileId to it.bpm }

        // ── Duration guard ────────────────────────────────────────────────────
        // Applied before BPM scoring so the mix algorithm never even sees
        // long tracks when the user has opted into the short-tracks filter.
        val durationFiltered = if (settings.mixOfTheDayFilterByDuration) {
            allFiles.filter { it.duration <= AppSettings.MIX_OF_THE_DAY_MAX_DURATION_MS }
                .also { filtered ->
                    Log.d(
                        TAG,
                        "Duration filter active: ${filtered.size} / ${allFiles.size} tracks " +
                                "are ≤ ${AppSettings.MIX_OF_THE_DAY_MAX_DURATION_MS / 1_000}s"
                    )
                }
        } else {
            allFiles
        }

        val eligibleFiles = durationFiltered.filter { validBpmMap.containsKey(it.id) }
        Log.d(TAG, "${eligibleFiles.size} / ${durationFiltered.size} duration-eligible files have valid BPM data")

        if (eligibleFiles.isEmpty()) {
            Log.d(TAG, "No BPM-analysed files in the eligible pool — mix skipped until next run")
            return Result.success()
        }

        val mixTracks = buildMix(eligibleFiles, validBpmMap)
        if (mixTracks.isEmpty()) {
            Log.w(TAG, "buildMix returned empty — aborting")
            return Result.success()
        }

        val mixPlaylist = Playlist(
            id        = AutomaticPlaylistType.MIX_OF_THE_DAY_PLAYLIST_ID,
            name      = "Mix of the Day",
            isAutomatic = true,
            type      = AutomaticPlaylistType.MIX_OF_THE_DAY,
            createdAt = System.currentTimeMillis()
        )
        playlistRepository.replaceMixOfTheDay(mixPlaylist, mixTracks)
        settingsRepository.updateLastMixOfTheDayTimestamp(System.currentTimeMillis())

        Log.d(TAG, "Mix of the Day saved — ${mixTracks.size} tracks")
        showNotification(mixTracks.size)

        return Result.success()
    }

    // ── Mix construction ──────────────────────────────────────────────────────

    private fun buildMix(
        eligible: List<AudioFile>,
        bpmMap: Map<Long, Float>
    ): List<AudioFile> {
        val cap = min(eligible.size, MAX_TRACKS)
        val pool = eligible.toMutableList()
        val result = mutableListOf<AudioFile>()

        val today = Calendar.getInstance()
        val daySeed = today.get(Calendar.YEAR) * 1000L + today.get(Calendar.DAY_OF_YEAR)
        val rng = java.util.Random(daySeed)

        val sorted = pool.sortedBy { bpmMap[it.id]!! }
        val n = sorted.size
        val lowerIdx = (n * 0.20).toInt().coerceIn(0, n - 1)
        val upperIdx = (n * 0.40).toInt().coerceIn(lowerIdx, n - 1)
        val openerIdx = if (lowerIdx < upperIdx) lowerIdx + rng.nextInt(upperIdx - lowerIdx + 1)
        else lowerIdx
        val opener = sorted[openerIdx]

        result.add(opener)
        pool.remove(opener)

        val recentBpms = ArrayDeque<Float>(4)
        recentBpms.addLast(bpmMap[opener.id] ?: 120f)

        while (result.size < cap && pool.isNotEmpty()) {
            val lastBpm = bpmMap[result.last().id] ?: 120f
            val next = selectWeightedTopN(
                currentBpm = lastBpm,
                pool       = pool,
                bpmMap     = bpmMap,
                recentBpms = recentBpms.toList(),
                rng        = rng,
                topN       = 3
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

    private fun selectWeightedTopN(
        currentBpm: Float,
        pool: MutableList<AudioFile>,
        bpmMap: Map<Long, Float>,
        recentBpms: List<Float>,
        rng: java.util.Random,
        topN: Int = 3
    ): AudioFile? {
        data class Scored(val track: AudioFile, val score: Float)

        val candidates = pool
            .filter { bpmMap.containsKey(it.id) }
            .map { track ->
                val bpm = bpmMap[track.id]!!
                val harmonic = getSmartNextTrackUseCase.isHarmonicallyCompatible(currentBpm, bpm)
                val delta = getSmartNextTrackUseCase.minimumHarmonicDelta(currentBpm, bpm)
                val proximity = (100f - delta * 4.5f).coerceAtLeast(-30f)
                val harmonicBonus = if (harmonic) 60f else 0f
                val stagnantCount = recentBpms.count { kotlin.math.abs(it - bpm) <= 2f }
                val stagnation = if (stagnantCount >= (recentBpms.size / 2f).coerceAtLeast(1f)) 18f else 0f
                Scored(track, harmonicBonus + proximity - stagnation)
            }
            .sortedByDescending { it.score }
            .take(topN)

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0].track

        val minScore = candidates.minOf { it.score }
        val weights = candidates.map { (it.score - minScore + 1f) }
        val total = weights.sum()
        var pick = rng.nextFloat() * total
        for ((i, w) in weights.withIndex()) {
            pick -= w
            if (pick <= 0f) return candidates[i].track
        }
        return candidates.last().track
    }

    // ── Notification ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showNotification(trackCount: Int) {
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
            } ?: return

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your Mix of the Day is ready 🎵")
            .setContentText("$trackCount tracks, perfectly BPM-matched.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}