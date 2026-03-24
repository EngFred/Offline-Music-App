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

/**
 * Represents the observable state of the crossfade engine, exposed to the ViewModel.
 *
 * @param currentTrack The track currently audible (highest volume).
 * @param isPlaying Whether the primary player is actively playing.
 * @param isCrossfading True while a crossfade transition is in progress.
 * @param currentPositionMs Playback position of the primary player in milliseconds.
 * @param currentDurationMs Duration of the primary player's current track in milliseconds.
 * @param crossfadeProgressFraction 0f → 1f progress of the in-flight crossfade, for UI visuals.
 * @param error Non-null when a fatal playback error has occurred.
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
 * (pre-loaded at volume 0 and waiting). After each crossfade they swap roles.
 * - The engine is intentionally decoupled from [PlaybackService]: it owns its own players and
 * does NOT interact with the app's [MediaSession] or notification. The [DjMixViewModel] is
 * responsible for bridging state to the UI.
 * - BPM logic lives entirely in [GetSmartNextTrackUseCase] / [DjMixViewModel]. The engine
 * asks "what's next?" via [nextTrackRequest] and the ViewModel responds with [queueNextTrack].
 *
 * ## DJ Features implemented
 * 1. **Smart Cue** — incoming track is seeked to its first detected beat (no dead air intro).
 * 2. **Bass Kill EQ** — low frequencies of the outgoing track are cut during the fade.
 * 3. **Skip Silence** — ExoPlayer natively removes absolute digital silence at track edges.
 * 4. **Beat-Aligned Transitions** — crossfade trigger snaps to the nearest beat boundary of
 * the outgoing track, so the fade always starts on the "1" of the bar.
 * 5. **Tempo Sync** — incoming track is time-stretched via [PlaybackParameters] to match the
 * outgoing BPM, then gradually eased back to 1.0× post-crossfade. Pitch is locked to
 * 1.0f throughout to prevent unnatural "chipmunk" or "pitch-down" artefacts.
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

        // ── Beat-Aligned Transitions ──────────────────────────────────────────
        /**
         * How close (in ms) the current position must be to a beat boundary to be
         * considered "on the beat" and eligible to trigger a crossfade.
         * Set to half the poll interval so we never miss a beat between two polls.
         */
        private const val BEAT_SNAP_WINDOW_MS = POSITION_POLL_MS / 2

        // ── Tempo Sync ────────────────────────────────────────────────────────
        /**
         * Number of discrete steps to ease playback speed from the sync ratio back to 1.0×.
         * More steps = smoother, less perceptible correction.
         */
        private const val TEMPO_EASE_STEPS = 40

        /**
         * Total wall-clock duration (ms) of the post-crossfade tempo ease.
         * At 4 s with 40 steps each step fires every 100 ms.
         */
        private const val TEMPO_EASE_DURATION_MS = 4_000L

        /**
         * Bounds for the Tempo Sync speed ratio (±15%).
         * Beyond this range the time-stretch artefacts become audible on most devices.
         * The BPM tolerance slider in the UI naturally prevents pairing tracks this far apart,
         * but we clamp here as a safety net.
         */
        private const val MAX_SPEED_RATIO = 1.15f
        private const val MIN_SPEED_RATIO = 0.85f
    }

    // ── Internal coroutine scope ──────────────────────────────────────────────
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Dual players ──────────────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    /** True while Player A is primary (audible). Alternates on each crossfade. */
    private var isPrimaryA = true
    private fun primaryPlayer() = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB else playerA

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
    /** Duration of the volume crossfade in milliseconds. Kept in sync by the ViewModel. */
    var crossfadeDurationMs: Long = 5_000L

    /** If true, triggers the next track at [maxTrackDurationMs] instead of track end. */
    var isRealMixMode: Boolean = false
    var maxTrackDurationMs: Long = 120_000L

    // ── Internal jobs ─────────────────────────────────────────────────────────
    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job? = null

    /**
     * Separate job for the post-crossfade tempo ease so it can be independently
     * cancelled if the user skips the track or a new crossfade starts mid-ease.
     */
    private var tempoEaseJob: Job? = null

    // ── Beat-Aligned Transitions state ────────────────────────────────────────
    /**
     * BPM of the currently playing track.
     * Updated via [updateCurrentBpmInfo] whenever the ViewModel detects a track change.
     * 0f = no BPM data available; engine falls back to time-based triggering.
     */
    @Volatile private var currentTrackBpm: Float = 0f

    /**
     * Timestamp (ms) of the first detected beat of the current track.
     * Acts as the anchor for the beat grid used in [startPositionMonitoring].
     */
    @Volatile private var currentTrackFirstBeatMs: Long = 0L

    /**
     * The normalized base volume calculated via Auto-Gain RMS.
     */
    @Volatile private var currentTrackBaseVolume: Float = 1.0f

    /**
     * Stores the ID of the last track we requested a transition for.
     * Prevents the engine from spamming the ViewModel with requests if the queue is
     * exhausted and the final track is playing out.
     */
    private var lastRequestedTrackId: Long? = null

    /**
     * Container for a track that was queued while a crossfade was already in progress.
     * Stores the full set of pre-computed transition data so nothing is lost.
     *
     * @param audioFile  The track to play next.
     * @param firstBeatMs  Pre-computed first-beat cue point from the BPM cache.
     * @param bpm  Analysed BPM used for Tempo Sync.
     * @param amplitude Analysed RMS Loudness for Auto-Gain.
     */
    private data class PendingTrack(
        val audioFile: AudioFile,
        val firstBeatMs: Long,
        val bpm: Float,
        val amplitude: Float
    )
    private var pendingNextTrack: PendingTrack? = null

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
                    // Natively skip absolute digital silence at start/end of tracks
                    skipSilenceEnabled = true
                }
                playerB = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, false) // secondary: no audio focus
                    setHandleAudioBecomingNoisy(false)
                    // Natively skip absolute digital silence at start/end of tracks
                    skipSilenceEnabled = true
                }
                Log.d(TAG, "initialize: CrossfadeEngine initialised — both players ready with skipSilence.")
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

                // Reset request tracker on manual playback start
                lastRequestedTrackId = null
                Log.d(TAG, "startPlayback: DJ Mix playback started: ${audioFile.title} (ID: ${audioFile.id})")
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
                if (primary.isPlaying) {
                    primary.pause()
                    Log.d(TAG, "playPause: Primary player paused.")
                } else {
                    primary.play()
                    Log.d(TAG, "playPause: Primary player playing.")
                }
                _state.update { it.copy(isPlaying = primary.isPlaying) }
            }
        }
    }

    /**
     * Feeds the current track's BPM data to the engine so [startPositionMonitoring] can
     * align crossfade triggers to beat boundaries.
     *
     * Should be called by [DjMixViewModel] whenever:
     * - The currently playing track changes.
     * - BPM analysis completes for the currently playing track mid-playback.
     *
     * @param bpm          Analysed BPM of the current track (pass 0f if unknown).
     * @param firstBeatMs  Timestamp of the first detected beat; anchors the beat grid.
     * @param amplitude    RMS Loudness of the track used for Auto-Gain normalization.
     */
    fun updateCurrentBpmInfo(bpm: Float, firstBeatMs: Long, amplitude: Float = 0f) {
        currentTrackBpm = bpm
        currentTrackFirstBeatMs = firstBeatMs

        // Auto-Gain Target: Normalize against an RMS target of 0.15f.
        // Clamp between 0.2f and 1.0f so we don't completely mute loud tracks or blow out quiet ones.
        currentTrackBaseVolume = if (amplitude > 0f) {
            (0.15f / amplitude).coerceIn(0.2f, 1.0f)
        } else {
            1.0f
        }

        Log.d(TAG, "updateCurrentBpmInfo: Grid updated [BPM: $bpm, FirstBeat: ${firstBeatMs}ms, VolScale: $currentTrackBaseVolume]")
    }

    /**
     * Called by the ViewModel in response to [nextTrackRequest]. The engine will load
     * [audioFile] into the secondary player and execute the crossfade.
     *
     * @param audioFile   The track to crossfade into.
     * @param firstBeatMs First detected beat (ms) used for Smart Cue — the incoming track
     * is seeked here before playback so it drops in on the beat.
     * @param nextBpm     Analysed BPM of the incoming track used for Tempo Sync.
     * The track is time-stretched to match the outgoing BPM, then eased
     * back to 1.0× over [TEMPO_EASE_DURATION_MS] ms post-crossfade.
     * Pass 0f if unknown; the engine will skip time-stretching gracefully.
     * @param nextAmplitude RMS Loudness of incoming track for Auto-Gain.
     */
    fun queueNextTrack(audioFile: AudioFile, firstBeatMs: Long = 0L, nextBpm: Float = 0f, nextAmplitude: Float = 0f) {
        Log.d(TAG, "queueNextTrack: Queuing '${audioFile.title}' [BPM: $nextBpm, Cue: ${firstBeatMs}ms]")
        if (_state.value.isCrossfading) {
            Log.d(TAG, "queueNextTrack: Crossfade in progress. Storing as pending next track.")
            // Store the full state so no transition data is lost during a back-to-back queue
            pendingNextTrack = PendingTrack(audioFile, firstBeatMs, nextBpm, nextAmplitude)
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile, firstBeatMs, nextBpm, nextAmplitude)
        }
    }

    /**
     * Stops all playback and releases both ExoPlayer instances.
     * Must be called from [DjMixViewModel.onCleared].
     */
    fun release() {
        Log.d(TAG, "release: Releasing CrossfadeEngine resources.")
        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        tempoEaseJob?.cancel()
        engineScope.launch {
            withContext(Dispatchers.Main) {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
                Log.d(TAG, "release: ExoPlayers destroyed.")
            }
        }
        engineScope.cancel()
    }

    // ── Crossfade logic ───────────────────────────────────────────────────────

    /**
     * Core crossfade routine. Loads [nextTrack] into the secondary player, applies
     * Smart Cue, Bass Kill, and Tempo Sync, then fades the primary out and the
     * secondary in simultaneously over [crossfadeDurationMs] ms. Swaps player roles
     * on completion and launches the post-crossfade tempo ease.
     *
     * @param nextTrack   Track to transition into.
     * @param firstBeatMs Beat cue point for the incoming track (Smart Cue).
     * @param nextBpm     BPM of the incoming track (Tempo Sync). 0f = skip sync.
     * @param nextAmplitude RMS Loudness of incoming track for Auto-Gain.
     */
    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        firstBeatMs: Long = 0L,
        nextBpm: Float = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primary = primaryPlayer() ?: return
        val secondary = secondaryPlayer() ?: return

        Log.d(TAG, "executeCrossfade: STARTING crossfade into '${nextTrack.title}'")
        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        // Cancel any in-flight tempo ease from a previous crossfade so we don't have
        // two easing coroutines fighting over setPlaybackParameters simultaneously.
        tempoEaseJob?.cancel()

        // We declare the Equalizer outside the try block so we can guarantee its release
        // in the finally block, preventing severe Android AudioFX memory leaks.
        var bassKillEq: android.media.audiofx.Equalizer? = null

        try {
            // ── TEMPO SYNC — compute speed ratio before touching the secondary player ──
            // ExoPlayer's PlaybackParameters(speed, pitch=1.0f) uses internal time-stretching
            // to change tempo without affecting pitch — no chipmunk / pitch-shift artefacts.
            // We clamp to ±15% because beyond that the stretching becomes audible on most devices.
            val outgoingBpm = currentTrackBpm
            val speedFactor = if (outgoingBpm > 0f && nextBpm > 0f) {
                (outgoingBpm / nextBpm).coerceIn(MIN_SPEED_RATIO, MAX_SPEED_RATIO)
            } else {
                1.0f
            }

            // ── AUTO-GAIN BASE VOLUMES ──
            val secondaryBaseVolume = if (nextAmplitude > 0f) {
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f)
            } else {
                1.0f
            }
            val primaryBaseVolume = currentTrackBaseVolume

            // ── Prepare secondary player ──────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                secondary.stop()
                secondary.clearMediaItems()
                secondary.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                secondary.volume = 0f

                // Apply tempo sync BEFORE prepare so ExoPlayer initialises the audio
                // pipeline with the correct playback rate from the very first buffer.
                if (speedFactor != 1.0f) {
                    secondary.setPlaybackParameters(PlaybackParameters(speedFactor, 1.0f))
                    Log.d(TAG, "executeCrossfade: Tempo Sync applied: %.3f×".format(speedFactor))
                }

                secondary.prepare()

                // SMART CUE — seek to the first detected beat so the track drops in
                // with a punch rather than fading in on dead air or a slow intro.
                if (firstBeatMs > 0L) {
                    Log.d(TAG, "executeCrossfade: Smart Cue: Seeking incoming track to ${firstBeatMs}ms")
                    secondary.seekTo(firstBeatMs)
                }

                secondary.play()
            }

            // Wait briefly for secondary to buffer before starting the fade.
            // 2 s timeout prevents the engine from hanging on a slow/corrupt file.
            var waitMs = 0L
            while (waitMs < 2_000L) {
                val ready = withContext(Dispatchers.Main) {
                    secondary.playbackState == Player.STATE_READY || secondary.isPlaying
                }
                if (ready) {
                    Log.d(TAG, "executeCrossfade: Incoming track ready after ${waitMs}ms.")
                    break
                }
                delay(100L)
                waitMs += 100L
            }

            // ── BASS KILL EQ ──────────────────────────────────────────────────────────
            // Drop the low frequencies on the outgoing track so kick drums don't clash
            // with those of the incoming track during the overlap window.
            withContext(Dispatchers.Main) {
                try {
                    val sessionId = primary.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        bassKillEq = android.media.audiofx.Equalizer(0, sessionId).apply {
                            enabled = true
                            if (numberOfBands > 0) {
                                // Band 0 is usually ~60 Hz. Cut it to the absolute minimum.
                                val minBassLevel = bandLevelRange[0]
                                setBandLevel(0, minBassLevel)
                                Log.d(TAG, "executeCrossfade: Bass Kill EQ active on session $sessionId.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Wrap in try-catch because some OEM Android skins crash when
                    // instantiating Equalizer — we treat it as a non-fatal degradation.
                    Log.w(TAG, "executeCrossfade: Bass Kill EQ failed: ${e.message}")
                }
            }

            // ── Volume ramp ───────────────────────────────────────────────────────────
            val stepDelayMs = (crossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            Log.d(TAG, "executeCrossfade: Starting volume ramp (Steps: $FADE_STEPS, Auto-Gain Target: $secondaryBaseVolume)")
            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive) break
                val toVolumeFraction   = step.toFloat() / FADE_STEPS
                val fromVolumeFraction = 1f - toVolumeFraction
                withContext(Dispatchers.Main) {
                    primary.volume   = fromVolumeFraction * primaryBaseVolume
                    secondary.volume = toVolumeFraction * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = toVolumeFraction) }
                delay(stepDelayMs)
            }

            // ── Finalise swap ─────────────────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                primary.pause()
                primary.volume = 1f
                // Reset the outgoing player's playback parameters so it's in a clean
                // state for its next role as the secondary in a future crossfade.
                primary.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
                secondary.volume = secondaryBaseVolume // Leave at Auto-Gain volume
            }

            // Swap primary role — the former secondary is now the audible player.
            isPrimaryA = !isPrimaryA

            // Allow the engine to request a track for this new primary player when ready
            lastRequestedTrackId = null

            Log.d(TAG, "executeCrossfade: COMPLETE. Swapped roles. PrimaryA: $isPrimaryA")
            _state.update {
                it.copy(
                    currentTrack = nextTrack,
                    isPlaying = true,
                    isCrossfading = false,
                    crossfadeProgressFraction = 0f
                )
            }

            // ── Post-crossfade Tempo Ease ─────────────────────────────────────────────
            // If we applied time-stretching, gradually ease the new primary back to 1.0×
            // so the listener doesn't notice the tempo correction being removed.
            // Runs on a separate job so it can be cancelled cleanly on the next crossfade.
            if (speedFactor != 1.0f) {
                tempoEaseJob = engineScope.launch {
                    easeTempoBackToNormal(speedFactor)
                }
            }

            // If a next track was queued while this crossfade ran, start it now.
            val pending = pendingNextTrack
            if (pending != null) {
                Log.d(TAG, "executeCrossfade: Handling pending track '${pending.audioFile.title}'")
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            // PRO-DEV GUARD: Ensure Equalizer is ALWAYS released, even if the coroutine
            // is cancelled mid-fade (e.g. user manually skips track mid-crossfade).
            try {
                bassKillEq?.release()
                bassKillEq = null
                Log.d(TAG, "executeCrossfade: Bass Kill EQ released safely.")
            } catch (e: Exception) {
                Log.e(TAG, "executeCrossfade: Failed to release Bass Kill EQ", e)
            }
        }
    }

    // ── Tempo Sync: ease-back ─────────────────────────────────────────────────

    /**
     * Gradually eases the new primary player's playback speed from [fromSpeed] back to 1.0×
     * over [TEMPO_EASE_DURATION_MS] ms so the tempo correction is imperceptible.
     *
     * Uses linear interpolation across [TEMPO_EASE_STEPS] discrete steps.
     * A floating-point guard at the end ensures we land exactly at 1.0× regardless of
     * rounding accumulated across steps.
     *
     * Runs on [engineScope] via [tempoEaseJob]; cancelled automatically if a new crossfade
     * begins before the ease completes.
     */
    private suspend fun easeTempoBackToNormal(fromSpeed: Float) {
        Log.d(TAG, "easeTempoBackToNormal: Starting ease from %.3f× back to 1.0×".format(fromSpeed))
        val stepDelayMs = TEMPO_EASE_DURATION_MS / TEMPO_EASE_STEPS
        for (step in 1..TEMPO_EASE_STEPS) {
            if (!engineScope.isActive) break
            val easedSpeed = fromSpeed + (1.0f - fromSpeed) * step.toFloat() / TEMPO_EASE_STEPS
            withContext(Dispatchers.Main) {
                primaryPlayer()?.setPlaybackParameters(PlaybackParameters(easedSpeed, 1.0f))
            }
            delay(stepDelayMs)
        }
        // Floating-point guard: ensure we land precisely at 1.0×
        withContext(Dispatchers.Main) {
            primaryPlayer()?.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
        }
        Log.d(TAG, "easeTempoBackToNormal: Normalization finished.")
    }

    // ── Position monitoring ───────────────────────────────────────────────────

    /**
     * Polls the primary player every [POSITION_POLL_MS] ms to:
     * 1. Update [CrossfadeEngineState.currentPositionMs] / [CrossfadeEngineState.currentDurationMs].
     * 2. Trigger a [nextTrackRequest] when the remaining time falls within the crossfade window
     * AND the current position is aligned to a beat boundary (Beat-Aligned Transitions).
     *
     * ## Beat-Aligned Transition logic
     * When BPM data is available via [updateCurrentBpmInfo], the crossfade window is extended
     * by one [beatLengthMs] to give the beat-snap logic room to find a valid beat. The trigger
     * fires only when [beatPhase] is within [BEAT_SNAP_WINDOW_MS] of a beat boundary — i.e.,
     * within half a poll interval of a grid line anchored at [currentTrackFirstBeatMs].
     *
     * Falls back to the original time-only trigger when no BPM data is available.
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

                val remaining    = duration - position
                val bpm          = currentTrackBpm
                val firstBeat    = currentTrackFirstBeatMs
                val beatLengthMs = if (bpm > 0f) (60_000f / bpm).toLong() else 0L

                // ── Beat-Aligned Transition check ─────────────────────────────────
                // "On the beat" means the current playback position falls within
                // BEAT_SNAP_WINDOW_MS of a beat boundary on the grid anchored at firstBeatMs.
                val isOnBeatBoundary = if (beatLengthMs > 0L && duration > 0L) {
                    val posRelativeToGrid = (position - firstBeat).coerceAtLeast(0L)
                    val beatPhase = posRelativeToGrid % beatLengthMs
                    // Success if we are near the start or the end of the current beat interval
                    beatPhase <= BEAT_SNAP_WINDOW_MS || beatPhase >= (beatLengthMs - BEAT_SNAP_WINDOW_MS)
                } else {
                    // No BPM data — any position inside the window is valid (original behaviour).
                    true
                }

                // Extend the check window by one beatLengthMs so we never miss a beat that
                // falls right at the boundary of the nominal crossfade window.
                // When beatLengthMs == 0 (no BPM) this collapses back to crossfadeDurationMs.
                val triggerWindowMs = crossfadeDurationMs + beatLengthMs

                val inTriggerZone = remaining in CROSSFADE_GUARD_MS..triggerWindowMs

                // NEW: Real Mix trigger: reach the user-defined max duration
                val isMaxTimeReached = isRealMixMode && position >= maxTrackDurationMs && remaining > crossfadeDurationMs

                val shouldTrigger = playing
                        && !_state.value.isCrossfading
                        && duration > 0L
                        && (inTriggerZone || isMaxTimeReached)
                        && isOnBeatBoundary

                if (shouldTrigger) {
                    val currentId = _state.value.currentTrack?.id ?: continue

                    // Prevent spamming the ViewModel if looping is off and the last track is ending
                    if (currentId != lastRequestedTrackId) {
                        lastRequestedTrackId = currentId
                        val triggerReason = if (isMaxTimeReached) "Real Mix Limit" else "Track End"
                        Log.d(
                            TAG,
                            "startPositionMonitoring: TRIGGERED ($triggerReason). [Remaining: ${remaining}ms, BeatGrid: $isOnBeatBoundary]"
                        )
                        _nextTrackRequest.tryEmit(currentId)
                    }
                }
            }
        }
    }
}