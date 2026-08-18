package com.engfred.musicplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.R
import com.engfred.musicplayer.feature_player.data.service.PlaybackService
import androidx.core.net.toUri
import androidx.media3.common.Player
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.LastPlaybackState
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.domain.model.WidgetDisplayInfo
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.util.sortAudioFiles
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.engfred.musicplayer.feature_player.data.service.WidgetUpdater
import com.engfred.musicplayer.helpers.PlaybackQueueHelper.preparePlayingQueue
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TAG = "PlayerWidgetProvider"

@OptIn(UnstableApi::class)
class PlayerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.engfred.musicplayer.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.engfred.musicplayer.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.engfred.musicplayer.ACTION_WIDGET_PREV"
        const val ACTION_UPDATE_WIDGET = "com.engfred.musicplayer.ACTION_UPDATE_WIDGET"
        const val ACTION_WIDGET_REPEAT = "com.engfred.musicplayer.ACTION_WIDGET_REPEAT"
        const val ACTION_WIDGET_SHUFFLE = "com.engfred.musicplayer.ACTION_WIDGET_SHUFFLE"
    }

    // Hilt entrypoint to fetch repositories from onReceive
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun settingsRepository(): SettingsRepository
        fun libraryRepository(): LibraryRepository
        fun sharedDataSource(): SharedAudioDataSource
    }

    @OptIn(UnstableApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive action=${intent.action}")

        when (intent.action) {
            ACTION_WIDGET_PLAY_PAUSE,
            ACTION_WIDGET_NEXT,
            ACTION_WIDGET_PREV,
            ACTION_WIDGET_REPEAT,
            ACTION_WIDGET_SHUFFLE -> {
                val svcIntent = Intent(context, PlaybackService::class.java).apply {
                    action = intent.action
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "startForegroundService refused for user action: ${e.message}")  // Changed: No fallback to update for user actions; just log
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service for widget action: ${e.message}", e)
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Received BOOT_COMPLETED")
                //Refreshing all widgets fully on boot to re-set PendingIntents and visuals
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, PlayerWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(component)
                onUpdate(context, appWidgetManager, appWidgetIds)
                // requestServiceRefresh still called via onUpdate
            }

            ACTION_UPDATE_WIDGET -> {
                CoroutineScope(Dispatchers.Main).launch {
                    updateWidgetFallback(context)
                }
            }
        }
    }

    /**
     * Try to request the service to refresh the widget. If startForegroundService is refused (boot, restricted),
     * fallback to sending ACTION_UPDATE_WIDGET broadcast which updates widgets without requiring the service.
     */
    private fun requestServiceRefresh(context: Context) {
        try {
            val refresh = Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_REFRESH_WIDGET
            }
            try {
                context.startService(refresh)
            } catch (e: Exception) {
                Log.w(TAG, "startService failed in requestServiceRefresh: ${e.message}")
                safeSendUpdateBroadcast(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestServiceRefresh failed: ${e.message}", e)
            safeSendUpdateBroadcast(context)
        }
    }

    /** Sends ACTION_UPDATE_WIDGET as a broadcast to this Provider — safe fallback when service cannot start. */
    private fun safeSendUpdateBroadcast(context: Context) {
        try {
            val b = Intent(context, PlayerWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(b)
            Log.d(TAG, "safeSendUpdateBroadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "safeSendUpdateBroadcast failed: ${e.message}")
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate widgets count=${appWidgetIds.size}")

        // Not updating immediately with idle; load async and update once
        CoroutineScope(Dispatchers.Main).launch {
            // Load state and update with it (avoids initial idle flash)
            val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            val settingsRepository = entry.settingsRepository()
            val libRepo = entry.libraryRepository()
            preparePlayingQueue(context, settingsRepository, libRepo, entry.sharedDataSource())

            val lastState = settingsRepository.getLastPlaybackState().first()
            val deviceAudios = libRepo.getAllAudioFiles().first()
            val startAudio = lastState.audioId?.let { id -> deviceAudios.find { it.id == id } }

            val (info, idleRepeatMode, widgetThemeAware) = loadIdleWidgetParams(context, lastState, startAudio, settingsRepository)

            appWidgetIds.forEach { appWidgetId ->
                try {
                    val views = buildRemoteViews(context, appWidgetId)
                    // Set visibilities based on loaded info
                    if (info != null) {
                        views.setViewVisibility(R.id.idle_container, View.GONE)
                        views.setViewVisibility(R.id.full_container, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.idle_container, View.VISIBLE)
                        views.setViewVisibility(R.id.full_container, View.GONE)
                    }
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating widget id=$appWidgetId: ${e.message}", e)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WidgetUpdater.updateWidget(context, null, info, idleRepeatMode, widgetThemeAware, isInitial = true)
                Log.d(TAG, "onUpdate widget update completed with loaded state")
            }
            requestServiceRefresh(context)
        }
    }

    // Build RemoteViews per widget instance so we can create unique PendingIntents per widgetId.
    private fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
        val pkg = context.packageName
        val resources = context.resources

        // Always inflate the unified layout
        val layoutToInflate = resources.getIdentifier("widget_layout", "layout", pkg).takeIf { it != 0 } ?: R.layout.widget_layout // fallback

        val views = RemoteViews(pkg, layoutToInflate)

        //Not setting visibilities here; done in onUpdate after load

        fun pendingIntentFor(action: String, widgetId: Int): PendingIntent {
            val i = Intent(context, PlayerWidgetProvider::class.java).apply {
                this.action = action
                // Make the Intent unique across widget instances and updates
                data = "app://widget/$action/$widgetId".toUri()
                `package` = pkg
            }
            val requestCode = (action.hashCode() xor widgetId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getBroadcast(context, requestCode, i, flags)
        }

        try {
            // Set pending intents for both idle and full play buttons (ignores if ID missing)
            views.setOnClickPendingIntent(R.id.widget_play_pause_idle, pendingIntentFor(ACTION_WIDGET_PLAY_PAUSE, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_play_pause_full, pendingIntentFor(ACTION_WIDGET_PLAY_PAUSE, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_next, pendingIntentFor(ACTION_WIDGET_NEXT, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_prev, pendingIntentFor(ACTION_WIDGET_PREV, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_repeat, pendingIntentFor(ACTION_WIDGET_REPEAT, appWidgetId))
            views.setOnClickPendingIntent(R.id.widget_shuffle, pendingIntentFor(ACTION_WIDGET_SHUFFLE, appWidgetId))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set button pending intents: ${e.message}", e)
        }

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        openAppIntent?.let {
            // make open intents unique too (to avoid reuse across widget instances)
            it.data = "app://widget/open/$appWidgetId".toUri()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val openPending = PendingIntent.getActivity(context, appWidgetId, it, flags)
            // safe to call even when ids not present (ignored)
            views.setOnClickPendingIntent(R.id.widget_album_art, openPending)
            views.setOnClickPendingIntent(R.id.widget_title, openPending)
            views.setOnClickPendingIntent(R.id.widget_artist, openPending)
        }

        return views
    }

    private suspend fun loadIdleWidgetParams(
        context: Context,
        lastState: LastPlaybackState,
        startAudio: AudioFile?,
        settingsRepository: SettingsRepository
    ): Triple<WidgetDisplayInfo?, Int, Boolean> {
        val appSettings = settingsRepository.getAppSettings().first()
        val widgetThemeAware = (appSettings.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE)
        val repeatMode = appSettings.repeatMode

        val info = if (startAudio != null && lastState.audioId != null) {
            WidgetDisplayInfo(
                title = startAudio.title,
                artist = startAudio.artist ?: "<Unknown>",
                durationMs = startAudio.duration,
                positionMs = lastState.positionMs.coerceAtLeast(0L)
                    .coerceAtMost(startAudio.duration),
                artworkUri = startAudio.albumArtUri
            ).also {
                Log.d(TAG, "Loaded idle display info: ${startAudio.title} by ${startAudio.artist}")
            }
        } else {
            // Clear invalid state
            settingsRepository.saveLastPlaybackState(LastPlaybackState(null))
            null.also {
                Log.w(TAG, "Last audio ID ${lastState.audioId} not found or null; cleared state")
            }
        }

        val idleRepeatMode = when (repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }

        return Triple(info, idleRepeatMode, widgetThemeAware)
    }

    private fun updateWidgetFallback(context: Context) {
        // Using runBlocking for load in fallback
        runBlocking {
            val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            val settingsRepository = entry.settingsRepository()
            val libRepo = entry.libraryRepository()

            preparePlayingQueue(context, settingsRepository, libRepo, entry.sharedDataSource())  //Loading queue here to ensure it's set even in fallback

            val lastState = settingsRepository.getLastPlaybackState().first()
            val deviceAudios = libRepo.getAllAudioFiles().first()
            val startAudio = lastState.audioId?.let { id -> deviceAudios.find { it.id == id } }

            val (info, idleRepeatMode, widgetThemeAware) = loadIdleWidgetParams(context, lastState, startAudio, settingsRepository)

            // Fully update widgets like in onUpdate to include PendingIntents
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PlayerWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(component)

            appWidgetIds.forEach { appWidgetId ->
                val views = buildRemoteViews(context, appWidgetId)
                if (info != null) {
                    views.setViewVisibility(R.id.idle_container, View.GONE)
                    views.setViewVisibility(R.id.full_container, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.idle_container, View.VISIBLE)
                    views.setViewVisibility(R.id.full_container, View.GONE)
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)  // Changed: Full update for fallback
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WidgetUpdater.updateWidget(context, null, info, idleRepeatMode, widgetThemeAware)
                Log.d(TAG, "Fallback widget update completed")
            }
        }
    }
}