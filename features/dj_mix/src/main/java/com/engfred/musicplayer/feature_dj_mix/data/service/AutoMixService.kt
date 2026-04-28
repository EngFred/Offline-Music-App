package com.engfred.musicplayer.feature_dj_mix.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
import com.engfred.musicplayer.feature_dj_mix.data.sampler.SamplerEngine
import com.engfred.musicplayer.feature_dj_mix.domain.DjSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class AutoMixService : Service() {

    @Inject lateinit var crossfadeEngine: CrossfadeEngine
    @Inject lateinit var djSessionManager: DjSessionManager
    @Inject lateinit var activePlayerRegistry: ActivePlayerRegistry
    @Inject lateinit var samplerEngine: SamplerEngine
    @Inject lateinit var settingsRepository: SettingsRepository

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engineReleased = false

    @Volatile private var lastSkipTimestampMs: Long = 0L
    @Volatile private var lastNotifiedTrackId: Long?      = null
    @Volatile private var lastNotifiedIsPlaying: Boolean? = null
    @Volatile private var lastProgressNotifyMs: Long      = 0L
    @Volatile private var lastSyncedTrackId: Long?        = null

    private val PROGRESS_NOTIFY_INTERVAL_MS = 5_000L

    companion object {
        private const val TAG = "DjMixService"
        const val DJ_CHANNEL_ID   = "dj_mix_channel"
        const val NOTIFICATION_ID = 505

        const val ACTION_START      = "com.engfred.musicplayer.dj.START"
        const val ACTION_PLAY_PAUSE = "com.engfred.musicplayer.dj.PLAY_PAUSE"
        const val ACTION_PREV       = "com.engfred.musicplayer.dj.PREV"
        const val ACTION_NEXT       = "com.engfred.musicplayer.dj.NEXT"
        const val ACTION_STOP       = "com.engfred.musicplayer.dj.STOP"

        private const val SKIP_DEBOUNCE_MS = 1_500L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        showStartingNotification()
        activePlayerRegistry.onDjMixStarted()

        setupMediaSession()
        observeEngineState()
        observeNextTrackRequests()
        observePrebufferRequests()
        observeEngineSettings()
        observeStopSignal()
        samplerEngine.initialize()
        observeFirstPlay()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()  { crossfadeEngine.playPause() }
                override fun onPause() { crossfadeEngine.playPause() }
                override fun onSkipToPrevious() {
                    val now = System.currentTimeMillis()
                    if (now - lastSkipTimestampMs < SKIP_DEBOUNCE_MS) return
                    lastSkipTimestampMs = now
                    executePrevTrackTransition()
                }
                override fun onSkipToNext() {
                    val now = System.currentTimeMillis()
                    if (now - lastSkipTimestampMs < SKIP_DEBOUNCE_MS) return
                    lastSkipTimestampMs = now
                    val engineState = crossfadeEngine.state.value
                    if (engineState.isCrossfading) return
                    val currentId = engineState.currentTrack?.id ?: return
                    executeNextTrackTransition(currentId, isManualSkip = true)
                }
                override fun onStop() { releaseAndStop() }
            })
        }
    }

    // ── Next track ────────────────────────────────────────────────────────────
    private fun executeNextTrackTransition(currentTrackId: Long, isManualSkip: Boolean = false) {
        val nextTrack = djSessionManager.selectNextTrack(currentTrackId)
        if (nextTrack != null) {
            val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(nextTrack)
            djSessionManager.markTrackPlayed(nextTrack.id)
            crossfadeEngine.queueNextTrack(nextTrack, bpm, firstBeatMs, amplitude)
            Log.d(TAG, "[SKIP] ${if (isManualSkip) "Manual skip" else "Auto-mix queued"} → '${nextTrack.title}'")
        } else {
            Log.d(TAG, "[SKIP] Queue exhausted — DJ Mix will finish after current track.")
        }
    }

    // ── Previous track ────────────────────────────────────────────────────────
    private fun executePrevTrackTransition() {
        val engineState = crossfadeEngine.state.value
        if (engineState.isCrossfading) return
        val currentId = engineState.currentTrack?.id ?: return
        val prevTrack = djSessionManager.skipBack(currentId) ?: run {
            Log.d(TAG, "[PREV] No previous track in history — ignoring")
            return
        }
        val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(prevTrack)
        crossfadeEngine.queueNextTrack(prevTrack, bpm, firstBeatMs, amplitude)
        Log.d(TAG, "[PREV] Crossfading back → '${prevTrack.title}'")
    }

    private fun observeEngineState() {
        serviceScope.launch {
            crossfadeEngine.state.collectLatest { state ->
                // ── BPM sync (keeps engine updated when ViewModel is dead) ──
                val newTrackId = state.currentTrack?.id
                if (newTrackId != null && newTrackId != lastSyncedTrackId) {
                    lastSyncedTrackId = newTrackId
                    val info = djSessionManager.getBpmCacheSnapshot()[newTrackId]
                    if (info != null && !info.analysisFailed) {
                        crossfadeEngine.updateCurrentBpmInfo(
                            bpm              = info.bpm,
                            firstBeatMs      = info.firstBeatMs,
                            amplitude        = info.amplitude,
                            waveformEnvelope = info.waveformEnvelope,
                        )
                    }
                }
                // ─────────────────────────────────────────────────────────────

                updateMediaSession(
                    track     = state.currentTrack,
                    isPlaying = state.isPlaying,
                    position  = state.currentPositionMs,
                    duration  = state.currentDurationMs
                )

                val now = System.currentTimeMillis()
                val trackChanged   = state.currentTrack?.id != lastNotifiedTrackId
                val playingChanged = state.isPlaying != lastNotifiedIsPlaying
                val progressDue    = now - lastProgressNotifyMs > PROGRESS_NOTIFY_INTERVAL_MS

                if (trackChanged || playingChanged || progressDue) {
                    lastNotifiedTrackId   = state.currentTrack?.id
                    lastNotifiedIsPlaying = state.isPlaying
                    lastProgressNotifyMs  = now
                    postNotification(
                        track     = state.currentTrack,
                        isPlaying = state.isPlaying,
                        position  = state.currentPositionMs,
                        duration  = state.currentDurationMs
                    )
                }
            }
        }
    }

    private fun observeNextTrackRequests() {
        serviceScope.launch {
            crossfadeEngine.nextTrackRequest.collect { currentTrackId ->
                val actualCurrentId = crossfadeEngine.state.value.currentTrack?.id
                if (currentTrackId != actualCurrentId) return@collect
                executeNextTrackTransition(currentTrackId, isManualSkip = false)
            }
        }
    }

    private fun observePrebufferRequests() {
        serviceScope.launch {
            crossfadeEngine.prebufferRequest.collect { currentTrackId ->
                val actualCurrentId = crossfadeEngine.state.value.currentTrack?.id
                if (currentTrackId != actualCurrentId) return@collect
                if (crossfadeEngine.state.value.isCrossfading) return@collect

                val nextTrack = djSessionManager.selectNextTrack(currentTrackId) ?: return@collect
                val (_, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(nextTrack)
                crossfadeEngine.prebufferTrack(nextTrack, bpm, amplitude)
                Log.d(TAG, "Prebuffering '${nextTrack.title}'")
            }
        }
    }

    private fun observeEngineSettings() {
        serviceScope.launch {
            var lastPreset: AudioPreset? = null
            settingsRepository.getAppSettings().collect { appSettings ->
                crossfadeEngine.isRealMixMode = appSettings.isRealMixMode
                if (appSettings.audioPreset != lastPreset) {
                    lastPreset = appSettings.audioPreset
                    crossfadeEngine.applyEqPreset(appSettings.audioPreset)
                }

                samplerEngine.isAutoSamplerEnabled =
                    appSettings.autoSamplerEnabled && appSettings.isRealMixMode
                samplerEngine.sampleVolume = appSettings.sampleVolume
            }
        }
    }

    private fun observeStopSignal() {
        serviceScope.launch {
            activePlayerRegistry.stopDjMixSignal.collect {
                Log.d(TAG, "Stop signal received — normal player taking over.")
                releaseAndStop()
            }
        }
    }

    private fun observeFirstPlay() {
        serviceScope.launch {
            crossfadeEngine.state.first { it.isPlaying && !it.isCrossfading }
            samplerEngine.onSessionStarted()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> crossfadeEngine.playPause()
            ACTION_PREV       -> executePrevTrackTransition()
            ACTION_STOP       -> releaseAndStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showStartingNotification() {
        val notification = NotificationCompat.Builder(this, DJ_CHANNEL_ID)
            .setContentTitle("DJ Auto-Mix")
            .setContentText("Starting…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateMediaSession(
        track: AudioFile?,
        isPlaying: Boolean,
        position: Long,
        duration: Long
    ) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position, 1.0f
            ).build()

        mediaSession.setPlaybackState(playbackState)

        if (track != null) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist ?: "Unknown Artist")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
            mediaSession.setMetadata(metadata)
        }
    }

    private fun postNotification(
        track: AudioFile?,
        isPlaying: Boolean,
        position: Long = 0L,
        duration: Long = 0L
    ) {
        if (track == null) return

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_DJ_MIX", true)
        }

        val openAppPi = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val appIconBitmap = runCatching {
            BitmapFactory.decodeResource(resources, applicationInfo.icon)
        }.getOrNull()

        val notification = NotificationCompat.Builder(this, DJ_CHANNEL_ID)
            .setContentTitle(track.title)
            .setContentText(track.artist ?: "Unknown Artist")
            .setSubText("DJ Auto-Mix Active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(appIconBitmap)
            .setContentIntent(openAppPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // action index 0 — Previous
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                getServicePendingIntent(ACTION_PREV)
            )
            // action index 1 — Play/Pause
            .addAction(playPauseIcon, "Play/Pause", getServicePendingIntent(ACTION_PLAY_PAUSE))
            // action index 2 — Stop
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                getServicePendingIntent(ACTION_STOP)
            )
            .setProgress(duration.toInt(), position.toInt(), false)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // prev, play/pause, stop
            )
            .setSilent(true)
            .build()

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "notify() failed, falling back to startForeground: ${e.message}")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AutoMixService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel(DJ_CHANNEL_ID)
            val channel = NotificationChannel(
                DJ_CHANNEL_ID,
                "DJ Mix Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun releaseAndStop() {
        serviceScope.cancel()
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
            samplerEngine.release()
        }
        djSessionManager.endSession()
        activePlayerRegistry.acknowledgeDjMixStopped()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)

        stopSelf()
        Log.i(TAG, "[LIFECYCLE] AutoMixService stopped and notification removed.")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
            samplerEngine.release()
            djSessionManager.endSession()
            activePlayerRegistry.acknowledgeDjMixStopped()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)

        mediaSession.release()
        super.onDestroy()
    }
}