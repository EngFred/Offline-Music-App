package com.engfred.musicplayer.feature_trim.utils

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import javax.inject.Inject

private const val TAG = "PreviewPlayerManager"

class PreviewPlayerManager @Inject constructor(
    private val application: Application
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var clipStartMs: Long = 0L
    private var clipEndMs: Long = 0L
    private var isPlayerReady = false  // Track readiness for robust operations
    private var isClipPrepared = false  // Track if current clip media is loaded
    private var lastUri: Uri? = null
    private var lastStartMs: Long = -1L
    private var lastEndMs: Long = -1L
    private var pendingSeekToMs: Long? = null  // For deferred seeks (e.g., before player ready)

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(application).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    _isPlaying.value = isPlayingNow
                    Log.d(TAG, "Playback isPlaying changed to: $isPlayingNow (suppression: ${p.playbackSuppressionReason})")
                    if (isPlayingNow) {
                        // Start position updates only when actually playing
                        if (positionJob?.isActive != true && clipEndMs > clipStartMs) {
                            startPositionUpdates()
                        }
                    } else {
                        positionJob?.cancel()
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "Playback ended")
                            stopInternal()
                        }
                        Player.STATE_READY -> {
                            isPlayerReady = true
                            Log.d(TAG, "Player ready; total duration: ${p.duration}ms")
                            // Handle pending or initial seek
                            pendingSeekToMs?.let { pending ->
                                safeSeekTo(pending)
                                val relPos = (pending - clipStartMs).coerceIn(0L, (clipEndMs - clipStartMs).coerceAtLeast(1L))
                                _positionMs.value = relPos
                                pendingSeekToMs = null
                                Log.d(TAG, "Handled pending seek to $pending ms; rel pos: $relPos")
                            } ?: run {
                                // Initial seek if current position < clip start
                                if (clipStartMs > 0L && (p.currentPosition == C.TIME_UNSET || p.currentPosition < clipStartMs)) {
                                    safeSeekTo(clipStartMs)
                                    _positionMs.value = 0L
                                    Log.d(TAG, "Initial seek to clip start: $clipStartMs ms")
                                }
                            }
                            // Now start playback after potential seek
                            p.playWhenReady = true
                            Log.d(TAG, "Set playWhenReady=true after potential seek")
                        }
                        Player.STATE_BUFFERING -> {
                            Log.v(TAG, "Player buffering")
                        }
                        else -> {
                            Log.v(TAG, "Player state changed to: $state")
                        }
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    val absPos = if (newPosition.positionMs == C.TIME_UNSET) 0L else newPosition.positionMs.coerceAtLeast(0L)
                    val relPos = (absPos - clipStartMs).coerceIn(0L, (clipEndMs - clipStartMs).coerceAtLeast(1L))
                    _positionMs.value = relPos
                    Log.d(TAG, "Position discontinuity updated relative pos to $relPos ms (abs: $absPos)")
                }

                override fun onPlayerError(error: PlaybackException) {
                    val errorMsg = error.message ?: "Unknown playback error"
                    Log.e(TAG, "Playback error: $errorMsg", error)
                    _error.value = "Playback failed. Please try again."
                    pauseInternal()
                    // Auto-recover: clear error after 5s if not playing
                    scope.launch {
                        delay(5000)
                        if (!_isPlaying.value) {
                            _error.value = null
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Log.d(TAG, "Media item transition: $reason")
                }
            })
            p.setAudioAttributes(
                androidx.media3.common.AudioAttributes.DEFAULT,
                true
            )
        }
    }

    // Noisy receiver: headphone unplug -> pause
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy; pausing playback")
                pauseInternal()
            }
        }
    }

    private var positionJob: Job? = null

    private companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 150L
    }

    init {
        try {
            application.registerReceiver(
                noisyReceiver,
                IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED // Added for Android 14+ security
            )
            Log.d(TAG, "Noisy receiver registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register noisy receiver", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid intent filter for noisy receiver", e)
        }
    }

    private fun safeSeekTo(positionMs: Long) {
        if (!isPlayerReady) {
            Log.w(TAG, "Seek skipped: player not ready yet")
            return
        }
        try {
            player.seekTo(positionMs.coerceAtLeast(0L))
            Log.d(TAG, "Safely seeked to ${positionMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error during safe seek to ${positionMs}ms", e)
            _error.value = "Unable to seek. Please try playing again."
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        if (clipEndMs <= clipStartMs) {
            Log.w(TAG, "Invalid clip range; skipping position updates")
            return
        }
        positionJob = scope.launch {
            try {
                while (isActive && player.isPlaying) {
                    val absPos = if (player.currentPosition == C.TIME_UNSET) 0L else player.currentPosition.coerceAtLeast(0L)
                    val relPos = (absPos - clipStartMs).coerceIn(0L, (clipEndMs - clipStartMs).coerceAtLeast(1L))
                    _positionMs.value = relPos

                    // Check if we've reached the end of the clip (with tolerance for drift)
                    if (absPos >= clipEndMs - 100L) {  // 100ms tolerance
                        Log.d(TAG, "Reached end of clip at ${absPos}ms; pausing")
                        pauseInternal()
                        _positionMs.value = clipEndMs - clipStartMs  // Snap to end
                        break
                    }

                    delay(POSITION_UPDATE_INTERVAL_MS)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in position update loop", e)
                    _error.value = "Playback interrupted. Please try playing again."
                } else {
                    Log.d(TAG, "Position update loop cancelled normally")
                }
            }
        }
        Log.d(TAG, "Position updates started")
    }

    /**
     * Play a clip: uri is full audio file URI, startMs and endMs are absolute positions (ms)
     * Resumes if same clip already prepared; otherwise full setup.
     * Allows full track preview (start=0, end=duration) on load without trim.
     */
    fun playClip(uri: Uri, startMs: Long, endMs: Long) {
        if (endMs <= startMs) {
            val errorMsg = "Invalid clip range: end must be after start"
            Log.e(TAG, errorMsg)
            _error.value = "Please set a valid preview range."
            return
        }
        if (startMs < 0L) {
            val errorMsg = "Start time must be non-negative"
            Log.e(TAG, errorMsg)
            _error.value = errorMsg
            return
        }
        if (uri == Uri.EMPTY) {
            val errorMsg = "Invalid URI provided"
            Log.e(TAG, errorMsg)
            _error.value = errorMsg
            return
        }

        Log.d(TAG, "Starting/resuming preview clip: $startMs-$endMs ms for URI $uri")

        // Check for same clip
        if (lastUri == uri && lastStartMs == startMs && lastEndMs == endMs && isClipPrepared) {
            if (isPlayerReady) {
                Log.d(TAG, "Resuming existing clip from current position")
                player.playWhenReady = true
                // Sync relative position immediately
                val absPos = if (player.currentPosition == C.TIME_UNSET) startMs else player.currentPosition.coerceAtLeast(startMs)
                _positionMs.value = (absPos - startMs).coerceAtLeast(0L)
                // Updates will start on isPlaying true
                return
            } else {
                Log.d(TAG, "Ignoring duplicate play request for same clip during preparation")
                return
            }
        }

        clipStartMs = startMs
        clipEndMs = endMs
        isPlayerReady = false  // Reset readiness
        isClipPrepared = false
        pendingSeekToMs = null  // Clear any pending on new clip

        // Stop and clear previous media only if different clip
        try {
            if (lastUri != uri || lastStartMs != startMs || lastEndMs != endMs) {
                player.stop()
                player.clearMediaItems()
                isPlayerReady = false
                isClipPrepared = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping/clearing player before new clip", e)
        }

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = false  // Defer playback until after seek in ready callback
            _positionMs.value = 0L  // Reset relative position
            _error.value = null // Clear any previous errors on successful start
            isClipPrepared = true
            lastUri = uri
            lastStartMs = startMs
            lastEndMs = endMs
            Log.d(TAG, "Media set and prepared with playWhenReady=false, awaiting ready callback for seek/play")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing media item for URI $uri", e)
            _error.value = "Failed to load audio. Check file and try again."
            isClipPrepared = false
            lastUri = null
            lastStartMs = -1L
            lastEndMs = -1L
        }
    }

    fun pause() {
        Log.d(TAG, "Pause requested")
        pauseInternal()
    }

    private fun pauseInternal() {
        try {
            player.playWhenReady = false
            positionJob?.cancel()
            _error.value = null // Clear transient errors on pause
            Log.d(TAG, "Player paused internally (position preserved: ${player.currentPosition}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing player", e)
            _error.value = "Pause failed. Please try again."
        }
    }

    fun stop() {
        Log.d(TAG, "Stop requested")
        stopInternal()
    }

    private fun stopInternal() {
        try {
            player.stop()
            player.clearMediaItems()
            _positionMs.value = 0L
            positionJob?.cancel()
            isPlayerReady = false
            isClipPrepared = false
            lastUri = null
            lastStartMs = -1L
            lastEndMs = -1L
            pendingSeekToMs = null
            _error.value = null
            Log.d(TAG, "Player stopped internally (clip reset)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping player", e)
            _error.value = "Stop failed. Please try again."
        }
    }

    fun seekToStartOfClip() {
        if (!isClipPrepared) {
            Log.w(TAG, "Cannot seek: clip not prepared")
            _error.value = "Please start playback first."
            return
        }
        Log.d(TAG, "Seeking to clip start: $clipStartMs ms")
        pendingSeekToMs = clipStartMs
        // Attempt immediate seek if ready
        if (isPlayerReady) {
            safeSeekTo(clipStartMs)
            _positionMs.value = 0L
            pendingSeekToMs = null
            Log.d(TAG, "Immediate seek performed; rel pos set to 0")
        } else {
            Log.d(TAG, "Seek queued as pending until player ready")
        }
    }

    fun release() {
        Log.d(TAG, "Releasing player manager")
        try {
            stopInternal()
            player.release()
            application.unregisterReceiver(noisyReceiver)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during release", t)
        } finally {
            positionJob?.cancel()
            _error.value = null
            scope.cancel()
        }
    }
}