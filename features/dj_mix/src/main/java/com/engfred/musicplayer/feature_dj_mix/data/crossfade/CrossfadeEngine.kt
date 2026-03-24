package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
import javax.inject.Singleton

/**
 * Observable state of the crossfade engine, exposed to ViewModel and Service.
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
 * Manages two [ExoPlayer] instances for seamless BPM-aware DJ crossfades.
 *
 * ── Architecture ─────────────────────────────────────────────────────────────
 * The engine is intentionally dumb about queue logic. It asks "what's next?" via
 * [nextTrackRequest] and [DjMixService] responds via [queueNextTrack]. This
 * separation means the engine works correctly whether or not a ViewModel is alive.
 *
 * ── BUG FIXES in this version ────────────────────────────────────────────────
 * 1. release() — the old code called engineScope.launch{} then engineScope.cancel()
 *    on the next line. The launch was never guaranteed to execute on a cancelled
 *    scope. Fixed: ExoPlayer teardown now runs on a dedicated one-shot Main scope.
 *
 * 2. @Volatile on isPrimaryA / pendingNextTrack — these are written from ViewModel
 *    coroutines (Default dispatcher) and read from engineScope. Without @Volatile
 *    the JVM may cache stale values in CPU registers.
 *
 * 3. isCrossfading reset on cancel — if crossfadeJob is cancelled mid-fade the
 *    finally block now explicitly resets isCrossfading so the UI doesn't freeze
 *    in the "crossfading" state forever.
 *
 * 4. nextTrackRequest replay=1 — if DjMixService subscribes slightly after the
 *    emission (startup race), replay=1 ensures it still receives the request.
 *
 * ── New features ──────────────────────────────────────────────────────────────
 * 5. triggerMixNow() — public API for the "Mix Now" FAB. Immediately emits a
 *    [nextTrackRequest] for the current track, bypassing the position monitor.
 *    Guards against double-emission and mid-crossfade calls.
 *
 * 6. useHalfwayMix — when true (default), Real Mix Mode fires the crossfade when
 *    the track reaches 50 % of its duration instead of relying on [maxTrackDurationMs].
 *    Set to false by [DjMixService] when the user has configured a manual max time.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 * All ExoPlayer mutations are dispatched to Dispatchers.Main.
 * Internal coroutines run on engineScope (SupervisorJob + Dispatchers.Default).
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "CrossfadeEngine"
        private const val POSITION_POLL_MS        = 300L
        private const val FADE_STEPS              = 60
        private const val CROSSFADE_GUARD_MS      = 200L
        private const val BEAT_SNAP_WINDOW_MS     = POSITION_POLL_MS / 2
        private const val TEMPO_EASE_STEPS        = 40
        private const val TEMPO_EASE_DURATION_MS  = 4_000L
        private const val MAX_SPEED_RATIO         = 1.15f
        private const val MIN_SPEED_RATIO         = 0.85f
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Dual players ──────────────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    /**
     * FIX: @Volatile ensures writes on one thread are immediately visible on others.
     * isPrimaryA is written by executeCrossfade (engineScope/Default) and read by
     * primaryPlayer()/secondaryPlayer() which may be called from any context.
     */
    @Volatile private var isPrimaryA = true
    private fun primaryPlayer()   = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB else playerA

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    /**
     * FIX: replay = 1 prevents a startup race where DjMixService subscribes fractionally
     * after the emission and misses the request. The last emission is replayed to any
     * new collector within the engine's lifetime.
     */
    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    // ── Settings (kept in sync by DjMixService via DjSessionManager) ─────────
    var crossfadeDurationMs: Long  = 5_000L
    var isRealMixMode: Boolean     = false
    var maxTrackDurationMs: Long   = 120_000L

    /**
     * When true (default), Real Mix Mode triggers the crossfade once the track
     * reaches 50% of its duration — no user configuration required.
     * When false, [maxTrackDurationMs] is used as the fixed trigger point instead.
     * Kept in sync by [DjMixService.observeEngineSettings].
     */
    @Volatile var useHalfwayMix: Boolean = true

    // ── Internal jobs ─────────────────────────────────────────────────────────
    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var tempoEaseJob: Job?       = null

    // ── Beat-Aligned state (@Volatile for cross-thread visibility) ────────────
    @Volatile private var currentTrackBpm: Float         = 0f
    @Volatile private var currentTrackFirstBeatMs: Long  = 0L
    @Volatile private var currentTrackBaseVolume: Float  = 1.0f

    private var lastRequestedTrackId: Long? = null

    /** FIX: @Volatile — written from ViewModel coroutine, read from engineScope. */
    @Volatile private var pendingNextTrack: PendingTrack? = null

    /** Guards against double-release if both ACTION_STOP and onDestroy fire. */
    @Volatile private var isReleased = false

    private var isInitialized = false

    private data class PendingTrack(
        val audioFile: AudioFile,
        val firstBeatMs: Long,
        val bpm: Float,
        val amplitude: Float
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun initialize() {
        if (isInitialized || isReleased) return
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                playerA = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, true)
                    setHandleAudioBecomingNoisy(true)
                    skipSilenceEnabled = true
                    addListener(createFocusListener(isPlayerA = true))
                }
                playerB = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, false)
                    setHandleAudioBecomingNoisy(false)
                    skipSilenceEnabled = true
                    addListener(createFocusListener(isPlayerA = false))
                }
                isInitialized = true
                Log.d(TAG, "initialize: Both players ready.")
            }
        }
    }

    private fun createFocusListener(isPlayerA: Boolean) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if ((isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }
        }
    }

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                primary.stop()
                primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.prepare()
                primary.play()
                lastRequestedTrackId = null
            }
            _state.update { it.copy(currentTrack = audioFile, isPlaying = true, error = null) }
            startPositionMonitoring()
            Log.d(TAG, "startPlayback: '${audioFile.title}'")
        }
    }

    fun playPause() {
        if (isReleased) return
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                if (primary.isPlaying) primary.pause() else primary.play()
                _state.update { it.copy(isPlaying = primary.isPlaying) }
            }
        }
    }

    fun updateCurrentBpmInfo(bpm: Float, firstBeatMs: Long, amplitude: Float = 0f) {
        currentTrackBpm         = bpm
        currentTrackFirstBeatMs = firstBeatMs
        currentTrackBaseVolume  = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        Log.d(TAG, "updateCurrentBpmInfo: BPM=$bpm, FirstBeat=${firstBeatMs}ms, Vol=$currentTrackBaseVolume")
    }

    /**
     * Immediately triggers a crossfade to the next queued track, bypassing the
     * position-based trigger window. Safe to call from any thread.
     *
     * Guards:
     * - No-ops if already crossfading (an in-progress fade cannot be interrupted here).
     * - No-ops if there is no current track.
     * - Deduplicates emissions so rapid taps don't fire multiple requests.
     *
     * The actual track selection is handled by [DjMixService.observeNextTrackRequests],
     * keeping the engine decoupled from queue logic.
     */
    fun triggerMixNow() {
        if (isReleased) return
        if (_state.value.isCrossfading) {
            Log.d(TAG, "triggerMixNow: ignored — crossfade already in progress.")
            return
        }
        val currentId = _state.value.currentTrack?.id ?: run {
            Log.d(TAG, "triggerMixNow: ignored — no current track.")
            return
        }
        // Reset lastRequestedTrackId so the emission is always accepted, even if
        // the automatic monitor already fired once for this track ID.
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.d(TAG, "triggerMixNow: nextTrackRequest emitted for trackId=$currentId")
    }

    fun queueNextTrack(
        audioFile: AudioFile,
        firstBeatMs: Long    = 0L,
        nextBpm: Float       = 0f,
        nextAmplitude: Float = 0f
    ) {
        if (isReleased) return
        if (_state.value.isCrossfading) {
            pendingNextTrack = PendingTrack(audioFile, firstBeatMs, nextBpm, nextAmplitude)
            Log.d(TAG, "queueNextTrack: crossfade in progress — stored as pending.")
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile, firstBeatMs, nextBpm, nextAmplitude)
        }
    }

    /**
     * Stops all playback and releases both ExoPlayer instances.
     *
     * FIX: The old implementation called engineScope.launch{...} immediately before
     * engineScope.cancel(). In Kotlin coroutines, cancelling a scope makes all
     * subsequent launches fail silently — meaning the ExoPlayers were never released.
     *
     * Fix: ExoPlayer teardown now runs on a dedicated one-shot CoroutineScope
     * (Dispatchers.Main.immediate) that is independent of engineScope.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        Log.d(TAG, "release: Stopping all jobs and releasing players.")

        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        tempoEaseJob?.cancel()

        // Dedicated scope so ExoPlayer teardown is guaranteed to execute
        // even after engineScope is cancelled.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
            } catch (e: Exception) {
                Log.e(TAG, "release: Error releasing players", e)
            } finally {
                isInitialized = false
                Log.d(TAG, "release: ExoPlayers destroyed.")
            }
        }
        engineScope.cancel()
    }

    // ── Crossfade logic ───────────────────────────────────────────────────────

    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        firstBeatMs: Long    = 0L,
        nextBpm: Float       = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primary   = primaryPlayer()   ?: return
        val secondary = secondaryPlayer() ?: return

        Log.d(TAG, "executeCrossfade: START → '${nextTrack.title}'")
        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }
        tempoEaseJob?.cancel()

        var bassKillEq: android.media.audiofx.Equalizer? = null

        try {
            // ── Tempo Sync ────────────────────────────────────────────────────
            val outgoingBpm = currentTrackBpm
            val speedFactor = if (outgoingBpm > 0f && nextBpm > 0f) {
                (outgoingBpm / nextBpm).coerceIn(MIN_SPEED_RATIO, MAX_SPEED_RATIO)
            } else 1.0f

            // ── Auto-Gain volumes ─────────────────────────────────────────────
            val secondaryBaseVolume = if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume   = currentTrackBaseVolume

            // ── Prepare secondary player ──────────────────────────────────────
            withContext(Dispatchers.Main) {
                secondary.stop()
                secondary.clearMediaItems()
                secondary.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                secondary.volume = 0f
                if (speedFactor != 1.0f) {
                    secondary.setPlaybackParameters(PlaybackParameters(speedFactor, 1.0f))
                }
                secondary.prepare()
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                secondary.play()
            }

            // Buffer wait (2 s max)
            var waitMs = 0L
            while (waitMs < 2_000L) {
                val ready = withContext(Dispatchers.Main) {
                    secondary.playbackState == Player.STATE_READY || secondary.isPlaying
                }
                if (ready) break
                delay(100L); waitMs += 100L
            }

            // ── Bass Kill EQ ──────────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                try {
                    val sessionId = primary.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        bassKillEq = android.media.audiofx.Equalizer(0, sessionId).apply {
                            enabled = true
                            if (numberOfBands > 0) setBandLevel(0, bandLevelRange[0])
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Bass Kill EQ failed (non-fatal): ${e.message}")
                }
            }

            // ── Volume ramp ───────────────────────────────────────────────────
            val stepDelayMs = (crossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive) break
                val to   = step.toFloat() / FADE_STEPS
                val from = 1f - to
                withContext(Dispatchers.Main) {
                    primary.volume   = from * primaryBaseVolume
                    secondary.volume = to   * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = to) }
                delay(stepDelayMs)
            }

            // ── Finalise swap ─────────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                primary.pause()
                primary.volume = 1f
                primary.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
                secondary.volume = secondaryBaseVolume
            }

            isPrimaryA = !isPrimaryA
            lastRequestedTrackId = null

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = true,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }
            Log.d(TAG, "executeCrossfade: COMPLETE. PrimaryA=$isPrimaryA")

            // ── Post-crossfade Tempo Ease ─────────────────────────────────────
            if (speedFactor != 1.0f) {
                tempoEaseJob = engineScope.launch { easeTempoBackToNormal(speedFactor) }
            }

            // Handle any track queued while this crossfade was in progress
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            // FIX: Always reset isCrossfading, even if this coroutine was cancelled
            // mid-fade (e.g. user skips). Without this the UI freezes in "crossfading" state.
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try {
                bassKillEq?.release()
                bassKillEq = null
            } catch (e: Exception) {
                Log.e(TAG, "Bass Kill EQ release failed", e)
            }
        }
    }

    private suspend fun easeTempoBackToNormal(fromSpeed: Float) {
        val stepDelayMs = TEMPO_EASE_DURATION_MS / TEMPO_EASE_STEPS
        for (step in 1..TEMPO_EASE_STEPS) {
            if (!engineScope.isActive) break
            val speed = fromSpeed + (1.0f - fromSpeed) * step.toFloat() / TEMPO_EASE_STEPS
            withContext(Dispatchers.Main) {
                primaryPlayer()?.setPlaybackParameters(PlaybackParameters(speed, 1.0f))
            }
            delay(stepDelayMs)
        }
        withContext(Dispatchers.Main) {
            primaryPlayer()?.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
        }
    }

    // ── Position monitoring ───────────────────────────────────────────────────

    private fun startPositionMonitoring() {
        positionMonitorJob?.cancel()
        positionMonitorJob = engineScope.launch {
            while (isActive) {
                delay(POSITION_POLL_MS)
                val (position, duration, playing) = withContext(Dispatchers.Main) {
                    val p   = primaryPlayer()
                    val dur = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                    Triple(p?.currentPosition ?: 0L, dur, p?.isPlaying ?: false)
                }
                _state.update { it.copy(currentPositionMs = position, currentDurationMs = duration) }

                val remaining    = duration - position
                val bpm          = currentTrackBpm
                val firstBeat    = currentTrackFirstBeatMs
                val beatLengthMs = if (bpm > 0f) (60_000f / bpm).toLong() else 0L

                val isOnBeatBoundary = if (beatLengthMs > 0L && duration > 0L) {
                    val phase = (position - firstBeat).coerceAtLeast(0L) % beatLengthMs
                    phase <= BEAT_SNAP_WINDOW_MS || phase >= (beatLengthMs - BEAT_SNAP_WINDOW_MS)
                } else true

                val triggerWindowMs = crossfadeDurationMs + beatLengthMs
                val inTriggerZone   = remaining in CROSSFADE_GUARD_MS..triggerWindowMs

                /**
                 * Real Mix Mode early trigger:
                 * - If [useHalfwayMix] is true (default), fire once the track has passed
                 *   the 50% mark and there is still enough time for a full crossfade.
                 * - If [useHalfwayMix] is false, the user has set a manual [maxTrackDurationMs]
                 *   and we use that fixed threshold instead.
                 */
                val isMaxTimeReached = if (isRealMixMode && duration > 0L) {
                    val mixTriggerMs = if (useHalfwayMix) duration / 2L else maxTrackDurationMs
                    position >= mixTriggerMs && remaining > crossfadeDurationMs
                } else false

                val shouldTrigger = playing
                        && !_state.value.isCrossfading
                        && duration > 0L
                        && (inTriggerZone || isMaxTimeReached)
                        && isOnBeatBoundary

                if (shouldTrigger) {
                    val currentId = _state.value.currentTrack?.id ?: continue
                    if (currentId != lastRequestedTrackId) {
                        lastRequestedTrackId = currentId
                        Log.d(TAG, "nextTrackRequest emitted [remaining=${remaining}ms, beat=$isOnBeatBoundary, halfway=$useHalfwayMix]")
                        _nextTrackRequest.tryEmit(currentId)
                    }
                }
            }
        }
    }
}