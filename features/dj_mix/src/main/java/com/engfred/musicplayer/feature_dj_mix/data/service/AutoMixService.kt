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
 * ── EQ Preset Forwarding ─────────────────────────────────────────────────────
 * [observeAppSettings] watches the same [SettingsRepository.getAppSettings] flow
 * that [PlaybackService] already observes for [AudioPreset] changes, and forwards
 * the current preset to [CrossfadeEngine.applyEqPreset]. This gives the DJ mix
 * the same equaliser the user set in Settings — one global EQ, no separate
 * "DJ EQ" preference needed.
 *
 * The observation is kept in this service (rather than inside CrossfadeEngine)
 * for consistency with how every other engine property is wired — settings
 * ownership lives in the service layer, not the engine.
 *
 * ── Race condition fix (ActivePlayerRegistry) ────────────────────────────────
 * Previously releaseAndStop() called activePlayerRegistry.onDjMixStopped(),
 * which was the same as acknowledgeDjMixStopped() but named incorrectly. The
 * important change is ORDER: acknowledgeDjMixStopped() is now called AFTER the
 * engine is released, not before. This ensures PlaybackControllerImpl only
 * resumes un-suppressed playback once audio focus is genuinely free.
 *
 * ── Notification ghost bug fix ────────────────────────────────────────────────
 * serviceScope is cancelled at the TOP of releaseAndStop() so observeEngineState()
 * can never re-post notification 505 after stopForeground().
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

                    // ── Debounce guard ────────────────────────────────────────────
                    // Prevents rapid double-taps from stacking crossfades.
                    // 1 500 ms is enough time for the UI to update and the user to
                    // see the state change; short enough not to feel sluggish.
                    if (now - lastSkipTimestampMs < SKIP_DEBOUNCE_MS) {
                        Log.d(TAG, "[SKIP] Debounced (${now - lastSkipTimestampMs}ms < ${SKIP_DEBOUNCE_MS}ms)")
                        return
                    }
                    lastSkipTimestampMs = now

                    // ── Already crossfading guard ─────────────────────────────────
                    // queueNextTrack() already stores a pending track when isCrossfading
                    // is true, but we want to skip against the ACTUAL upcoming track
                    // (the one that will be "current" after the in-progress fade),
                    // not the one that is currently fading out.
                    // Reading currentTrack while crossfading gives the outgoing track —
                    // the incoming track is not yet reflected in state. So we only
                    // allow one pending queue-up during an active crossfade.
                    val engineState = crossfadeEngine.state.value
                    if (engineState.isCrossfading) {
                        Log.d(TAG, "[SKIP] Crossfade in progress — skip ignored (one pending slot already handled by engine)")
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

    private fun observeEngineState() {
        serviceScope.launch {
            crossfadeEngine.state.collectLatest { state ->
                updateNotification(
                    track     = state.currentTrack,
                    isPlaying = state.isPlaying,
                    position  = state.currentPositionMs,
                    duration  = state.currentDurationMs
                )
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

    /**
     * Mirrors CrossfadeEngine settings from the DJ-specific persisted store.
     *
     * This observer is intentionally separate from MixStudioViewModel's
     * observeSettings(). The ViewModel is destroyed when the UI goes to
     * background; this ensures the engine stays in sync while the service
     * continues running headlessly. When both are alive, writes are
     * redundant but always consistent (same source flow, same values).
     *
     * ⚠️ If you add a new engine property to MixStudioSettings, add it
     *    here AND in MixStudioViewModel.observeSettings(). The two blocks
     *    must stay in sync.
     */
    private fun observeEngineSettings() {
        serviceScope.launch {
            djSessionManager.settings.collect { settings ->
                crossfadeEngine.crossfadeDurationMs = settings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = settings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = settings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix       = !settings.useManualMaxDuration
                crossfadeEngine.cuePointOffsetMs    = settings.cuePointOffsetSec * 1000L
                samplerEngine.isAutoSamplerEnabled  = settings.autoSamplerEnabled && settings.isRealMixMode
                samplerEngine.sampleVolume          = settings.sampleVolume
            }
        }
    }

    /**
     * Forwards the global [AppSettings.audioPreset] to [CrossfadeEngine.applyEqPreset].
     *
     * This is the DJ-side mirror of the equivalent block in [PlaybackService] —
     * both observe the same settings flow and call [setPreset] on their respective
     * processors. The result is one global EQ that applies to all audio, whether
     * the normal player or the DJ mix is active.
     *
     * Kept here (rather than inside CrossfadeEngine) so all settings-to-engine
     * wiring lives in the service layer, consistent with the rest of the codebase.
     */
    private fun observeAppSettings() {
        serviceScope.launch {
            var lastPreset: AudioPreset? = null
            settingsRepository.getAppSettings().collect { appSettings ->
                if (appSettings.audioPreset != lastPreset) {
                    lastPreset = appSettings.audioPreset  // ✅
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
                position, 1.0f
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
            .build()
        startForeground(NOTIFICATION_ID, notification)
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Shutdown sequence — ORDER MATTERS:
     *
     * 1. serviceScope.cancel()        — stops all coroutines; observeEngineState()
     *                                   can no longer re-post the notification.
     * 2. crossfadeEngine.release()    — releases audio focus. Emissions are now
     *                                   ignored because the scope is cancelled.
     * 3. samplerEngine.release()
     * 4. djSessionManager.endSession()
     * 5. activePlayerRegistry         — acknowledgeDjMixStopped() is called LAST,
     *    .acknowledgeDjMixStopped()     after the engine has fully released audio
     *                                   focus. This closes the race window where
     *                                   both players could briefly hold audio focus.
     * 6. Explicit notification cancel + stopForeground + stopSelf.
     */
    private fun releaseAndStop() {
        // Step 1: Kill all coroutines so nothing can re-post the notification.
        serviceScope.cancel()

        // Steps 2-4: Release engines and session.
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
            samplerEngine.release()
        }
        djSessionManager.endSession()

        // Step 5: FIX — signal registry AFTER engine release, not before.
        // PlaybackControllerImpl watches isDjMixActive. Setting it to false here
        // (via acknowledgeDjMixStopped) means audio focus is already free when
        // the normal player resumes — no overlap window.
        activePlayerRegistry.acknowledgeDjMixStopped()

        // Step 6: Remove notification and stop service.
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID)
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (!engineReleased) {
            engineReleased = true
            crossfadeEngine.release()
            samplerEngine.release()
            djSessionManager.endSession()
            // Guard: acknowledgeDjMixStopped() is idempotent (sets false to false)
            activePlayerRegistry.acknowledgeDjMixStopped()
        }
        mediaSession.release()
        super.onDestroy()
    }
}