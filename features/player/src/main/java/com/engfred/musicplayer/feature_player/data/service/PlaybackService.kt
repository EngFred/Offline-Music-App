package com.engfred.musicplayer.feature_player.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.LastPlaybackState
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.domain.model.WidgetDisplayInfo
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.mapper.AudioFileMapper
import com.engfred.musicplayer.core.util.sortAudioFiles
import com.engfred.musicplayer.core.data.audio.eq.BandEqAudioProcessor
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
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

private const val TAG = "PlaybackService"
private const val MUSIC_NOTIFICATION_CHANNEL_ID = "music_player_channel"
private const val MUSIC_NOTIFICATION_ID = 1001
private const val UNKNOWN_ARTIST = "Unknown Artist"
private const val PERIODIC_SAVE_INTERVAL_MS = 5000L

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

    @Inject
    lateinit var activePlayerRegistry: ActivePlayerRegistry

    @Inject
    lateinit var eqProcessor: BandEqAudioProcessor

    @Inject
    lateinit var audioFileMapper: AudioFileMapper

    private lateinit var exoPlayer: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastIdleDisplayInfo: WidgetDisplayInfo? = null
    private var preferredRepeatMode: RepeatMode = RepeatMode.OFF
    private var widgetThemeAware: Boolean = false
    private var isFullShown: Boolean = false

    companion object {
        const val WIDGET_PROVIDER_CLASS = "com.engfred.musicplayer.widget.MusicWidgetProvider"
        const val ACTION_WIDGET_PLAY_PAUSE = "com.engfred.musicplayer.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.engfred.musicplayer.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.engfred.musicplayer.ACTION_WIDGET_PREV"
        const val ACTION_REFRESH_WIDGET = "com.engfred.musicplayer.ACTION_REFRESH_WIDGET"
        const val ACTION_WIDGET_REPEAT = "com.engfred.musicplayer.ACTION_WIDGET_REPEAT"
        const val ACTION_WIDGET_SHUFFLE = "com.engfred.musicplayer.ACTION_WIDGET_SHUFFLE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService onCreate called")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                MUSIC_NOTIFICATION_CHANNEL_ID,
                "Playback Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = NotificationCompat.Builder(this, MUSIC_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Music Player")
                .setContentText("Starting music service...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationManager.IMPORTANCE_MIN)
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

            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(eqProcessor))
                        .build()
                }
            }

            exoPlayer = ExoPlayer.Builder(this, renderersFactory).build().apply {
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
            }

            val intent = Intent().setClassName(this, "${packageName}.MainActivity").apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

            mediaSession = MediaSession.Builder(this, exoPlayer)
                .setSessionActivity(pendingIntent)
                .setCallback(MediaSessionCallback())
                .build()

            setMediaNotificationProvider(musicNotificationProvider)

            runBlocking {
                loadLastIdleDisplayInfo()
                if (sharedAudioDataSource.playingQueueAudioFiles.value.isEmpty()) {
                    Log.d(TAG, "Playing queue was empty, loading songs...")
                    loadPlayingQueue()
                }

                val lastState = settingsRepository.getLastPlaybackState().first()
                val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
                if (playingQueue.isNotEmpty() && exoPlayer.mediaItemCount == 0) {
                    val mediaItems = playingQueue.map { audioFileMapper.mapAudioFileToMediaItem(it) }
                    val startIndex = lastState.audioId?.let { id -> playingQueue.indexOfFirst { it.id == id } }?.takeIf { it != -1 } ?: 0
                    val startPos = if (lastState.positionMs > 0) lastState.positionMs else 0L

                    exoPlayer.setMediaItems(mediaItems, startIndex, startPos)
                    exoPlayer.prepare()
                    Log.d(TAG, "Pre-populated ExoPlayer queue: ${mediaItems.size} items, startIndex=$startIndex, startPos=$startPos")
                }
            }
            isFullShown = lastIdleDisplayInfo != null

            exoPlayer.addListener(object : Player.Listener {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    updateWidgetWithInfo()
                }

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

                override fun onPositionDiscontinuity(reason: Int) {
                    super.onPositionDiscontinuity(reason)
                    updateWidgetWithInfo()
                }
            })

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

            serviceScope.launch {
                while (true) {
                    delay(PERIODIC_SAVE_INTERVAL_MS)
                    if (exoPlayer.currentMediaItem != null) {
                        savePlaybackStateAsync(serviceScope, settingsRepository, exoPlayer)
                    }
                }
            }

            serviceScope.launch {
                val appSettings = settingsRepository.getAppSettings().first()
                preferredRepeatMode = appSettings.repeatMode
                widgetThemeAware = (appSettings.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    WidgetUpdater.updateWidget(
                        this@PlaybackService,
                        exoPlayer,
                        lastIdleDisplayInfo,
                        getIdleRepeatMode(),
                        widgetThemeAware,
                        isInitial = true
                    )
                }
            }

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
                        eqProcessor.setPreset(settings.audioPreset)
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
                settingsRepository.saveLastPlaybackState(LastPlaybackState(null))
                lastIdleDisplayInfo = null
                Log.w(TAG, "Last audio ID ${lastState.audioId} not found; cleared state")
            }
        } else {
            lastIdleDisplayInfo = null
        }
    }

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

    private suspend fun handleWidgetPlayPause() {
        try {
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
            isFullShown = true
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
        }
    }

    private fun updateWidgetWithInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WidgetUpdater.updateWidget(this, exoPlayer, lastIdleDisplayInfo, getIdleRepeatMode(), widgetThemeAware)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val superResult = super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: return superResult

        when (action) {
            ACTION_WIDGET_PLAY_PAUSE -> serviceScope.launch { handleWidgetPlayPause() }
            ACTION_WIDGET_NEXT -> serviceScope.launch { playbackController.skipToNext() }
            ACTION_WIDGET_PREV -> serviceScope.launch { playbackController.skipToPrevious() }
            ACTION_REFRESH_WIDGET -> updateWidgetWithInfo()
            ACTION_WIDGET_REPEAT -> serviceScope.launch {
                val nextMode = when (preferredRepeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
                playbackController.setRepeatMode(nextMode)
            }
            ACTION_WIDGET_SHUFFLE -> serviceScope.launch {
                val queue = sharedAudioDataSource.playingQueueAudioFiles.value
                if (queue.isNotEmpty()) {
                    playbackController.initiateShufflePlayback(queue)
                }
            }
        }

        return superResult
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && (player.playWhenReady || player.isPlaying)) {
            Log.d(TAG, "Task removed while playing — service remains in foreground for audio")
            return
        }
        Log.d(TAG, "Task removed while paused — stopping service")
        stopSelf()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    if (exoPlayer.mediaItemCount == 0) {
                        loadPlayingQueue()
                    }
                    val lastState = settingsRepository.getLastPlaybackState().first()
                    val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
                    if (playingQueue.isNotEmpty()) {
                        val startIndex = lastState.audioId?.let { id ->
                            playingQueue.indexOfFirst { it.id == id }
                        }?.takeIf { it != -1 } ?: 0

                        val mediaItems = playingQueue.map { audioFileMapper.mapAudioFileToMediaItem(it) }
                        val startPos = if (lastState.positionMs > 0) lastState.positionMs else 0L

                        val result = MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            startIndex,
                            startPos
                        )
                        settableFuture.set(result)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onPlaybackResumption error: ${e.message}", e)
                }
                settableFuture.setException(UnsupportedOperationException("No media items available to resume"))
            }
            return settableFuture
        }
    }
}