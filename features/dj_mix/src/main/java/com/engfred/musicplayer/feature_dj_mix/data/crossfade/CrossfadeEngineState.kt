package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.engfred.musicplayer.core.domain.model.AudioFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Represents the observable state of the crossfade engine, exposed to the ViewModel.
 *
 * @param currentTrack          The track currently audible (highest volume).
 * @param isPlaying             Whether the primary player is actively playing.
 * @param isCrossfading         True while a crossfade transition is in progress.
 * @param currentPositionMs     Playback position of the primary player in milliseconds.
 * @param currentDurationMs     Duration of the primary player's current track in milliseconds.
 * @param crossfadeProgressFraction  0f → 1f progress of the in-flight crossfade, for UI visuals.
 * @param error                 Non-null when a fatal playback error has occurred.
 */
data class CrossfadeEngineState(
    val currentTrack: AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val error: String? = null
)

/**
 * Manages two [ExoPlayer] instances to produce seamless, BPM-aware DJ-style crossfades.
 *
 * ## Architecture
 * - **Player A / Player B**: One is always the *primary* (audible), the other the *secondary*
 *   (pre-loaded at volume 0 and waiting). After each crossfade they swap roles.
 * - The engine is intentionally decoupled from [PlaybackService]: it owns its own players and
 *   does NOT interact with the app's [MediaSession] or notification. The [DjMixViewModel] is
 *   responsible for bridging state to the UI.
 * - BPM logic lives entirely in [GetSmartNextTrackUseCase] / [DjMixViewModel]. The engine
 *   asks "what's next?" via [nextTrackRequest] and the ViewModel responds with [queueNextTrack].
 *
 * ## Lifecycle
 * Call [initialize] before any playback. Call [release] in ViewModel.onCleared() to free
 * ExoPlayer resources. The engine is *not* a Singleton — each [DjMixViewModel] owns one instance.
 *
 * ## Thread safety
 * All ExoPlayer mutations are dispatched to [Dispatchers.Main]. Internal coroutines run on
 * [engineScope] which is bound to the engine's lifetime.
 */
@UnstableApi
class CrossfadeEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        /** Polling interval for position monitoring. */
        private const val POSITION_POLL_MS = 300L

        /** Number of volume steps per crossfade. More steps = smoother fade. */
        private const val FADE_STEPS = 60

        /**
         * Start the crossfade this many ms before the natural end of the current track.
         * The actual trigger window is [crossfadeDurationMs] — this constant is a guard
         * that prevents triggering twice on the same track-end.
         */
        private const val CROSSFADE_GUARD_MS = 200L
    }

    // ── Internal coroutine scope ──────────────────────────────────────────────

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Dual players ──────────────────────────────────────────────────────────

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    /** True while Player A is primary (audible). Alternates on each crossfade. */
    private var isPrimaryA = true

    private fun primaryPlayer()   = if (isPrimaryA) playerA   else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB   else playerA

    // ── State ─────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    /**
     * The engine emits the current track's [AudioFile.id] here when it needs the next
     * track to pre-load. The [DjMixViewModel] should collect this and respond by calling
     * [queueNextTrack] before the crossfade window expires.
     */
    private val _nextTrackRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    // ── Settings ──────────────────────────────────────────────────────────────

    /** Duration of the volume crossfade in milliseconds. */
    var crossfadeDurationMs: Long = 5_000L

    // ── Internal jobs ─────────────────────────────────────────────────────────

    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job? = null
    private var pendingNextTrack: AudioFile? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates both ExoPlayer instances. Must be called on the Main thread (or the
     * ViewModel's init block which runs on Main).
     */
    fun initialize() {
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()

                playerA = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, true)
                    setHandleAudioBecomingNoisy(true)
                }
                playerB = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, false)   // secondary: no audio focus
                    setHandleAudioBecomingNoisy(false)
                }
                Log.d(TAG, "CrossfadeEngine initialised — both players ready.")
            }
        }
    }

    /**
     * Starts DJ Mix playback with the supplied [audioFile] as the first track.
     * The [DjMixViewModel] is expected to respond to subsequent [nextTrackRequest] emissions
     * to keep the engine fed.
     */
    fun startPlayback(audioFile: AudioFile) {
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                primary.stop()
                primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.prepare()
                primary.play()
                Log.d(TAG, "Started DJ Mix playback: ${audioFile.title}")
            }
            _state.update { it.copy(currentTrack = audioFile, isPlaying = true, error = null) }
            startPositionMonitoring()
        }
    }

    /** Toggles play/pause on the primary player. */
    fun playPause() {
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                if (primary.isPlaying) primary.pause() else primary.play()
                _state.update { it.copy(isPlaying = primary.isPlaying) }
            }
        }
    }

    /**
     * Called by the ViewModel in response to [nextTrackRequest]. The engine will load
     * [audioFile] into the secondary player and execute the crossfade.
     */
    fun queueNextTrack(audioFile: AudioFile) {
        if (_state.value.isCrossfading) {
            Log.d(TAG, "Crossfade already in progress — storing as pending next track.")
            pendingNextTrack = audioFile
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile)
        }
    }

    /**
     * Stops all playback and releases both ExoPlayer instances.
     * Must be called from [DjMixViewModel.onCleared].
     */
    fun release() {
        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        engineScope.launch {
            withContext(Dispatchers.Main) {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
                Log.d(TAG, "CrossfadeEngine released.")
            }
        }
        engineScope.cancel()
    }

    // ── Crossfade logic ───────────────────────────────────────────────────────

    /**
     * Loads [nextTrack] into the secondary player, fades the primary player out and
     * the secondary in simultaneously over [crossfadeDurationMs] ms, then swaps roles.
     */
    private suspend fun executeCrossfade(nextTrack: AudioFile) {
        val primary   = primaryPlayer()   ?: return
        val secondary = secondaryPlayer() ?: return

        Log.d(TAG, "Beginning crossfade → ${nextTrack.title}")
        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        // Prepare secondary player with next track at volume 0
        withContext(Dispatchers.Main) {
            secondary.stop()
            secondary.clearMediaItems()
            secondary.setMediaItem(MediaItem.fromUri(nextTrack.uri))
            secondary.volume = 0f
            secondary.prepare()
            secondary.play()
        }

        // Wait briefly for secondary to buffer before starting the fade
        var waitMs = 0L
        while (waitMs < 2_000L) {
            val ready = withContext(Dispatchers.Main) {
                secondary.playbackState == Player.STATE_READY || secondary.isPlaying
            }
            if (ready) break
            delay(100L)
            waitMs += 100L
        }

        // Execute volume ramp over FADE_STEPS
        val stepDelayMs = (crossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)

        for (step in 1..FADE_STEPS) {
            if (!engineScope.isActive) break
            val toVolume   = step.toFloat() / FADE_STEPS
            val fromVolume = 1f - toVolume
            withContext(Dispatchers.Main) {
                primary.volume   = fromVolume
                secondary.volume = toVolume
            }
            _state.update { it.copy(crossfadeProgressFraction = toVolume) }
            delay(stepDelayMs)
        }

        // Finish: primary fades out completely, secondary takes over
        withContext(Dispatchers.Main) {
            primary.pause()
            primary.volume = 1f   // reset for future use as secondary
            secondary.volume = 1f
        }

        // Swap primary role
        isPrimaryA = !isPrimaryA

        Log.d(TAG, "Crossfade complete. Now playing: ${nextTrack.title}")
        _state.update {
            it.copy(
                currentTrack = nextTrack,
                isPlaying = true,
                isCrossfading = false,
                crossfadeProgressFraction = 0f
            )
        }

        // If a next track was queued while this crossfade ran, start it now
        val pending = pendingNextTrack
        if (pending != null) {
            pendingNextTrack = null
            executeCrossfade(pending)
        }
    }

    // ── Position monitoring ───────────────────────────────────────────────────

    /**
     * Polls the primary player every [POSITION_POLL_MS] ms to:
     * 1. Update [CrossfadeEngineState.currentPositionMs] / [CrossfadeEngineState.currentDurationMs].
     * 2. Trigger a [nextTrackRequest] when the remaining time falls within [crossfadeDurationMs].
     */
    private fun startPositionMonitoring() {
        positionMonitorJob?.cancel()
        positionMonitorJob = engineScope.launch {
            while (isActive) {
                delay(POSITION_POLL_MS)

                val (position, duration, playing) = withContext(Dispatchers.Main) {
                    val p = primaryPlayer()
                    val dur = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                    Triple(p?.currentPosition ?: 0L, dur, p?.isPlaying ?: false)
                }

                _state.update { it.copy(currentPositionMs = position, currentDurationMs = duration) }

                val remaining = duration - position
                val shouldTrigger = playing
                        && !_state.value.isCrossfading
                        && duration > 0L
                        && remaining in CROSSFADE_GUARD_MS..crossfadeDurationMs

                if (shouldTrigger) {
                    val currentId = _state.value.currentTrack?.id ?: continue
                    Log.d(TAG, "Approaching track end (remaining=${remaining}ms) — requesting next track.")
                    _nextTrackRequest.tryEmit(currentId)
                }
            }
        }
    }
}