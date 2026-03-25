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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Observable state of the crossfade engine.
 *
 * [waveform] is now generated purely from the beat grid (BPM + firstBeatMs + amplitude)
 * — no android.media.audiofx.Visualizer, no RECORD_AUDIO permission required.
 * The bars pulse on kick/snare boundaries and scale with the track's perceptual amplitude.
 */
data class CrossfadeEngineState(
    val currentTrack: AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val error: String? = null
)

/**
 * Manages two [ExoPlayer] instances for seamless BPM-aware DJ crossfades.
 *
 * ── What changed in this version ─────────────────────────────────────────────
 *
 * 1. EQUAL-POWER CROSSFADE (critical audio fix)
 *    Previous: linear ramp → volume dip at midpoint (both tracks at 50% = ~70% perceived).
 *    Fixed: sin/cos equal-power curve keeps total perceived loudness constant throughout
 *    the crossfade. This is how every professional DJ application works.
 *
 * 2. VISUALIZER REMOVED — NO RECORD_AUDIO PERMISSION
 *    android.media.audiofx.Visualizer requires the RECORD_AUDIO runtime permission.
 *    Users see a "microphone access" dialog inside a music app — confusing and off-putting.
 *    The waveform is now synthesised from the beat grid (BPM + firstBeatMs + amplitude)
 *    with per-bar exponential smoothing. Visually indistinguishable from a real capture,
 *    zero permissions, zero risk of SecurityException.
 *
 * 3. PRE-BUFFER SECONDARY PLAYER
 *    When remaining < crossfadeDurationMs × 3, [prebufferTrack] prepares the secondary
 *    ExoPlayer silently (volume=0, paused, seeked to firstBeatMs). When the actual
 *    crossfade fires, the track is already in STATE_READY — the 2-second buffer wait is
 *    skipped entirely, eliminating the potential silence gap at crossfade start.
 *
 * 4. FAST POLL IN TRIGGER WINDOW
 *    Position monitor normally polls at 300ms. When remaining < crossfadeDurationMs × 3,
 *    it switches to 50ms. At 180 BPM one beat = 333ms; a 300ms poll can miss a beat
 *    boundary entirely. 50ms guarantees sub-beat precision.
 *
 * 5. ±25% TEMPO CLAMP (was ±15%)
 *    Common DJ scenario: 120→140 BPM requires 16.7% speedup — beyond the old limit.
 *    Extended to ±25% (0.75..1.33) while remaining within ExoPlayer's pitch-shift
 *    artefact threshold for most genres.
 *
 * 6. 8-BAR PHRASE DETECTION
 *    Crossfade triggers are gated to the last bar of an 8-bar musical phrase (the
 *    dominant structure in electronic, hip-hop, and pop). Falls back to any beat boundary
 *    when remaining < crossfadeDurationMs + beatLengthMs so the track never runs out.
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "CrossfadeEngine"

        /** Normal position poll interval. */
        private const val POSITION_POLL_MS         = 300L

        /** Fast poll interval used inside the trigger + prebuffer window. */
        private const val FAST_POLL_MS             = 50L

        private const val FADE_STEPS              = 60
        private const val CROSSFADE_GUARD_MS      = 200L
        private const val BEAT_SNAP_WINDOW_MS     = POSITION_POLL_MS / 2
        private const val TEMPO_EASE_STEPS        = 40
        private const val TEMPO_EASE_DURATION_MS  = 4_000L

        /** Extended from ±15% to ±25% to handle typical DJ tempo jumps (e.g. 120→150 BPM). */
        private const val MAX_SPEED_RATIO = 1.33f
        private const val MIN_SPEED_RATIO = 0.75f

        /** Number of bars in a musical phrase. 8 is standard for electronic/pop/hip-hop. */
        private const val PHRASE_BARS = 8

        /** Number of bars per measure (standard 4/4 time). */
        private const val BARS_PER_BEAT_MULTIPLE = 4

        /** Waveform bar count rendered by the UI. */
        private const val WAVEFORM_BARS = 32
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Dual players ──────────────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    /** @Volatile — written by executeCrossfade (Default), read everywhere. */
    @Volatile private var isPrimaryA = true
    private fun primaryPlayer()   = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB else playerA

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    /**
     * Emitted when the position monitor decides it is time to crossfade to the next track.
     * replay=1 prevents a startup race where DjMixService subscribes slightly after emission.
     */
    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    /**
     * Emitted when remaining time enters the pre-buffer window (3× crossfade duration).
     * DjMixService observes this and calls [prebufferTrack] with the selected next track,
     * so the secondary player is already in STATE_READY when the crossfade fires.
     * replay=0 — stale prebuffer requests from previous tracks should not replay.
     */
    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ── Settings kept in sync by DjMixService ─────────────────────────────────
    var crossfadeDurationMs: Long  = 5_000L
    var isRealMixMode: Boolean     = false
    var maxTrackDurationMs: Long   = 120_000L
    @Volatile var useHalfwayMix: Boolean = true

    // ── Internal jobs ─────────────────────────────────────────────────────────
    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var tempoEaseJob: Job?       = null
    private var prebufferJob: Job?       = null

    // ── Beat-aligned state (@Volatile for cross-thread visibility) ─────────────
    @Volatile private var currentTrackBpm: Float        = 0f
    @Volatile private var currentTrackFirstBeatMs: Long = 0L
    @Volatile private var currentTrackBaseVolume: Float = 1.0f
    /** Raw perceptual amplitude from BpmAnalyzer — used for waveform generation. */
    @Volatile private var currentTrackAmplitude: Float  = 0f

    // ── Pre-buffer state ──────────────────────────────────────────────────────
    /** ID of the track currently loaded (silently) into the secondary player. Null if none. */
    @Volatile private var prebufferedTrackId: Long? = null
    @Volatile private var isPrebufferingInProgress = false
    private var lastPrebufferRequestedId: Long? = null

    // ── Miscellaneous state ────────────────────────────────────────────────────
    private var lastRequestedTrackId: Long?    = null

    /** @Volatile — written from ViewModel coroutine, read from engineScope. */
    @Volatile private var pendingNextTrack: PendingTrack? = null

    @Volatile private var isReleased    = false
    private var isInitialized           = false

    val isActive: Boolean get() = isInitialized && !isReleased

    /**
     * Per-bar smoothed amplitudes for the beat-grid waveform.
     * Exponential moving average applied each position-monitor tick.
     * Not @Volatile — only written/read inside engineScope.
     */
    private val waveformSmoothed = FloatArray(WAVEFORM_BARS) { 0f }

    private data class PendingTrack(
        val audioFile: AudioFile,
        val firstBeatMs: Long,
        val bpm: Float,
        val amplitude: Float
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun initialize() {
        if (isInitialized) return
        if (isReleased) {
            engineScope            = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isReleased             = false
            isPrimaryA             = true
            lastRequestedTrackId   = null
            lastPrebufferRequestedId = null
            pendingNextTrack       = null
            prebufferedTrackId     = null
            isPrebufferingInProgress = false
            playerA                = null
            playerB                = null
            _state.value           = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm         = 0f
            currentTrackFirstBeatMs = 0L
            currentTrackBaseVolume  = 1.0f
            currentTrackAmplitude   = 0f
            Log.d(TAG, "initialize: Engine reset after previous release.")
        }
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
                    addListener(createPlayerListener(isPlayerA = true))
                }
                playerB = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, false)
                    setHandleAudioBecomingNoisy(false)
                    skipSilenceEnabled = true
                    addListener(createPlayerListener(isPlayerA = false))
                }
                isInitialized = true
                Log.d(TAG, "initialize: Both players ready.")
            }
        }
    }

    private fun createPlayerListener(isPlayerA: Boolean) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            if (isPrimary) _state.update { it.copy(isPlaying = isPlaying) }
        }
    }

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return
        // Reset pre-buffer state — secondary player will be repurposed for the new primary
        prebufferJob?.cancel()
        prebufferedTrackId = null
        lastPrebufferRequestedId = null
        isPrebufferingInProgress = false
        waveformSmoothed.fill(0f)

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
        currentTrackAmplitude   = amplitude
        currentTrackBaseVolume  = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        Log.d(TAG, "updateCurrentBpmInfo: BPM=$bpm firstBeat=${firstBeatMs}ms Vol=$currentTrackBaseVolume")
    }

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
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.d(TAG, "triggerMixNow: nextTrackRequest emitted for trackId=$currentId")
    }

    /**
     * Silently prepares the secondary player with [audioFile] at volume=0, paused,
     * seeked to [firstBeatMs]. When [queueNextTrack] is called for the same track ID,
     * [executeCrossfade] detects it is already buffered and skips the 2-second wait,
     * eliminating the potential silence gap at crossfade start.
     */
    fun prebufferTrack(audioFile: AudioFile, firstBeatMs: Long, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return

        isPrebufferingInProgress = true
        prebufferJob?.cancel()
        prebufferJob = engineScope.launch {
            withContext(Dispatchers.Main) {
                val secondary = secondaryPlayer() ?: run {
                    isPrebufferingInProgress = false
                    return@withContext
                }
                secondary.stop()
                secondary.clearMediaItems()
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f
                secondary.prepare()
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                // Intentionally NOT calling play() — we are only pre-loading into the buffer
            }
            prebufferedTrackId = audioFile.id
            isPrebufferingInProgress = false
            Log.d(TAG, "prebufferTrack: ready '${audioFile.title}' id=${audioFile.id}")
        }
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
     * Uses a dedicated one-shot Main scope so teardown is guaranteed even after
     * engineScope is cancelled (the original bug where ExoPlayers were never released).
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        Log.d(TAG, "release: Stopping all jobs and releasing players.")

        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        tempoEaseJob?.cancel()
        prebufferJob?.cancel()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
            } catch (e: Exception) {
                Log.e(TAG, "release: Error releasing players", e)
            } finally {
                isInitialized = false
                _state.update { it.copy(waveform = emptyList()) }
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
            // ── Tempo sync ─────────────────────────────────────────────────────
            val outgoingBpm = currentTrackBpm
            val speedFactor = if (outgoingBpm > 0f && nextBpm > 0f) {
                (outgoingBpm / nextBpm).coerceIn(MIN_SPEED_RATIO, MAX_SPEED_RATIO)
            } else 1.0f

            if (speedFactor != 1.0f) {
                Log.d(TAG, "executeCrossfade: tempo sync ${String.format("%.3f", speedFactor)}× " +
                        "(${String.format("%.1f", outgoingBpm)}→${String.format("%.1f", nextBpm)} BPM)")
            }

            // ── Auto-Gain volumes ──────────────────────────────────────────────
            val secondaryBaseVolume = if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume   = currentTrackBaseVolume

            // ── Prepare secondary player ──────────────────────────────────────
            val isAlreadyPrebuffered = prebufferedTrackId == nextTrack.id
            if (!isAlreadyPrebuffered) {
                // Full prepare from scratch
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
                // Buffer wait — only needed when NOT pre-buffered (max 2s)
                var waitMs = 0L
                while (waitMs < 2_000L) {
                    val ready = withContext(Dispatchers.Main) {
                        secondary.playbackState == Player.STATE_READY || secondary.isPlaying
                    }
                    if (ready) break
                    delay(100L); waitMs += 100L
                }
                Log.d(TAG, "executeCrossfade: prepared from scratch (waited ${waitMs}ms)")
            } else {
                // Already buffered and seeked — just set speed and play
                withContext(Dispatchers.Main) {
                    if (speedFactor != 1.0f) {
                        secondary.setPlaybackParameters(PlaybackParameters(speedFactor, 1.0f))
                    }
                    secondary.play()
                }
                prebufferedTrackId = null
                Log.d(TAG, "executeCrossfade: using pre-buffered track — skipped buffer wait")
            }

            // ── Bass Kill EQ on outgoing track ────────────────────────────────
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

            // ── EQUAL-POWER volume ramp ────────────────────────────────────────
            // Uses sin/cos curve instead of linear to maintain constant perceived loudness.
            // At the midpoint: sin(π/4)=cos(π/4)≈0.707 → combined power = 0.707²+0.707²=1.0 ✓
            // Linear crossfade at midpoint: 0.5+0.5=1.0 power but perceived as 0.5+0.5≈0.7 ✓
            val stepDelayMs = (crossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive) break
                val angle = (step.toFloat() / FADE_STEPS) * (PI.toFloat() / 2f)
                val toGain   = sin(angle)   // 0 → 1 on a sin curve
                val fromGain = cos(angle)   // 1 → 0 on a cos curve
                withContext(Dispatchers.Main) {
                    primary.volume   = fromGain * primaryBaseVolume
                    secondary.volume = toGain   * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = toGain) }
                delay(stepDelayMs)
            }

            // ── Finalise swap ─────────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                primary.pause()
                primary.volume = 1f
                primary.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
                secondary.volume = secondaryBaseVolume
            }

            isPrimaryA             = !isPrimaryA
            lastRequestedTrackId   = null
            // Reset pre-buffer state after swap — the new secondary needs fresh prebuffering
            prebufferedTrackId     = null
            lastPrebufferRequestedId = null

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = true,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }
            Log.d(TAG, "executeCrossfade: COMPLETE. PrimaryA=$isPrimaryA")

            // ── Post-crossfade tempo ease ─────────────────────────────────────
            if (speedFactor != 1.0f) {
                tempoEaseJob = engineScope.launch { easeTempoBackToNormal(speedFactor) }
            }

            // Handle any track queued while this crossfade was running
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            // Always reset isCrossfading even if cancelled mid-fade (e.g. user skips)
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try { bassKillEq?.release(); bassKillEq = null } catch (e: Exception) {
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
                val (position, duration, playing) = withContext(Dispatchers.Main) {
                    val p   = primaryPlayer()
                    val dur = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                    Triple(p?.currentPosition ?: 0L, dur, p?.isPlaying ?: false)
                }

                val remaining    = duration - position
                val bpm          = currentTrackBpm
                val firstBeat    = currentTrackFirstBeatMs

                // ── Beat timing ───────────────────────────────────────────────
                val beatLengthMs = if (bpm > 0f) (60_000f / bpm).toLong() else 0L

                val isOnBeatBoundary = if (beatLengthMs > 0L && duration > 0L) {
                    val phase = (position - firstBeat).coerceAtLeast(0L) % beatLengthMs
                    phase <= BEAT_SNAP_WINDOW_MS || phase >= (beatLengthMs - BEAT_SNAP_WINDOW_MS)
                } else true

                // ── 8-bar phrase detection ─────────────────────────────────────
                // Only fire the crossfade at musically coherent points — the last bar of
                // an 8-bar phrase (the "drop zone"). Falls back to any beat when forced.
                val barLengthMs    = beatLengthMs * BARS_PER_BEAT_MULTIPLE
                val phraseLengthMs = barLengthMs  * PHRASE_BARS

                val isAtPhraseBoundary = when {
                    phraseLengthMs <= 0L || firstBeat <= 0L -> true  // No beat data → allow any beat
                    else -> {
                        val elapsed        = (position - firstBeat).coerceAtLeast(0L)
                        val phaseInPhrase  = elapsed % phraseLengthMs
                        val lastBarStart   = phraseLengthMs - barLengthMs
                        // We are in the last bar of the current 8-bar phrase
                        phaseInPhrase >= lastBarStart
                    }
                }

                // Force-allow triggering if remaining time is critically short
                val mustTriggerNow = beatLengthMs > 0L && remaining <= crossfadeDurationMs + beatLengthMs

                // ── Trigger zone & prebuffer zone ─────────────────────────────
                val triggerWindowMs  = crossfadeDurationMs + beatLengthMs
                val prebufferZoneMs  = crossfadeDurationMs * 3 + beatLengthMs

                val inTriggerZone    = duration > 0L && remaining in CROSSFADE_GUARD_MS..triggerWindowMs
                val inPrebufferZone  = duration > 0L && remaining in triggerWindowMs..prebufferZoneMs

                // ── Real Mix Mode ─────────────────────────────────────────────
                val isMaxTimeReached = if (isRealMixMode && duration > 0L) {
                    val mixTriggerMs = if (useHalfwayMix) duration / 2L else maxTrackDurationMs
                    position >= mixTriggerMs && remaining > crossfadeDurationMs
                } else false

                // ── Prebuffer request ─────────────────────────────────────────
                // Ask the Service to select + load the next track into the secondary player
                // while it's still invisible (volume=0). No crossfade yet.
                if (inPrebufferZone
                    && !_state.value.isCrossfading
                    && prebufferedTrackId == null
                    && !isPrebufferingInProgress) {
                    val currentId = _state.value.currentTrack?.id
                    if (currentId != null && currentId != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = currentId
                        _prebufferRequest.tryEmit(currentId)
                        Log.d(TAG, "prebufferRequest emitted [remaining=${remaining}ms]")
                    }
                }

                // ── Crossfade trigger ──────────────────────────────────────────
                val shouldTrigger = playing
                        && !_state.value.isCrossfading
                        && duration > 0L
                        && (inTriggerZone || isMaxTimeReached)
                        && isOnBeatBoundary
                        && (isAtPhraseBoundary || mustTriggerNow)  // phrase-aware

                if (shouldTrigger) {
                    val currentId = _state.value.currentTrack?.id
                    if (currentId != null && currentId != lastRequestedTrackId) {
                        lastRequestedTrackId = currentId
                        Log.d(TAG, "nextTrackRequest emitted " +
                                "[remaining=${remaining}ms, beat=$isOnBeatBoundary, " +
                                "phrase=$isAtPhraseBoundary, forced=$mustTriggerNow]")
                        _nextTrackRequest.tryEmit(currentId)
                    }
                }

                // ── Beat-grid waveform ─────────────────────────────────────────
                // Generated from BPM + firstBeatMs + amplitude — no permissions needed.
                val waveform = if (bpm > 0f && duration > 0L) {
                    generateBeatWaveform(position, bpm, firstBeat, currentTrackAmplitude)
                } else emptyList()

                _state.update {
                    it.copy(
                        currentPositionMs = position,
                        currentDurationMs = duration,
                        waveform          = waveform
                    )
                }

                // ── Adaptive poll interval ─────────────────────────────────────
                // Switch to 50ms when we are anywhere near the trigger or prebuffer windows
                // so beat boundaries are detected with sub-beat precision at high tempos.
                val pollDelayMs = if (inTriggerZone || inPrebufferZone || isMaxTimeReached) {
                    FAST_POLL_MS
                } else {
                    POSITION_POLL_MS
                }
                delay(pollDelayMs)
            }
        }
    }

    // ── Beat-grid waveform generation ─────────────────────────────────────────

    /**
     * Synthesises a 32-bar waveform purely from BPM, firstBeatMs, and amplitude.
     *
     * No android.media.audiofx.Visualizer — no RECORD_AUDIO permission.
     *
     * The bars model low-frequency (bass) and high-frequency (treble) behaviour:
     * - Bass bars (i=0–8): respond strongly to the kick drum envelope (beat phase=0)
     * - Mid bars (i=8–16): respond moderately to kick + snare (beat phase=0.5)
     * - High bars (i=16–32): hold a relatively steady amplitude (constant energy feel)
     *
     * Exponential moving average (α=0.35) smooths frame-to-frame jumps so bars rise
     * and fall fluidly rather than flickering.
     *
     * The static pattern per bar is seeded deterministically from the bar index using
     * Math.sin with an irrational multiplier — avoids obvious periodicity in the display.
     */
    private fun generateBeatWaveform(
        positionMs: Long,
        bpm: Float,
        firstBeatMs: Long,
        amplitude: Float
    ): List<Float> {
        if (bpm <= 0f) return emptyList()

        val beatLengthMs = 60_000.0 / bpm
        val elapsed      = (positionMs - firstBeatMs).toDouble().coerceAtLeast(0.0)
        val phaseInBeat  = (elapsed % beatLengthMs) / beatLengthMs // 0.0 .. 1.0

        // Kick envelope: sharp attack at beat start, rapid exponential decay
        val kickEnvelope = maxOf(0.0, 1.0 - phaseInBeat * 2.5).toFloat()

        // Snare envelope: secondary transient at beat phase 0.5 ("2 and 4")
        val snarePhase    = if (phaseInBeat > 0.5) phaseInBeat - 0.5 else 1.0
        val snareEnvelope = (maxOf(0.0, 1.0 - snarePhase * 3.0) * 0.65).toFloat()

        val beatEnvelope = maxOf(kickEnvelope, snareEnvelope)

        // Scale raw amplitude to a useful visual range (BpmAnalyzer returns ~0.02..0.25 for typical music)
        val scaledAmp = (amplitude * 4.5f).coerceIn(0.18f, 0.95f)

        for (i in 0 until WAVEFORM_BARS) {
            // Deterministic per-bar static height (Weyl sequence — no repetition until wrap-around)
            val staticBase = (Math.sin(i * 2.3999632 + 1.0) * 0.22 + 0.78).toFloat()

            val freqNorm       = i.toFloat() / WAVEFORM_BARS  // 0=bass, 1=treble
            val kickResponse   = 1f - freqNorm * 0.65f        // Bass reacts most to kick
            val steadyContrib  = freqNorm * 0.35f             // Treble has steady component

            val dynamic = beatEnvelope * kickResponse + steadyContrib
            val rawValue = (scaledAmp * staticBase * dynamic).coerceIn(0f, 1f)

            // Exponential moving average: α=0.35 (fast rise, moderate decay)
            waveformSmoothed[i] = waveformSmoothed[i] * 0.65f + rawValue * 0.35f
        }

        return waveformSmoothed.toList()
    }
}