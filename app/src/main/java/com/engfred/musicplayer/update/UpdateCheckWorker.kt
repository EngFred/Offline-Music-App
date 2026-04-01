package com.engfred.musicplayer.update

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.engfred.musicplayer.BuildConfig
import com.engfred.musicplayer.MusicPlayerApplication
import com.engfred.musicplayer.core.domain.model.UpdateInfo
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.usecases.CheckForUpdateUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateCheckWorker"

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val checkForUpdateUseCase: CheckForUpdateUseCase,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val NOTIFICATION_ID = 1003
        const val WORK_NAME       = "update_check_work"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Scheduled — runs every 24 h when connected")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: checking for update in background")

        val updateInfo = checkForUpdateUseCase(BuildConfig.VERSION_NAME)
            ?: return Result.success()  // Up-to-date or network error

        // Respect the user's "Remind Me Later" choice for this version
        val snoozed = settingsRepository.getSnoozedUpdateVersion()
        if (updateInfo.latestVersion == snoozed) {
            Log.d(TAG, "Version ${updateInfo.latestVersion} was snoozed by user — skipping notification")
            return Result.success()
        }

        showNotification(updateInfo)
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(updateInfo: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_UPDATE_DIALOG", true)
            } ?: return

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat
            .Builder(context, MusicPlayerApplication.UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update available — v${updateInfo.latestVersion}")
            .setContentText("A new version of Music Player is ready to download.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            append("Version ${updateInfo.latestVersion} is ready.\n\n")
                            if (updateInfo.releaseNotes.isNotBlank()) {
                                append(updateInfo.releaseNotes.take(200))
                                if (updateInfo.releaseNotes.length > 200) append("…")
                            }
                        }
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Update notification posted for v${updateInfo.latestVersion}")
    }
}