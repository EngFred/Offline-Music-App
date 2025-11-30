package com.engfred.musicplayer.feature_player.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.LastPlaybackState
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.domain.model.WidgetDisplayInfo
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.util.sortAudioFiles
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

const val MUSIC_NOTIFICATION_CHANNEL_ID = "music_playback_channel"
const val MUSIC_NOTIFICATION_ID = 101
private const val UNKNOWN_ARTIST = "Unknown Artist"

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var musicNotificationProvider: MusicNotificationProvider

    @Inject
    lateinit var playbackController: PlaybackController

    @Inject
    lateinit var libRepo: LibraryRepository

    @Inject
    lateinit var sharedAudioDataSource: SharedAudioDataSource

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var exoPlayer: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var equalizer: Equalizer? = null
    private var lastIdleDisplayInfo: WidgetDisplayInfo? = null
    private var preferredRepeatMode: RepeatMode = RepeatMode.OFF
    private var widgetThemeAware: Boolean = false
    private var lastAppliedPreset: AudioPreset? = null  // To avoid redundant applies
    private var isFullShown: Boolean = false  //Track if full was rendered to prevent idle reversion

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.engfred.musicplayer.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.engfred.musicplayer.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.engfred.musicplayer.ACTION_WIDGET_PREV"
        const val ACTION_REFRESH_WIDGET = "com.engfred.musicplayer.ACTION_REFRESH_WIDGET"
        const val ACTION_WIDGET_REPEAT = "com.engfred.musicplayer.ACTION_WIDGET_REPEAT"
        const val ACTION_WIDGET_SHUFFLE = "com.engfred.musicplayer.ACTION_WIDGET_SHUFFLE"
        const val WIDGET_PROVIDER_CLASS = "com.engfred.musicplayer.widget.PlayerWidgetProvider"
        private const val TAG = "PlaybackService"
        private const val PERIODIC_SAVE_INTERVAL_MS = 10000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate started!!!")
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = NotificationCompat.Builder(this, MUSIC_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Music Player")
                .setContentText("Starting music service...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationManager.IMPORTANCE_LOW)
                .setSilent(true)
                .build()
            try {
                startForeground(MUSIC_NOTIFICATION_ID, notification)
            } catch (_: Exception) {
                stopSelf()
                return
            }
        }

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
            }

            val intent = Intent().setClassName(this, "${packageName}.MainActivity")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

            mediaSession = MediaSession.Builder(this, exoPlayer)
                .setSessionActivity(pendingIntent)
                .build()

            setMediaNotificationProvider(musicNotificationProvider)

            //Load last info BLOCKING to ensure it's ready before any updates
            runBlocking {
                loadLastIdleDisplayInfo()
                if (sharedAudioDataSource.playingQueueAudioFiles.value.isEmpty()) {
                    Log.d(TAG, "Playing queue was empty loading songs....")
                    loadPlayingQueue()
                } else{
                    Log.d(TAG, "QUEUE IS SET. READY TO PLAY!!!!!.")
                }
            }
            isFullShown = lastIdleDisplayInfo != null  // Set flag based on load

            // listen for player changes and update widget
            exoPlayer.addListener(object : Player.Listener {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    updateWidgetWithInfo()
                }

                @RequiresApi(Build.VERSION_CODES.P)
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    super.onMediaItemTransition(mediaItem, reason)
                    if (mediaItem == null) {
                        serviceScope.launch {
                            updateWidgetWithInfo()
                        }
                    } else {
                        updateWidgetWithInfo()
                    }
                }

                @RequiresApi(Build.VERSION_CODES.P)
                override fun onPositionDiscontinuity(reason: Int) {
                    super.onPositionDiscontinuity(reason)
                    updateWidgetWithInfo()
                }
            })

            // Start periodic update for duration (1s)
            serviceScope.launch {
                while (true) {
                    delay(1000)
                    if (exoPlayer.isPlaying) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            WidgetUpdater.updateWidget(this@PlaybackService, exoPlayer, lastIdleDisplayInfo, getIdleRepeatMode(), widgetThemeAware)
                        }
                    }
                }
            }

            // Periodic state saving
            serviceScope.launch {
                while (true) {
                    delay(PERIODIC_SAVE_INTERVAL_MS)
                    if (exoPlayer.currentMediaItem != null) {
                        savePlaybackStateAsync(serviceScope, settingsRepository, exoPlayer)
                    }
                }
            }

            equalizer = Equalizer(0, exoPlayer.audioSessionId)

            // Load preferred repeat and widget mode (async, as less critical)
            serviceScope.launch {
                val appSettings = settingsRepository.getAppSettings().first()
                preferredRepeatMode = appSettings.repeatMode
                widgetThemeAware = (appSettings.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE)
                // Initial update now that load is done
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    WidgetUpdater.updateWidget(
                        this@PlaybackService,
                        exoPlayer,
                        lastIdleDisplayInfo,
                        getIdleRepeatMode(),
                        widgetThemeAware,
                        isInitial = true  // Force full rebuild on boot/refresh
                    )
                }
            }

            // Observe settings for preset and repeat changes
            serviceScope.launch {
                var lastRepeat: RepeatMode? = null
                var lastWidgetMode: WidgetBackgroundMode? = null
                var lastPreset: AudioPreset? = null
                settingsRepository.getAppSettings().collectLatest { settings ->
                    if (settings.repeatMode != lastRepeat || settings.widgetBackgroundMode != lastWidgetMode) {
                        lastRepeat = settings.repeatMode
                        lastWidgetMode = settings.widgetBackgroundMode
                        preferredRepeatMode = settings.repeatMode
                        widgetThemeAware = (settings.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE)
                        val idleInfo = lastIdleDisplayInfo
                        val idleRepeat = getIdleRepeatMode()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            WidgetUpdater.updateWidget(this@PlaybackService, exoPlayer, idleInfo, idleRepeat, widgetThemeAware)
                        }
                    }
                    if (settings.audioPreset != lastPreset) {
                        lastPreset = settings.audioPreset
                        EqualizerPresetApplier.applyPreset(
                            eq = equalizer,
                            scope = serviceScope,
                            preset = settings.audioPreset,
                            intensity = 1.0f,
                            steps = 8,
                            stepDelayMs = 30L,
                            onApplied = { applied ->
                                lastAppliedPreset = applied
                            },
                            onError = { t ->
                                Log.w(TAG, "Failed to apply preset: ${t.message}")
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}", e)
            stopSelf()
        }
    }

    private fun getIdleRepeatMode(): Int = when (preferredRepeatMode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    private suspend fun loadLastIdleDisplayInfo() {
        val lastState = settingsRepository.getLastPlaybackState().first()
        if (lastState.audioId != null) {
            val audios = libRepo.getAllAudioFiles().first()
            val audio = audios.find { it.id == lastState.audioId }
            if (audio != null) {
                lastIdleDisplayInfo = WidgetDisplayInfo(
                    title = audio.title,
                    artist = audio.artist ?: UNKNOWN_ARTIST,
                    durationMs = audio.duration,
                    positionMs = lastState.positionMs.coerceAtLeast(0L)
                        .coerceAtMost(audio.duration),
                    artworkUri = audio.albumArtUri
                )
                Log.d(TAG, "Cached last idle display info: ${audio.title} by ${audio.artist}")
            } else {
                // Clear invalid state
                settingsRepository.saveLastPlaybackState(LastPlaybackState(null))
                lastIdleDisplayInfo = null
                Log.w(TAG, "Last audio ID ${lastState.audioId} not found; cleared state")
            }
        } else {
            lastIdleDisplayInfo = null
        }
    }

    //Added this function to load the playing queue
    private suspend fun loadPlayingQueue() {
        val lastState = settingsRepository.getLastPlaybackState().first()
        val deviceAudios = libRepo.getAllAudioFiles().first()

        val filter = settingsRepository.getFilterOption().first()
        val sorted = sortAudioFiles(deviceAudios, filter)
        val playingQueue = lastState.queueIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            val idToAudio = deviceAudios.associateBy { it.id }
            ids.mapNotNull { idToAudio[it] }.takeIf { it.isNotEmpty() } ?: sorted
        } ?: sorted

        sharedAudioDataSource.setPlayingQueue(playingQueue)
        Log.d(TAG, "Loaded ${playingQueue.size} songs into playing queue on service create")
    }

    /**
     * Central widget play/pause handling that calls playbackController directly.
     */
    private suspend fun handleWidgetPlayPause() {
        try {
            // Wait for controller to be ready (fixes double click issue)
            if (!playbackController.waitUntilReady(10000L)) {
                Log.e(TAG, "Playback controller not ready after timeout")
                Toast.makeText(applicationContext, "Player starting, try again", Toast.LENGTH_SHORT).show()
                return
            }
            Log.d(TAG, "Playback controller ready")
            if (exoPlayer.mediaItemCount == 0) {
                preparePlayingQueue()
            } else {
                playbackController.playPause()
            }
            isFullShown = true  //Mark full as shown after playback init
        } catch (e: Exception) {
            Log.e(TAG, "handleWidgetPlayPause error: ${e.message}", e)
        }
    }

    private suspend fun preparePlayingQueue() {
        val lastState = settingsRepository.getLastPlaybackState().first()
        val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value

        val startAudio = lastState.audioId?.let { id ->
            playingQueue.find { it.id == id }
        }
        val startUri = startAudio?.uri ?: playingQueue.firstOrNull()?.uri
        if (startUri != null) {
            val resumePosition =
                if (startAudio != null && lastState.positionMs > 0) lastState.positionMs else C.TIME_UNSET
            Log.d(TAG, "Starting playback with URI: $startUri (resumePos=$resumePosition)")
            playbackController.initiatePlayback(startUri, resumePosition)
            if (startAudio != null) {
                Toast.makeText(applicationContext, "Resumed playback", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(applicationContext, "No audio files found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            //Check if exoPlayer is initialized before using
            if (!::exoPlayer.isInitialized) {
                Log.w(TAG, "exoPlayer not initialized yet in onStartCommand; skipping action")
                return START_STICKY
            }
            when (intent?.action) {
                ACTION_WIDGET_PLAY_PAUSE -> {
                    serviceScope.launch {
                        handleWidgetPlayPause()
                    }
                }
                ACTION_WIDGET_NEXT -> {
                    try {
                        exoPlayer.seekToNextMediaItem()
                    } catch (_: Exception) {}
                }
                ACTION_WIDGET_PREV -> {
                    try {
                        exoPlayer.seekToPreviousMediaItem()
                    } catch (_: Exception) {}
                }
                ACTION_REFRESH_WIDGET -> {
                    //Brief delay if load not done (should be rare since blocking in onCreate)
                    if (lastIdleDisplayInfo == null && isFullShown) {
                        serviceScope.launch {
                            delay(200)  // Wait for any pending load
                            WidgetUpdater.updateWidget(this@PlaybackService, exoPlayer, lastIdleDisplayInfo, getIdleRepeatMode(), widgetThemeAware, isInitial = true)
                        }
                    } else {
                        WidgetUpdater.updateWidget(this, exoPlayer, lastIdleDisplayInfo, getIdleRepeatMode(), widgetThemeAware, isInitial = true)
                    }
                }
                ACTION_WIDGET_REPEAT -> {
                    PlaybackActions.handleRepeatToggle(exoPlayer, settingsRepository, serviceScope)
                }
                ACTION_WIDGET_SHUFFLE -> {
                    exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
                    updateWidgetWithInfo()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onStartCommand action handling error: ${e.message}")
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            // Save last state synchronously (as before)
            saveLastPlaybackStateBlocking(settingsRepository, exoPlayer)
            serviceScope.cancel()
            mediaSession?.run {
                exoPlayer.release()
                release()
                mediaSession = null
            }
            equalizer?.release()
            equalizer = null
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MUSIC_NOTIFICATION_CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for music playback controls"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateWidgetWithInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            //Skip if would revert to idle when full is shown
            if (lastIdleDisplayInfo == null && exoPlayer.currentMediaItem == null && isFullShown) return
            WidgetUpdater.updateWidget(this, exoPlayer, lastIdleDisplayInfo, getIdleRepeatMode(), widgetThemeAware)
            isFullShown = (lastIdleDisplayInfo != null || exoPlayer.currentMediaItem != null)
        }
    }
}