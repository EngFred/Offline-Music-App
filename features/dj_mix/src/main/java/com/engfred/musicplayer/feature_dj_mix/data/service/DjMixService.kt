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
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
import com.engfred.musicplayer.feature_dj_mix.domain.DjSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground Service for the DJ Mix feature.
 *
 * ── What changed in this version ─────────────────────────────────────────────
 * Added [observePrebufferRequests] — new companion to [observeNextTrackRequests].
 *
 * When the position monitor determines that remaining time < crossfadeDurationMs × 3,
 * it emits on [CrossfadeEngine.prebufferRequest]. This service:
 *   1. Selects the next track from DjSessionManager (same algorithm, NOT marked as played)
 *   2. Calls crossfadeEngine.prebufferTrack() to silently load it into the secondary ExoPlayer
 *
 * When the actual crossfade fires shortly after, the secondary player is already in
 * STATE_READY and seeked to firstBeatMs. [executeCrossfade] skips the 2-second buffer
 * wait entirely — zero silence gap.
 *
 * Key distinction: observePrebufferRequests does NOT call markTrackPlayed.
 * That happens only in observeNextTrackRequests when the crossfade actually starts.
 *
 * ── Previously fixed (retained from prior version) ───────────────────────────
 * 1. nextTrackRequest handling lives here (not ViewModel) — survives screen exit
 * 2. startForeground called immediately in onCreate() — satisfies Android 8+ ANR rule
 * 3. onSkipToNext uses DjSessionManager.selectNextTrack (not current track)
 * 4. Engine settings kept in sync after ViewModel cleared
 * 5. ActivePlayerRegistry coordination (stop signal from normal player)
 * 6. Engine released in onDestroy if not already released via ACTION_STOP
 * 7. useHalfwayMix synced from useManualMaxDuration setting
 */
@UnstableApi
@AndroidEntryPoint
class DjMixService : Service() {

    @Inject lateinit var crossfadeEngine: CrossfadeEngine
    @Inject lateinit var djSessionManager: DjSessionManager
    @Inject lateinit var activePlayerRegistry: ActivePlayerRegistry

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Guards against double-release if both ACTION_STOP and onDestroy fire. */
    private var engineReleased = false

    companion object {
        private const val TAG = "DjMixService"
        const val DJ_CHANNEL_ID   = "dj_mix_channel"
        const val NOTIFICATION_ID = 505
        const val ACTION_START      = "com.engfred.musicplayer.dj.START"
        const val ACTION_PLAY_PAUSE = "com.engfred.musicplayer.dj.PLAY_PAUSE"
        const val ACTION_NEXT       = "com.engfred.musicplayer.dj.NEXT"
        const val ACTION_STOP       = "com.engfred.musicplayer.dj.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must call startForeground within 5s of startForegroundService on Android 8+
        showStartingNotification()
        activePlayerRegistry.onDjMixStarted()
        setupMediaSession()
        observeEngineState()
        observeNextTrackRequests()
        observePrebufferRequests()   // ← NEW: wire pre-buffer loop
        observeEngineSettings()
        observeStopSignal()
    }

    // ── Media session ─────────────────────────────────────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()  { crossfadeEngine.playPause() }
                override fun onPause() { crossfadeEngine.playPause() }
                override fun onSkipToNext() {
                    val currentId = crossfadeEngine.state.value.currentTrack?.id ?: return
                    val next = djSessionManager.selectNextTrack(currentId) ?: return
                    val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(next)
                    djSessionManager.markTrackPlayed(next.id)
                    crossfadeEngine.queueNextTrack(next, firstBeatMs, bpm, amplitude)
                }
                override fun onStop() { releaseAndStop() }
            })
        }
    }

    // ── Core observers ────────────────────────────────────────────────────────

    private fun observeEngineState() {
        serviceScope.launch {
            crossfadeEngine.state.collectLatest { state ->
                updateNotification(
                    track    = state.currentTrack,
                    isPlaying = state.isPlaying,
                    position = state.currentPositionMs,
                    duration = state.currentDurationMs
                )
            }
        }
    }

    /**
     * Owns the automatic next-track crossfade loop for the lifetime of this Service.
     * Lives here (not ViewModel) so it continues when the user leaves the DJ screen.
     */
    private fun observeNextTrackRequests() {
        serviceScope.launch {
            crossfadeEngine.nextTrackRequest.collect { currentTrackId ->
                // Guard against stale replay emissions from a previous session
                val actualCurrentId = crossfadeEngine.state.value.currentTrack?.id
                if (currentTrackId != actualCurrentId) {
                    Log.d(TAG, "Ignored stale nextTrackRequest for ID $currentTrackId")
                    return@collect
                }
                Log.d(TAG, "nextTrackRequest received for trackId=$currentTrackId")
                val nextTrack = djSessionManager.selectNextTrack(currentTrackId)
                if (nextTrack != null) {
                    val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(nextTrack)
                    djSessionManager.markTrackPlayed(nextTrack.id)
                    crossfadeEngine.queueNextTrack(nextTrack, firstBeatMs, bpm, amplitude)
                    Log.d(TAG, "Queued next track: '${nextTrack.title}'")
                } else {
                    Log.d(TAG, "Queue exhausted — DJ Mix will finish after current track.")
                }
            }
        }
    }

    /**
     * Observes the engine's pre-buffer signal and silently loads the next track into the
     * secondary ExoPlayer BEFORE the crossfade trigger fires.
     *
     * Design: selectNextTrack is called WITHOUT markTrackPlayed so the same track will be
     * selected again by observeNextTrackRequests when the crossfade actually starts. This is
     * intentional — pre-buffering does not advance the queue position.
     *
     * If the queue is exhausted or the engine is already crossfading, this is a no-op.
     */
    private fun observePrebufferRequests() {
        serviceScope.launch {
            crossfadeEngine.prebufferRequest.collect { currentTrackId ->
                val actualCurrentId = crossfadeEngine.state.value.currentTrack?.id
                if (currentTrackId != actualCurrentId) {
                    Log.d(TAG, "Ignored stale prebufferRequest for ID $currentTrackId")
                    return@collect
                }
                // Do not prebuffer if a crossfade is already in progress
                if (crossfadeEngine.state.value.isCrossfading) return@collect

                Log.d(TAG, "prebufferRequest received for trackId=$currentTrackId")
                val nextTrack = djSessionManager.selectNextTrack(currentTrackId) ?: return@collect
                val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(nextTrack)
                // NOT marking as played here — that happens in observeNextTrackRequests
                crossfadeEngine.prebufferTrack(nextTrack, firstBeatMs, bpm, amplitude)
                Log.d(TAG, "Prebuffering '${nextTrack.title}' (bpm=$bpm firstBeatMs=$firstBeatMs)")
            }
        }
    }

    private fun observeEngineSettings() {
        serviceScope.launch {
            djSessionManager.settings.collect { settings ->
                crossfadeEngine.crossfadeDurationMs = settings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = settings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = settings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix       = !settings.useManualMaxDuration
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

    // ── onStartCommand ────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> crossfadeEngine.playPause()
            ACTION_STOP       -> releaseAndStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ──────────────────────────────────────────────────────────

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

    private fun updateNotification(
        track: AudioFile?,
        isPlaying: Boolean,
        position: Long = 0L,
        duration: Long = 0L
    ) {
        if (track == null) return

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position,
                1.0f
            ).build()
        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist ?: "Unknown Artist")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(metadata)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause
        else           android.R.drawable.ic_media_play

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_DJ_MIX", true)
        }
        val openAppPi = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
            .addAction(playPauseIcon, "Play/Pause", getServicePendingIntent(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", getServicePendingIntent(ACTION_STOP))
            .setProgress(duration.toInt(), position.toInt(), false)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun getServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, DjMixService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DJ_CHANNEL_ID, "DJ Mix Playback", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun releaseAndStop() {
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
        }
        djSessionManager.endSession()
        activePlayerRegistry.onDjMixStopped()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
            djSessionManager.endSession()
            activePlayerRegistry.onDjMixStopped()
        }
        mediaSession.release()
        super.onDestroy()
    }
}