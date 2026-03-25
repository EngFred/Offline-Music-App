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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Observable state of the crossfade engine.
 *
 * [waveform] is generated purely from the beat grid (BPM + firstBeatMs + amplitude)
 * at ~60fps by a dedicated waveform loop — no android.media.audiofx.Visualizer,
 * no RECORD_AUDIO permission required.
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
 * ── Features ──────────────────────────────────────────────────────────────────
 *
 * 1. TEMPO EASE-BACK (Restored)
 * The incoming track is tempo-matched during the crossfade. After the overlap
 * finishes, the track's speed is smoothly eased back to 1.0x (natural speed)
 * over 4 seconds to avoid an abrupt pitch/speed jump.
 *
 * 2. DEDICATED 60fps WAVEFORM LOOP
 * [startWaveformLoop] runs independently at 16ms (~60fps). The bars follow
 * the kick/snare envelope correctly and animate fluidly.
 *
 * 3. EQUAL-POWER CROSSFADE
 * Uses a sin/cos equal-power curve to keep total perceived loudness constant.
 *
 * 4. PRE-BUFFER SECONDARY PLAYER & FAST POLL
 * Eliminates silence gaps by silently readying the next track. Polls at 50ms
 * in the trigger window for sub-beat precision.
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "CrossfadeEngine"

        private const val POSITION_POLL_MS     = 300L
        private const val FAST_POLL_MS         = 50L
        private const val WAVEFORM_POLL_MS     = 16L   // ~60fps

        private const val FADE_STEPS           = 60
        private const val CROSSFADE_GUARD_MS   = 200L
        private const val BEAT_SNAP_WINDOW_MS  = POSITION_POLL_MS / 2

        // Restored Tempo Ease Constants
        private const val TEMPO_EASE_STEPS       = 40
        private const val TEMPO_EASE_DURATION_MS = 4_000L

        private const val MAX_SPEED_RATIO      = 1.33f
        private const val MIN_SPEED_RATIO      = 0.75f

        private const val PHRASE_BARS          = 8
        private const val BARS_PER_BEAT_MULTIPLE = 4

        private const val WAVEFORM_BARS        = 32
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Dual players ──────────────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    @Volatile private var isPrimaryA = true
    private fun primaryPlayer()   = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB else playerA

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ── Settings ───────────────────────────────────────────────────────────────
    var crossfadeDurationMs: Long   = 5_000L
    var isRealMixMode: Boolean      = false
    var maxTrackDurationMs: Long    = 120_000L
    @Volatile var useHalfwayMix: Boolean = true

    // ── Internal jobs ─────────────────────────────────────────────────────────
    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var tempoEaseJob: Job?       = null   // Restored
    private var waveformJob: Job?        = null
    private var prebufferJob: Job?       = null

    // ── Beat-aligned state ────────────────────────────────────────────────────
    @Volatile private var currentTrackBpm: Float        = 0f
    @Volatile private var currentTrackFirstBeatMs: Long = 0L
    @Volatile private var currentTrackBaseVolume: Float = 1.0f
    @Volatile private var currentTrackAmplitude: Float  = 0f

    // ── Pre-buffer state ──────────────────────────────────────────────────────
    @Volatile private var prebufferedTrackId: Long?      = null
    @Volatile private var isPrebufferingInProgress       = false
    private var lastPrebufferRequestedId: Long?          = null

    // ── Misc state ─────────────────────────────────────────────────────────────
    private var lastRequestedTrackId: Long?              = null
    @Volatile private var pendingNextTrack: PendingTrack? = null
    @Volatile private var isReleased                     = false
    private var isInitialized                            = false

    val isActive: Boolean get() = isInitialized && !isReleased

    /**
     * Per-bar smoothed amplitudes for the beat-grid waveform.
     * Only written/read inside engineScope (waveformJob) — not @Volatile.
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
            engineScope              = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isReleased               = false
            isPrimaryA               = true
            lastRequestedTrackId     = null
            lastPrebufferRequestedId = null
            pendingNextTrack         = null
            prebufferedTrackId       = null
            isPrebufferingInProgress = false
            playerA                  = null
            playerB                  = null
            _state.value             = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm          = 0f
            currentTrackFirstBeatMs  = 0L
            currentTrackBaseVolume   = 1.0f
            currentTrackAmplitude    = 0f
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
        prebufferJob?.cancel()
        prebufferedTrackId       = null
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
            startWaveformLoop()
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
            }
            prebufferedTrackId       = audioFile.id
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

    fun release() {
        if (isReleased) return
        isReleased = true
        Log.d(TAG, "release: Stopping all jobs and releasing players.")

        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        tempoEaseJob?.cancel() // Restored
        waveformJob?.cancel()
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
        tempoEaseJob?.cancel() // Cancel any ongoing tempo ease from a previous rapid mix

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
            val stepDelayMs = (crossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive) break
                val angle    = (step.toFloat() / FADE_STEPS) * (PI.toFloat() / 2f)
                val toGain   = sin(angle)
                val fromGain = cos(angle)
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
                // REMOVED the hard snap from the newer file here.
            }

            isPrimaryA               = !isPrimaryA
            lastRequestedTrackId     = null
            prebufferedTrackId       = null
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
            // RESTORED logic: ease the tempo back to natural instead of snapping
            if (speedFactor != 1.0f) {
                tempoEaseJob = engineScope.launch { easeTempoBackToNormal(speedFactor) }
            }

            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try { bassKillEq?.release(); bassKillEq = null } catch (e: Exception) {
                Log.e(TAG, "Bass Kill EQ release failed", e)
            }
        }
    }

    // ── Restored Tempo Ease Back Logic ────────────────────────────────────────

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

                val remaining = duration - position
                val bpm       = currentTrackBpm
                val firstBeat = currentTrackFirstBeatMs

                val beatLengthMs = if (bpm > 0f) (60_000f / bpm).toLong() else 0L

                val isOnBeatBoundary = if (beatLengthMs > 0L && duration > 0L) {
                    val phase = (position - firstBeat).coerceAtLeast(0L) % beatLengthMs
                    phase <= BEAT_SNAP_WINDOW_MS || phase >= (beatLengthMs - BEAT_SNAP_WINDOW_MS)
                } else true

                val barLengthMs    = beatLengthMs * BARS_PER_BEAT_MULTIPLE
                val phraseLengthMs = barLengthMs  * PHRASE_BARS

                val isAtPhraseBoundary = when {
                    phraseLengthMs <= 0L || firstBeat <= 0L -> true
                    else -> {
                        val elapsed       = (position - firstBeat).coerceAtLeast(0L)
                        val phaseInPhrase = elapsed % phraseLengthMs
                        val lastBarStart  = phraseLengthMs - barLengthMs
                        phaseInPhrase >= lastBarStart
                    }
                }

                val mustTriggerNow = beatLengthMs > 0L &&
                        remaining <= crossfadeDurationMs + beatLengthMs

                val triggerWindowMs = crossfadeDurationMs + beatLengthMs
                val prebufferZoneMs = crossfadeDurationMs * 3 + beatLengthMs

                val inTriggerZone   = duration > 0L && remaining in CROSSFADE_GUARD_MS..triggerWindowMs
                val inPrebufferZone = duration > 0L && remaining in triggerWindowMs..prebufferZoneMs

                val isMaxTimeReached = if (isRealMixMode && duration > 0L) {
                    val mixTriggerMs = if (useHalfwayMix) duration / 2L else maxTrackDurationMs
                    position >= mixTriggerMs && remaining > crossfadeDurationMs
                } else false

                // Prebuffer request
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

                // Crossfade trigger
                val shouldTrigger = playing
                        && !_state.value.isCrossfading
                        && duration > 0L
                        && (inTriggerZone || isMaxTimeReached)
                        && isOnBeatBoundary
                        && (isAtPhraseBoundary || mustTriggerNow)

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

                // Position and duration only — waveform is owned by waveformJob
                _state.update {
                    it.copy(
                        currentPositionMs = position,
                        currentDurationMs = duration
                    )
                }

                val pollDelayMs = if (inTriggerZone || inPrebufferZone || isMaxTimeReached)
                    FAST_POLL_MS else POSITION_POLL_MS
                delay(pollDelayMs)
            }
        }
    }

    // ── 60fps waveform loop ───────────────────────────────────────────────────

    private fun startWaveformLoop() {
        waveformJob?.cancel()
        waveformJob = engineScope.launch {
            while (isActive) {
                val bpm       = currentTrackBpm
                val firstBeat = currentTrackFirstBeatMs
                val amplitude = currentTrackAmplitude
                val duration  = _state.value.currentDurationMs

                if (bpm > 0f && duration > 0L) {
                    val position = withContext(Dispatchers.Main) {
                        primaryPlayer()?.currentPosition ?: 0L
                    }
                    val waveform = generateBeatWaveform(position, bpm, firstBeat, amplitude)
                    _state.update { it.copy(waveform = waveform) }
                }

                delay(WAVEFORM_POLL_MS)
            }
        }
    }

    // ── Beat-grid waveform generation ─────────────────────────────────────────

    private fun generateBeatWaveform(
        positionMs: Long,
        bpm: Float,
        firstBeatMs: Long,
        amplitude: Float
    ): List<Float> {
        if (bpm <= 0f) return emptyList()

        val beatLengthMs = 60_000.0 / bpm
        val elapsed      = (positionMs - firstBeatMs).toDouble().coerceAtLeast(0.0)
        val phaseInBeat  = (elapsed % beatLengthMs) / beatLengthMs

        val kickEnvelope  = maxOf(0.0, 1.0 - phaseInBeat * 2.5).toFloat()
        val snarePhase    = if (phaseInBeat > 0.5) phaseInBeat - 0.5 else 1.0
        val snareEnvelope = (maxOf(0.0, 1.0 - snarePhase * 3.0) * 0.65).toFloat()
        val beatEnvelope  = maxOf(kickEnvelope, snareEnvelope)

        val scaledAmp = (amplitude * 4.5f).coerceIn(0.18f, 0.95f)

        for (i in 0 until WAVEFORM_BARS) {
            val staticBase   = (Math.sin(i * 2.3999632 + 1.0) * 0.22 + 0.78).toFloat()
            val freqNorm     = i.toFloat() / WAVEFORM_BARS
            val kickResponse = 1f - freqNorm * 0.65f
            val steadyContrib = freqNorm * 0.35f
            val dynamic      = beatEnvelope * kickResponse + steadyContrib
            val rawValue     = (scaledAmp * staticBase * dynamic).coerceIn(0f, 1f)
            waveformSmoothed[i] = waveformSmoothed[i] * 0.65f + rawValue * 0.35f
        }

        return waveformSmoothed.toList()
    }
}