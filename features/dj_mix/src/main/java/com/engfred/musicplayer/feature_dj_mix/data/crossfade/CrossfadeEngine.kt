package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
 * Dual-[ExoPlayer] DJ crossfade engine with BPM-aware mix strategy selection.
 * Pure volume-based crossfading without tempo stretching.
 *
 * 🚨 THREAD SAFETY 🚨
 * ALL ExoPlayer method calls (play, pause, getAudioSessionId, etc) MUST be executed
 * inside `withContext(Dispatchers.Main)`. Calling them on a background coroutine
 * throws IllegalStateException and crashes the app.
 *
 * ── Continuous Play Mode (isRealMixMode = false) ──────────────────────────────
 * When Auto-Mix is OFF the engine runs in "Continuous Play" mode:
 * • Crossfade still executes (equal-power fade, bass-kill EQ, BPM-aware duration).
 * • First-beat seeking is SKIPPED — incoming tracks start from position 0.
 * • Prebuffering skips the firstBeatMs seek for the same reason.
 * • Sampler suppression is handled upstream (DjMixService / DjMixViewModel).
 * Everything else (position monitoring, waveform) is unchanged.
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mixDecisionEngine: MixDecisionEngine
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        private const val POSITION_POLL_MS       = 300L
        private const val FAST_POLL_MS           = 50L
        private const val WAVEFORM_POLL_MS       = 16L
        private const val FADE_STEPS             = 60
        private const val CROSSFADE_GUARD_MS     = 200L
        private const val BEAT_SNAP_WINDOW_MS    = 25L
        private const val PHRASE_BARS            = 8
        private const val BARS_PER_BEAT_MULTIPLE = 4
        private const val WAVEFORM_BARS          = 32

        /**
         * In continuous mode (isRealMixMode = false), start the crossfade this
         * many ms before the track ends. 12 seconds gives enough overlap for
         * the listener to clearly hear the new song arriving naturally.
         */
        private const val CONTINUOUS_TRIGGER_WINDOW_MS = 12_000L

        /**
         * In continuous mode, request prebuffering this many ms before track end.
         * 30 seconds ensures the secondary player is fully ready before the
         * 12-second trigger window opens, even on slow storage.
         */
        private const val CONTINUOUS_PREBUFFER_WINDOW_MS = 30_000L
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Players & Processors ──────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null

    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()            = if (isPrimaryA) playerA            else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB            else playerA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB

    // ── Public State ──────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ── Settings ──────────────────────────────────────────────────────────────
    var crossfadeDurationMs: Long        = 5_000L
    var isRealMixMode: Boolean           = false
    var maxTrackDurationMs: Long         = 120_000L
    @Volatile var useHalfwayMix: Boolean = true

    // ── Internal Jobs & Metadata ──────────────────────────────────────────────
    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var waveformJob: Job?        = null
    private var prebufferJob: Job?       = null

    @Volatile private var currentTrackBpm: Float              = 0f
    @Volatile private var currentTrackFirstBeatMs: Long       = 0L
    @Volatile private var currentTrackBaseVolume: Float       = 1.0f
    @Volatile private var currentTrackAmplitude: Float        = 0f
    @Volatile private var currentWaveformEnvelope: FloatArray = FloatArray(0)
    @Volatile private var currentTrackMixOutMs: Long?         = null
    @Volatile private var postCrossfadeGuardUntilMs: Long     = 0L

    @Volatile private var prebufferedTrackId: Long?       = null
    @Volatile private var isPrebufferingInProgress        = false
    private var lastPrebufferRequestedId: Long?           = null
    private var lastRequestedTrackId: Long?               = null
    @Volatile private var pendingNextTrack: PendingTrack? = null
    @Volatile private var isReleased                      = false
    private var isInitialized                             = false
    @Volatile private var abortCrossfade                  = false

    val isActive: Boolean get() = isInitialized && !isReleased
    private val waveformSmoothed = FloatArray(WAVEFORM_BARS) { 0f }

    private data class PendingTrack(
        val audioFile: AudioFile,
        val firstBeatMs: Long,
        val bpm: Float,
        val amplitude: Float
    )

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALIZATION & LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    fun initialize() {
        if (isInitialized) {
            _nextTrackRequest.resetReplayCache()
            return
        }

        if (isReleased) {
            engineScope              = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isReleased               = false; isPrimaryA = true; abortCrossfade = false
            lastRequestedTrackId     = null; lastPrebufferRequestedId = null
            pendingNextTrack         = null; prebufferedTrackId = null
            isPrebufferingInProgress = false
            playerA = null; playerB = null
            waveformProcessorA = null; waveformProcessorB = null
            _state.value             = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm = 0f; currentTrackFirstBeatMs = 0L
            currentTrackBaseVolume = 1.0f; currentTrackAmplitude = 0f
            currentTrackMixOutMs = null; postCrossfadeGuardUntilMs = 0L
        }

        waveformProcessorA = WaveformCaptureAudioProcessor()
        waveformProcessorB = WaveformCaptureAudioProcessor()

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        playerA = buildExoPlayer(attrs, handleAudioFocus = true,  isPlayerA = true)
        playerB = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = false)

        isInitialized = true
        Log.i(TAG, "[LIFECYCLE] CrossfadeEngine Initialized")
    }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        attrs: AudioAttributes,
        handleAudioFocus: Boolean,
        isPlayerA: Boolean
    ): ExoPlayer {
        val processors: Array<AudioProcessor> = listOfNotNull(
            if (isPlayerA) waveformProcessorA else waveformProcessorB
        ).toTypedArray()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(processors).build()

            override fun buildAudioRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: android.os.Handler,
                eventListener: AudioRendererEventListener, out: ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                out.add(MediaCodecAudioRenderer(
                    context, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, audioSink
                ))
            }
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .build().apply {
                setAudioAttributes(attrs, handleAudioFocus)
                skipSilenceEnabled = false
                addListener(createPlayerListener(isPlayerA))
            }
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        positionMonitorJob?.cancel(); crossfadeJob?.cancel()
        waveformJob?.cancel(); prebufferJob?.cancel()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
            } catch (e: Exception) {
                Log.e(TAG, "[LIFECYCLE] Error releasing players", e)
            } finally {
                waveformProcessorA?.reset(); waveformProcessorA = null
                waveformProcessorB?.reset(); waveformProcessorB = null
                isInitialized = false
                _state.update { it.copy(waveform = emptyList()) }
            }
        }
        engineScope.cancel()
        Log.i(TAG, "[LIFECYCLE] CrossfadeEngine Released")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═════════════════════════════════════════════════════════════════════════

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return

        Log.i(TAG, "[PLAYBACK] Starting Playback: id=${audioFile.id}")

        crossfadeJob?.cancel(); crossfadeJob = null
        prebufferJob?.cancel()
        prebufferedTrackId = null; lastPrebufferRequestedId = null
        isPrebufferingInProgress = false
        isPrimaryA = true
        _nextTrackRequest.resetReplayCache()
        postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
        lastRequestedTrackId = null
        waveformSmoothed.fill(0f)
        currentWaveformEnvelope = FloatArray(0)
        currentTrackMixOutMs = null

        engineScope.launch {
            withContext(Dispatchers.Main) {
                playerB?.pause(); playerB?.clearMediaItems(); playerB?.volume = 0f
                playerB?.playbackParameters = PlaybackParameters.DEFAULT

                val primary = playerA ?: return@withContext
                primary.stop(); primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.playbackParameters = PlaybackParameters.DEFAULT
                primary.prepare()
                primary.play()
            }

            postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
            _state.update { it.copy(currentTrack = audioFile, isPlaying = true, error = null) }
            startPositionMonitoring()
            startWaveformLoop()
        }
    }

    fun playPause() {
        if (isReleased) return
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val p = primaryPlayer() ?: return@withContext
                if (p.isPlaying) p.pause() else p.play()
                _state.update { it.copy(isPlaying = p.isPlaying) }
            }
        }
    }

    fun updateCurrentBpmInfo(bpm: Float, firstBeatMs: Long, amplitude: Float = 0f, waveformEnvelope: FloatArray = FloatArray(0), mixOutMs: Long? = null) {
        currentTrackBpm         = bpm
        currentTrackFirstBeatMs = firstBeatMs
        currentTrackAmplitude   = amplitude
        currentWaveformEnvelope = waveformEnvelope
        currentTrackBaseVolume  = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        currentTrackMixOutMs    = mixOutMs
        Log.d(TAG, "[METADATA] BPM=$bpm BeatMs=$firstBeatMs Vol=$currentTrackBaseVolume")
    }

    fun triggerMixNow() {
        if (isReleased || _state.value.isCrossfading) return
        val currentId = _state.value.currentTrack?.id ?: return
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.i(TAG, "[MIXER] Manual Mix Triggered")
    }

    fun abortCurrentCrossfade() {
        if (!_state.value.isCrossfading) return
        abortCrossfade = true
        crossfadeJob?.cancel()
        Log.w(TAG, "[MIXER] Crossfade Aborted")
    }

    /**
     * Pre-buffers the next track into the secondary ExoPlayer.
     *
     * In Continuous Play mode (isRealMixMode = false) the firstBeatMs seek is skipped
     * so the track is ready at position 0 — consistent with executeCrossfade behaviour.
     */
    fun prebufferTrack(audioFile: AudioFile, firstBeatMs: Long, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return
        isPrebufferingInProgress = true
        prebufferJob?.cancel()

        prebufferJob = engineScope.launch {
            withContext(Dispatchers.Main) {
                val secondary = secondaryPlayer() ?: run {
                    isPrebufferingInProgress = false; return@withContext
                }
                secondary.stop(); secondary.clearMediaItems()
                secondary.playbackParameters = PlaybackParameters.DEFAULT
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f
                secondary.prepare()

                // ── Continuous Play: skip first-beat seek ─────────────────────
                // When Auto-Mix is OFF we always start incoming tracks from position 0,
                // so we intentionally do NOT seek here regardless of firstBeatMs.
                if (isRealMixMode && firstBeatMs > 0L) {
                    secondary.seekTo(firstBeatMs)
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} seeked to firstBeatMs=$firstBeatMs")
                } else {
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} continuous play — no seek")
                }
            }
            prebufferedTrackId = audioFile.id
            isPrebufferingInProgress = false
        }
    }

    fun queueNextTrack(audioFile: AudioFile, firstBeatMs: Long = 0L, nextBpm: Float = 0f, nextAmplitude: Float = 0f) {
        if (isReleased) return
        if (_state.value.isCrossfading) {
            pendingNextTrack = PendingTrack(audioFile, firstBeatMs, nextBpm, nextAmplitude)
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile, firstBeatMs, nextBpm, nextAmplitude)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CROSSFADE EXECUTION
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        firstBeatMs: Long = 0L,
        nextBpm: Float = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primaryRef = primaryPlayer()
        val secondaryRef = secondaryPlayer()
        if (primaryRef == null || secondaryRef == null) return

        abortCrossfade = false

        // ── DJ BRAIN: Energy-aware decision ───────────────────────────────────
        // The MixDecisionEngine still runs in both modes — it drives crossfade duration
        // and bass-kill timing even in Continuous Play mode.
        val decision = mixDecisionEngine.computeMixDecision(
            currentTrackBpm,
            nextBpm,
            crossfadeDurationMs,
            currentTrackAmplitude,
            nextAmplitude
        )

        Log.i(TAG, "[MIXER] ${decision.djNote}")

        _state.update {
            it.copy(
                isCrossfading = true,
                crossfadeProgressFraction = 0f,
                currentMixStrategy = decision.strategy
            )
        }

//        // ── TIMING PROBE — helps identify where pre-fade delay comes from ─────
//        val crossfadeStartMs = System.currentTimeMillis()
//        Log.i(TAG, "[TIMING] Crossfade triggered. prebuffered=${prebufferedTrackId == nextTrack.id} track='${nextTrack.title}'")
//        // ─────────────────────────────────────────────────────────────────────

        var bassKillEq: android.media.audiofx.Equalizer? = null
        try {
            val secondaryBaseVolume = if (nextAmplitude > 0f) {
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f)
            } else 1.0f
            val primaryBaseVolume = currentTrackBaseVolume

            // ── 1. Prepare Secondary ──────────────────────────────────────────
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondaryRef.stop()
                    secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f
                    secondaryRef.prepare()
                } else {
                    prebufferedTrackId = null
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
            }

            // ── 2. Wait for READY ─────────────────────────────────────────────
            var waitMs = 0L
            var ready = false
            while (waitMs < 3000L) {
                ready = withContext(Dispatchers.Main) {
                    secondaryRef.playbackState == Player.STATE_READY && secondaryRef.currentMediaItem != null
                }
                if (ready) break
                delay(100L)
                waitMs += 100L
            }

            // ── TIMING PROBE A→B ─────────────────────────────────────────────
            Log.i(TAG, "[TIMING] A→B ready_wait=${waitMs}ms (ready=$ready, prebuffered=$alreadyPrebuffered)")
            // ─────────────────────────────────────────────────────────────────

            // ── 3. PHASE-ALIGNED SEEK (Auto-Mix ON only) ──────────────────────
            // In Continuous Play mode (isRealMixMode = false) we always start the
            // incoming track from position 0 — no phase alignment, no first-beat seek.
            val safeFirstBeatMs: Long = if (!isRealMixMode) {
                Log.d(TAG, "[MIXER] Continuous Play mode — skipping first-beat seek")
                0L
            } else {
                withContext(Dispatchers.Main) {
                    val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    if (secDuration <= 0L || firstBeatMs <= 0L || currentTrackBpm <= 0f || nextBpm <= 0f) {
                        firstBeatMs
                    } else {
                        val primaryPos = primaryRef.currentPosition
                        val outgoingBeatLenMs = 60_000f / currentTrackBpm
                        val primaryPhaseMs = ((primaryPos - currentTrackFirstBeatMs)
                            .coerceAtLeast(0L) % outgoingBeatLenMs.toLong() + outgoingBeatLenMs.toLong()) % outgoingBeatLenMs.toLong()

                        val incomingBeatLenMs = 60_000f / nextBpm
                        val phaseFraction = primaryPhaseMs.toFloat() / outgoingBeatLenMs
                        val sourcePhaseMs = (phaseFraction * incomingBeatLenMs).toLong()

                        var targetSeek = firstBeatMs + sourcePhaseMs

                        val minRemaining = decision.effectiveCrossfadeDurationMs * 2
                        val maxSafe = (secDuration - minRemaining).coerceAtLeast(0L)
                        targetSeek = targetSeek.coerceAtMost(maxSafe).coerceAtLeast(0L)

                        targetSeek
                    }
                }
            }

            if (safeFirstBeatMs > 0L) {
                withContext(Dispatchers.Main) {
                    secondaryRef.seekTo(safeFirstBeatMs)
                }
            }

            // ── 4. Start Muted ────────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                try {
                    secondaryRef.volume = 0f
                    secondaryRef.play()
                } catch (e: Exception) {
                    Log.e(TAG, "[MIXER] Secondary play failed", e)
                }
            }

            var playWaitMs = 0L
            var secondaryIsPlaying = false
            while (!secondaryIsPlaying && playWaitMs < 1000L) {
                delay(100L)
                playWaitMs += 100L
                secondaryIsPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }

//            // ── TIMING PROBE C→D ─────────────────────────────────────────────
//            Log.i(TAG, "[TIMING] C→D isPlaying_wait=${playWaitMs}ms (playing=$secondaryIsPlaying)")
//            Log.i(TAG, "[TIMING] Total pre-fade delay=${System.currentTimeMillis() - crossfadeStartMs}ms | effectiveFadeDuration=${decision.effectiveCrossfadeDurationMs}ms")
//            // ─────────────────────────────────────────────────────────────────

            // ── 5. Equal-Power Ramp + Energy-Aware Bass Kill ──────────────────
            val primaryStartVolume = withContext(Dispatchers.Main) { primaryRef.volume }
            val stepDelayMs = (decision.effectiveCrossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            var bassKillApplied = false

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break

                val progress = step.toFloat() / FADE_STEPS
                val angle = progress * (PI.toFloat() / 2f)

                if (!bassKillApplied && progress >= decision.bassKillThresholdFraction) {
                    bassKillApplied = true
                    withContext(Dispatchers.Main) {
                        try {
                            val sessionId = primaryRef.audioSessionId
                            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                                val eq = android.media.audiofx.Equalizer(0, sessionId)
                                val bassIndex = mixDecisionEngine.findBassBandIndex(eq)
                                if (bassIndex != null) {
                                    eq.enabled = true
                                    eq.setBandLevel(bassIndex, eq.bandLevelRange[0])
                                    bassKillEq = eq
                                    Log.d(TAG, "[MIXER] Bass kill applied at ${(progress * 100).toInt()}%")
                                } else eq.release()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[MIXER] Bass kill EQ failed: ${e.message}")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    primaryRef.volume = cos(angle) * primaryStartVolume
                    secondaryRef.volume = sin(angle) * secondaryBaseVolume

                    // ── Tempo blend ──────────────────────────────────────────
                    // Gradually shift the outgoing track's playback speed toward
                    // the incoming BPM over the FIRST HALF of the crossfade.
                    // By step FADE_STEPS/2, both tracks are at the same BPM and
                    // the second half is a clean equal-power blend with no clash.
                    //
                    // ExoPlayer's PlaybackParameters(speed) uses pitch-corrected
                    // time stretching (key lock) — the pitch does not change.
                    // This is the same as "tempo sync without pitch shift" on
                    // professional DJ hardware.
                    if (decision.isEffectivelyTempoSynced) {
                        val blendProgress = (progress * 2f).coerceAtMost(1f)
                        val targetSpeed   = 1.0f + (
                                (decision.stretchRatio.toFloat() - 1.0f) * blendProgress
                                )
                        try {
                            primaryRef.playbackParameters = PlaybackParameters(targetSpeed)
                        } catch (e: Exception) {
                            Log.w(TAG, "[MIXER] Tempo blend failed at step $step: ${e.message}")
                        }
                    }
                }
                _state.update { it.copy(crossfadeProgressFraction = sin(angle)) }
                delay(stepDelayMs)
            }

            // ── 6. Abort Check ────────────────────────────────────────────────
            if (abortCrossfade) {
                withContext(Dispatchers.Main) {
                    secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    primaryRef.volume = primaryStartVolume
                    try {
                        secondaryRef.pause()
                        secondaryRef.volume = 0f
                    } catch (e: Exception) {}
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── 7. Swap Players ───────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                try {
                    primaryRef.pause()
                    primaryRef.volume = primaryBaseVolume
                    // Reset tempo on the outgoing player. If tempo blending was
                    // active, it may be playing at a shifted speed. The next time
                    // this ExoPlayer slot is reused (as the secondary player on the
                    // NEXT crossfade), it must start at normal 1.0× speed.
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    if (secondaryRef.isPlaying) {
                        secondaryRef.volume = secondaryBaseVolume
                    }
                } catch (e: Exception) {}
            }

            val swapped = withContext(Dispatchers.Main) {
                if (secondaryRef.isPlaying) {
                    isPrimaryA = !isPrimaryA
                    true
                } else false
            }

            if (!swapped) {
                withContext(Dispatchers.Main) {
                    try {
                        primaryRef.volume = primaryBaseVolume
                        primaryRef.play()
                    } catch (e: Exception) {}
                }
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            withContext(Dispatchers.Main) {
                try { primaryPlayer()?.play() } catch (e: Exception) {}
            }

            // ── 8. Reset State ────────────────────────────────────────────────
            lastRequestedTrackId = null
            prebufferedTrackId = null
            lastPrebufferRequestedId = null
            currentTrackMixOutMs = null
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + decision.effectiveCrossfadeDurationMs

            _state.update {
                it.copy(
                    currentTrack = nextTrack,
                    isPlaying = true,
                    isCrossfading = false,
                    crossfadeProgressFraction = 0f
                )
            }

            Log.i(TAG, "[MIXER] Swap Complete → Next Track: '${nextTrack.title}'")

            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try {
                bassKillEq?.release()
                bassKillEq = null
            } catch (e: Exception) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═════════════════════════════════════════════════════════════════════════

    private fun startPositionMonitoring() {
        positionMonitorJob?.cancel()
        positionMonitorJob = engineScope.launch {

            while (isActive) {
                val (position, duration, playing) = withContext(Dispatchers.Main) {
                    val p = primaryPlayer()
                    val dur = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                    Triple(p?.currentPosition ?: 0L, dur, p?.isPlaying ?: false)
                }

                val remaining    = duration - position
                val bpm          = currentTrackBpm
                val firstBeat    = currentTrackFirstBeatMs
                val beatLengthMs = if (bpm > 0f) (60_000f / bpm).toLong() else 0L

                val isOnBeat = if (beatLengthMs > 0L && duration > 0L) {
                    val phase = (position - firstBeat).coerceAtLeast(0L) % beatLengthMs
                    phase <= BEAT_SNAP_WINDOW_MS || phase >= beatLengthMs - BEAT_SNAP_WINDOW_MS
                } else true

                val phraseLengthMs = beatLengthMs * BARS_PER_BEAT_MULTIPLE * PHRASE_BARS
                val barLengthMs    = beatLengthMs * BARS_PER_BEAT_MULTIPLE
                val isAtPhrase = when {
                    phraseLengthMs <= 0L || firstBeat <= 0L -> true
                    else -> {
                        val phaseInPhrase = (position - firstBeat).coerceAtLeast(0L) % phraseLengthMs
                        phaseInPhrase >= phraseLengthMs - barLengthMs
                    }
                }

                val mustTrigger     = beatLengthMs > 0L && remaining <= crossfadeDurationMs + beatLengthMs
                val triggerWindow   = crossfadeDurationMs + beatLengthMs
                val prebufferZone   = crossfadeDurationMs * 3 + beatLengthMs
                // ── Effective trigger & prebuffer windows ────────────────────
                // In Real Mix mode  : use beat-aligned windows (existing logic).
                // In Continuous mode: use fixed minimum windows so the next track
                //   is always heard coming in naturally, regardless of crossfade
                //   duration setting. Beat/phrase alignment is a DJ concept and
                //   does not apply to continuous background playback.
                val effectiveTriggerWindowMs = if (isRealMixMode) triggerWindow
                else maxOf(triggerWindow, CONTINUOUS_TRIGGER_WINDOW_MS)
                val effectivePrebufferZoneMs = if (isRealMixMode) prebufferZone
                else maxOf(prebufferZone, CONTINUOUS_PREBUFFER_WINDOW_MS)

                val inTriggerZone   = duration > 0L && remaining in CROSSFADE_GUARD_MS..effectiveTriggerWindowMs
                val inPrebufferZone = duration > 0L && remaining in effectiveTriggerWindowMs..effectivePrebufferZoneMs
                val postGuardActive = System.currentTimeMillis() < postCrossfadeGuardUntilMs
                val customMixOut    = currentTrackMixOutMs != null && position >= currentTrackMixOutMs!!

                val mixAtMs: Long? = if (isRealMixMode && duration > 0L) {
                    if (useHalfwayMix) (duration / 2L) + currentTrackFirstBeatMs else maxTrackDurationMs
                } else null

                val isMaxTime = mixAtMs != null && position >= mixAtMs && remaining > crossfadeDurationMs

                // approachingMaxTime only applies in Real Mix mode (mixAtMs is
                // always null in continuous mode — continuous mode uses the wider
                // inPrebufferZone window instead).
                val approachingMaxTime = isRealMixMode &&
                        mixAtMs != null &&
                        position >= (mixAtMs - crossfadeDurationMs * 2) &&
                        position < mixAtMs

                if ((inPrebufferZone || approachingMaxTime) && !_state.value.isCrossfading && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                // In Real Mix mode: only trigger on a beat AND near a phrase boundary
                //   (DJ precision — wrong bar = bad mix).
                // In Continuous mode: trigger as soon as the track enters the window.
                //   Beat/phrase alignment removed — irrelevant for background listening.
                val beatAligned = !isRealMixMode || (isOnBeat && (isAtPhrase || mustTrigger))
                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive && duration > 0L &&
                        (customMixOut || ((inTriggerZone || isMaxTime) && beatAligned))

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] Auto-Mix Triggered: remaining=${remaining}ms")
                    }
                }

                val timeToNextMixMs: Long? = when {
                    _state.value.isCrossfading   -> null
                    !playing || duration <= 0L   -> null
                    customMixOut                 -> 0L
                    currentTrackMixOutMs != null -> (currentTrackMixOutMs!! - position).coerceAtLeast(0L)
                    inTriggerZone                -> 0L
                    inPrebufferZone              -> (remaining - triggerWindow).coerceAtLeast(0L)
                    else                         -> null
                }

                _state.update { it.copy(currentPositionMs = position, currentDurationMs = duration, timeToNextMixMs = timeToNextMixMs) }

                delay(if (inTriggerZone || inPrebufferZone || isMaxTime || customMixOut || approachingMaxTime) FAST_POLL_MS else POSITION_POLL_MS)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WAVEFORM
    // ═════════════════════════════════════════════════════════════════════════

    private fun startWaveformLoop() {
        waveformJob?.cancel()
        waveformJob = engineScope.launch {
            while (isActive) {
                if (currentTrackBpm > 0f && _state.value.currentDurationMs > 0L) {
                    val pos = withContext(Dispatchers.Main) { primaryPlayer()?.currentPosition ?: 0L }
                    _state.update { it.copy(waveform = generateBeatWaveform(pos)) }
                }
                delay(WAVEFORM_POLL_MS)
            }
        }
    }

    private fun generateBeatWaveform(positionMs: Long): List<Float> {
        val realBands = primaryWaveformProcessor()?.computeCurrentBands()

        return if (realBands != null && realBands.any { it > 0.01f }) {
            List(WAVEFORM_BARS) { i ->
                val srcIdx = (i.toFloat() / WAVEFORM_BARS * realBands.size).toInt().coerceIn(0, realBands.size - 1)
                val raw       = realBands[srcIdx]
                val gainCurve = 1f + (i.toFloat() / WAVEFORM_BARS) * 2.5f
                val boosted   = (raw * gainCurve).coerceIn(0f, 1f)
                waveformSmoothed[i] = waveformSmoothed[i] * 0.15f + boosted * 0.85f
                waveformSmoothed[i]
            }
        } else {
            generatePlaceholderWaveform()
        }
    }

    private fun generatePlaceholderWaveform(): List<Float> {
        return List(WAVEFORM_BARS) { i ->
            val target = 0.10f + (Math.sin(i * 1.618 + 0.5) * 0.04f).toFloat()
            waveformSmoothed[i] = waveformSmoothed[i] * 0.80f + target * 0.20f
            waveformSmoothed[i]
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAYER LISTENER
    // ═════════════════════════════════════════════════════════════════════════

    private fun createPlayerListener(isPlayerA: Boolean) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            if (isPrimary) _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                Log.i(TAG, "[PLAYBACK] Player ${if (isPlayerA) "A" else "B"} reached STATE_ENDED")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            if (isPrimary) {
                Log.e(TAG, "[PLAYBACK] Fatal Player Error: ${error.message} (Code: ${error.errorCode})", error)
                _state.update { it.copy(error = "Player error: ${error.message}") }
            }
        }
    }
}