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
 * ── BUG FIXES in this version ────────────────────────────────────────────────
 * 1. nextTrackRequest handling moved HERE from DjMixViewModel.
 * Previously, when the user left the DJ screen the ViewModel was cleared,
 * nextTrackRequest emissions went unhandled, and the queue stopped after the
 * current track. The Service has the same lifecycle as playback, so it is the
 * correct owner of this logic. [DjSessionManager] provides the stateful
 * selectNextTrack() method.
 *
 * 2. startForeground called immediately in onCreate().
 * On Android 8+, a foreground service started via startForegroundService()
 * must call startForeground() within 5 seconds or the OS throws an ANR.
 * The old code called startForeground only inside updateNotification(), which
 * returned early when currentTrack == null — meaning it was never called until
 * the engine emitted its first state update. Fixed by calling
 * showStartingNotification() at the top of onCreate().
 *
 * 3. onSkipToNext bug fixed.
 * Old code: crossfadeEngine.queueNextTrack(currentTrack) — passed the CURRENT
 * track as the next track, causing it to loop on itself.
 * Fixed: djSessionManager.selectNextTrack(currentId) selects the correct track.
 *
 * 4. Engine settings kept in sync after ViewModel is cleared.
 * Service now observes DjSessionManager.settings and applies crossfadeDurationMs,
 * isRealMixMode, and maxTrackDurationMs to the engine continuously.
 *
 * 5. ActivePlayerRegistry coordination.
 * Service observes stopDjMixSignal (emitted by PlaybackControllerImpl when a
 * normal song is tapped) and shuts itself down cleanly.
 *
 * 6. Engine released in onDestroy if not already released via ACTION_STOP.
 *
 * NEW: Syncs the new useHalfwayMix flag based on useManualMaxDuration.
 */
@UnstableApi
@AndroidEntryPoint
class DjMixService : Service() {
    @Inject lateinit var crossfadeEngine: CrossfadeEngine
    @Inject lateinit var djSessionManager: DjSessionManager
    @Inject lateinit var activePlayerRegistry: ActivePlayerRegistry
    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /**
     * Tracks whether the engine was explicitly released inside this service lifecycle.
     * Prevents double-release in onDestroy if ACTION_STOP was already handled.
     */
    private var engineReleased = false
    companion object {
        private const val TAG = "DjMixService"
        const val DJ_CHANNEL_ID = "dj_mix_channel"
        const val NOTIFICATION_ID = 505
        const val ACTION_START = "com.engfred.musicplayer.dj.START"
        const val ACTION_PLAY_PAUSE = "com.engfred.musicplayer.dj.PLAY_PAUSE"
        const val ACTION_NEXT = "com.engfred.musicplayer.dj.NEXT"
        const val ACTION_STOP = "com.engfred.musicplayer.dj.STOP"
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // FIX #2: Call startForeground immediately — must happen within 5 s of
        // startForegroundService() on Android 8+, regardless of engine state.
        showStartingNotification()
        // Mark DJ as active so PlaybackControllerImpl pauses the normal player.
        activePlayerRegistry.onDjMixStarted()
        setupMediaSession()
        observeEngineState()
        observeNextTrackRequests() // FIX #1: Service owns this, not ViewModel
        observeEngineSettings() // FIX #4: Keep engine in sync after ViewModel cleared
        observeStopSignal() // FIX #5: Stop when normal playback takes over
    }
    // ── Media session ─────────────────────────────────────────────────────────
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { crossfadeEngine.playPause() }
                override fun onPause() { crossfadeEngine.playPause() }
                /**
                 * FIX #3: Old code passed currentTrack as the next track, causing
                 * the engine to loop on itself. Now uses DjSessionManager to pick
                 * the correct next track.
                 */
                override fun onSkipToNext() {
                    val currentId = crossfadeEngine.state.value.currentTrack?.id ?: return
                    val next = djSessionManager.selectNextTrack(currentId) ?: return
                    val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(next)
                    djSessionManager.markTrackPlayed(next.id)
                    crossfadeEngine.queueNextTrack(next, firstBeatMs, bpm, amplitude)
                }
                override fun onStop() {
                    releaseAndStop()
                }
            })
        }
    }
    // ── Core observers ────────────────────────────────────────────────────────
    /** Updates the notification whenever the engine state changes. */
    private fun observeEngineState() {
        serviceScope.launch {
            crossfadeEngine.state.collectLatest { state ->
                updateNotification(state.currentTrack, state.isPlaying)
            }
        }
    }
    /**
     * FIX #1: The entire next-track selection loop now lives here in the Service.
     *
     * Previously this was in DjMixViewModel.observeNextTrackRequests(). When the
     * user left the DJ screen the ViewModel was cleared, the coroutine was cancelled,
     * and the queue stopped advancing. Since this Service lives as long as playback
     * continues, it is the correct lifecycle owner.
     */
    private fun observeNextTrackRequests() {
        serviceScope.launch {
            crossfadeEngine.nextTrackRequest.collect { currentTrackId ->
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
     * FIX #4: Applies engine settings from DjSessionManager continuously.
     * After ViewModel is cleared, user-configured settings (crossfade duration,
     * real-mix mode, max duration) are still respected.
     *
     * NEW: Also syncs useHalfwayMix = !useManualMaxDuration
     */
    private fun observeEngineSettings() {
        serviceScope.launch {
            djSessionManager.settings.collect { settings ->
                crossfadeEngine.crossfadeDurationMs = settings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode = settings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs = settings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix = !settings.useManualMaxDuration
            }
        }
    }
    /**
     * FIX #5: Stop this service when PlaybackControllerImpl starts a normal song.
     * PlaybackControllerImpl calls activePlayerRegistry.requestStopDjMix() which
     * emits on stopDjMixSignal.
     */
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
            ACTION_STOP -> releaseAndStop()
            // ACTION_START: startForeground already called in onCreate; nothing extra needed.
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    // ── Notification ──────────────────────────────────────────────────────────
    /**
     * Shows a minimal placeholder notification immediately.
     * Required to satisfy Android 8+ foreground service contract.
     */
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
    private fun updateNotification(track: AudioFile?, isPlaying: Boolean) {
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
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1.0f
            ).build()
        mediaSession.setPlaybackState(playbackState)
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist ?: "Unknown Artist")
            .build()
        mediaSession.setMetadata(metadata)
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        // NEW: Add deep link extra to launch intent
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_DJ_MIX", true)   // <-- NEW: Tell MainActivity to open DJ screen
        }
        val openAppPi = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
    /**
     * Clean shutdown: release engine, update registries, stop foreground.
     * Called from ACTION_STOP, MediaSession onStop, and the stop signal observer.
     */
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
        // FIX: Release engine if it wasn't already released via releaseAndStop().
        // This handles the case where the OS kills the service unexpectedly.
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