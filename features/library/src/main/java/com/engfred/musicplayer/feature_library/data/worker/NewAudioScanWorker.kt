package com.engfred.musicplayer.feature_library.data.worker

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
import androidx.work.WorkerParameters
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_library.data.source.local.ContentResolverDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class NewAudioScanWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataSource: ContentResolverDataSource,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val CHANNEL_ID = "new_music_channel"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "new_audio_scan_work"
    }

    override suspend fun doWork(): Result {
        Log.d("NewAudioScanWorker", "Worker started!!!!")

        // 1. Check Permissions
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ActivityCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure()
        }

        // 2. Get last scan time
        val lastScanTime = settingsRepository.getLastScanTimestamp()
        val currentTime = System.currentTimeMillis()

        // 3. Fetch all audio files
        val allFiles = dataSource.getAllAudioFilesFlow().first()

        // 4. Filter for files added AFTER the last scan
        val newFiles = allFiles.filter { (it.dateAdded * 1000L) > lastScanTime }

        if (newFiles.isNotEmpty()) {
            val firstSongName = newFiles.first().title ?: "New Song"
            showNotification(newFiles.size, firstSongName)

            // Update the timestamp so we don't notify about these again
            settingsRepository.updateLastScanTimestamp(currentTime)
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(count: Int, firstSongName: String) {
        // Check permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        // REMOVED: createNotificationChannel() call.
        // It is already created by MusicPlayerApplication.onCreate()

        // Get the Intent dynamically
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("PLAY_NEW_SONGS", true)
        }

        if (intent == null) return

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (count == 1) {
            "New song added: $firstSongName"
        } else {
            "$count new songs added including $firstSongName"
        }

        val iconResId = android.R.drawable.stat_sys_headset

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconResId)
            .setContentTitle("New Music Detected")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}