package com.engfred.musicplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MusicPlayerApplication : Application(), Configuration.Provider, ImageLoaderFactory { // 1. Implement Interface

    @Inject lateinit var workerFactory: HiltWorkerFactory

    companion object {
        const val NEW_MUSIC_CHANNEL_ID = "new_music_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Memory Cache: Keeps images in RAM.
            // 0.25 means use up to 25% of available app memory for images.
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            // Disk Cache: Keeps images on the phone storage.
            // This prevents reloading album art from the file system/network every time.
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // Use 2% of disk space
                    .build()
            }
            // Music Player Specific Optimization:
            // Ensure we read/write to cache, but if the image on disk changes,
            // we might want to reload. For album art, ENABLED is usually best.
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true) // Smooth transition when loading
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "New Music Updates"
            val descriptionText = "Notifications when new music is found on device"
            val importance = NotificationManager.IMPORTANCE_DEFAULT

            val channel = NotificationChannel(NEW_MUSIC_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}