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

/**
 * Foreground Service for the DJ Mix feature.
 *
 * ── Notification update strategy ─────────────────────────────────────────────
 * The CrossfadeEngine state flow emits on EVERY position poll (300 ms normal,
 * 50 ms during fast-poll near a transition). Calling startForeground() or even
 * NotificationManager.notify() on every emission floods the Android notification
 * binder with up to 20 heavyweight IPC calls per second. On budget devices this
 * directly causes the quick-settings panel and notification shade to lag/freeze.
 *
 * Fix: separate concerns into two paths:
 *
 * A) MEDIA SESSION (lightweight, every emission)
 *    updateMediaSession() sets PlaybackStateCompat (position + playing flag)
 *    on the MediaSession. The system reads this directly for lock-screen art and
 *    the media control row — no notification rebuild required.
 *
 * B) NOTIFICATION (throttled, only when content actually changes)
 *    postNotification() rebuilds and posts the notification only when:
 *    • The track changes (new title/artist after a crossfade)
 *    • isPlaying flips (user hit play/pause)
 *    • More than PROGRESS_NOTIFY_INTERVAL_MS have elapsed (keeps the progress
 *      bar roughly in sync without spamming; users don't read ms on a DJ mix).
 *
 * This reduces notification rebuilds from ~20/s → at most once every few
 * seconds, eliminating the system binder backlog that was causing the freeze.
 *
 * ── Teardown fix ─────────────────────────────────────────────────────────────
 * The deprecated stopForeground(Boolean) behaves inconsistently on API 26+.
 * We now use stopForeground(STOP_FOREGROUND_REMOVE) on API 24+ so the
 * notification is immediately removed from the shade, clearing the UI freeze
 * the stale DJ-mix notification was causing while the library player started.
 */
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

    // ── Notification throttle state ───────────────────────────────────────────
    // Track the last values we posted so we can skip redundant rebuilds.
    @Volatile private var lastNotifiedTrackId: Long?   = null
    @Volatile private var lastNotifiedIsPlaying: Boolean? = null
    @Volatile private var lastProgressNotifyMs: Long   = 0L

    /**
     * How often the notification progress bar is refreshed when nothing else
     * changed. 5 s is imperceptible to users of a DJ mix and keeps binder
     * traffic to a minimum.
     */
    private val PROGRESS_NOTIFY_INTERVAL_MS = 5_000L

    companion object {
        private const val TAG = "DjMixService"
        const val DJ_CHANNEL_ID   = "dj_mix_channel"
        const val NOTIFICATION_ID = 505
        const val ACTION_START      = "com.engfred.musicplayer.dj.START"
        const val ACTION_PLAY_PAUSE = "com.engfred.musicplayer.dj.PLAY_PAUSE"
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
        observeAppSettings()
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

                override fun onSkipToNext() {
                    val now = System.currentTimeMillis()
                    if (now - lastSkipTimestampMs < SKIP_DEBOUNCE_MS) {
                        Log.d(TAG, "[SKIP] Debounced (${now - lastSkipTimestampMs}ms < ${SKIP_DEBOUNCE_MS}ms)")
                        return
                    }
                    lastSkipTimestampMs = now

                    val engineState = crossfadeEngine.state.value
                    if (engineState.isCrossfading) {
                        Log.d(TAG, "[SKIP] Crossfade in progress — skip ignored")
                        return
                    }

                    val currentId = engineState.currentTrack?.id ?: return
                    val nextTrack = djSessionManager.selectNextTrack(currentId) ?: return
                    val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(nextTrack)
                    djSessionManager.markTrackPlayed(nextTrack.id)
                    crossfadeEngine.queueNextTrack(nextTrack, firstBeatMs, bpm, amplitude)
                    Log.d(TAG, "[SKIP] Manual skip → '${nextTrack.title}'")
                }

                override fun onStop() { releaseAndStop() }
            })
        }
    }

    /**
     * Observes engine state with a TWO-PATH strategy:
     *
     * Path A — MediaSession (every emission, very cheap):
     *   Keeps position and playing state accurate for lock-screen / media controls.
     *
     * Path B — Notification rebuild (throttled):
     *   Only rebuilds and posts the notification when:
     *   • track id changed  (crossfade completed → show new song title)
     *   • isPlaying changed (play/pause tapped)
     *   • progress interval elapsed (keeps progress bar loosely accurate)
     *
     * This collapses ~20 startForeground()/notify() calls per second down to at
     * most 1 per PROGRESS_NOTIFY_INTERVAL_MS, eliminating the binder backlog
     * that caused the quick-settings panel to freeze.
     */
    private fun observeEngineState() {
        serviceScope.launch {
            crossfadeEngine.state.collectLatest { state ->

                // ── Path A: always update MediaSession (lightweight IPC) ──────
                updateMediaSession(
                    track     = state.currentTrack,
                    isPlaying = state.isPlaying,
                    position  = state.currentPositionMs,
                    duration  = state.currentDurationMs
                )

                // ── Path B: throttled notification rebuild ────────────────────
                val now = System.currentTimeMillis()
                val trackChanged    = state.currentTrack?.id != lastNotifiedTrackId
                val playingChanged  = state.isPlaying != lastNotifiedIsPlaying
                val progressDue     = now - lastProgressNotifyMs > PROGRESS_NOTIFY_INTERVAL_MS

                if (trackChanged || playingChanged || progressDue) {
                    lastNotifiedTrackId  = state.currentTrack?.id
                    lastNotifiedIsPlaying = state.isPlaying
                    lastProgressNotifyMs = now

                    postNotification(
                        track    = state.currentTrack,
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
                if (currentTrackId != actualCurrentId) {
                    Log.d(TAG, "Ignored stale nextTrackRequest for ID $currentTrackId")
                    return@collect
                }
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

    private fun observePrebufferRequests() {
        serviceScope.launch {
            crossfadeEngine.prebufferRequest.collect { currentTrackId ->
                val actualCurrentId = crossfadeEngine.state.value.currentTrack?.id
                if (currentTrackId != actualCurrentId) return@collect
                if (crossfadeEngine.state.value.isCrossfading) return@collect

                val nextTrack = djSessionManager.selectNextTrack(currentTrackId) ?: return@collect
                val (firstBeatMs, bpm, amplitude) = djSessionManager.getTrackTransitionInfo(nextTrack)
                crossfadeEngine.prebufferTrack(nextTrack, firstBeatMs, bpm, amplitude)
                Log.d(TAG, "Prebuffering '${nextTrack.title}'")
            }
        }
    }

    private fun observeEngineSettings() {
        serviceScope.launch {
            djSessionManager.settings.collect { settings ->
//                crossfadeEngine.crossfadeDurationMs = settings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = settings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = settings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix       = !settings.useManualMaxDuration
                crossfadeEngine.cuePointOffsetMs    = settings.cuePointOffsetSec * 1000L
                samplerEngine.isAutoSamplerEnabled  = settings.autoSamplerEnabled && settings.isRealMixMode
                samplerEngine.sampleVolume          = settings.sampleVolume
            }
        }
    }

    private fun observeAppSettings() {
        serviceScope.launch {
            var lastPreset: AudioPreset? = null
            settingsRepository.getAppSettings().collect { appSettings ->
                if (appSettings.audioPreset != lastPreset) {
                    lastPreset = appSettings.audioPreset
                    crossfadeEngine.applyEqPreset(appSettings.audioPreset)
                    Log.d(TAG, "[EQ] Preset forwarded to DJ engine: ${appSettings.audioPreset}")
                }
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
            ACTION_STOP       -> releaseAndStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═════════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═════════════════════════════════════════════════════════════════════════

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

    /**
     * Updates ONLY the MediaSession playback state (position + playing flag).
     *
     * This is cheap — a single Binder call to the system MediaSession. It keeps
     * the lock-screen media controls and any external media controller accurate
     * without triggering a notification rebuild. Called on every state emission.
     */
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

    /**
     * Rebuilds and posts the full notification.
     *
     * Called ONLY when track, isPlaying, or a timed progress update is due.
     * Uses [NotificationManager.notify] after the initial [startForeground] call
     * to avoid the overhead of re-attaching the service to the notification on
     * every update. Both paths produce identical visual results.
     */
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
            .addAction(playPauseIcon, "Play/Pause", getServicePendingIntent(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", getServicePendingIntent(ACTION_STOP))
            .setProgress(duration.toInt(), position.toInt(), false)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setSilent(true)
            .build()

        // Use notify() rather than startForeground() for updates — both update
        // the same notification but notify() skips the expensive foreground-service
        // rebinding overhead that was flooding the system binder.
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Fall back to startForeground if notify fails (e.g. service not yet started)
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

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Shutdown sequence — ORDER MATTERS:
     *
     * 1. serviceScope.cancel()           — kills all coroutines immediately so
     *                                      observeEngineState() CANNOT re-post
     *                                      notification 505 after we remove it.
     * 2. crossfadeEngine.release()       — releases ExoPlayers + audio focus.
     * 3. samplerEngine.release()
     * 4. djSessionManager.endSession()
     * 5. activePlayerRegistry            — called AFTER engine release so audio
     *    .acknowledgeDjMixStopped()        focus is genuinely free before the
     *                                      normal player resumes.
     * 6. Explicit notification removal   — stopForeground(STOP_FOREGROUND_REMOVE)
     *    + stopSelf()                       immediately removes the notification
     *                                      from the shade, ending the UI freeze
     *                                      that the stale DJ notification caused.
     *
     * WHY the notification was lingering:
     * The old code used the deprecated stopForeground(Boolean=true). On API 26+
     * this is unreliable — the notification can stay visible for several seconds
     * after the service stops. STOP_FOREGROUND_REMOVE atomically detaches AND
     * cancels the notification in a single system call.
     */
    private fun releaseAndStop() {
        // Step 1: Cancel all coroutines — observeEngineState() can no longer
        // re-post the notification after this line.
        serviceScope.cancel()

        // Steps 2–4: Release engines.
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
            samplerEngine.release()
        }
        djSessionManager.endSession()

        // Step 5: Signal registry AFTER engine release.
        activePlayerRegistry.acknowledgeDjMixStopped()

        // Step 6: Remove notification immediately and stop.
        //
        // stopForeground(STOP_FOREGROUND_REMOVE) atomically:
        //   a) Detaches this notification from the foreground-service binding
        //   b) Cancels / removes the notification from the shade
        // This replaces the deprecated stopForeground(true) which was unreliable
        // on API 26+ and left the notification visible for several seconds,
        // blocking quick-settings interactions during that window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // Belt-and-suspenders: explicit cancel ensures it is gone even if
        // stopForeground has an edge-case delay on some OEM ROMs.
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