package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
 *
 * 🚨 THREAD SAFETY 🚨
 * ALL ExoPlayer method calls (play, pause, getAudioSessionId, etc) MUST be executed
 * inside `withContext(Dispatchers.Main)`. Calling them on a background coroutine
 * throws IllegalStateException and crashes the app.
 *
 * Architecture Notes:
 * - Layer 1 Protection: Custom Processors use Zero-Allocation to prevent BT HAL freezing.
 * - Layer 2 Protection: `performSameTrackRecoveryOnSecondary` acts as an OS-level fallback
 * if an OEM Bluetooth stack permanently hangs the primary AudioTrack.
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

        // BT Stall Detection Thresholds
        private const val STALL_STARTUP_GRACE_MS = 2_500L
        private const val STALL_CONFIRM_MS       = 1_500L
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Players & Processors ──────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var processorA: RubberBandAudioProcessor? = null
    private var processorB: RubberBandAudioProcessor? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null

    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()            = if (isPrimaryA) playerA            else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB            else playerA
    private fun primaryProcessor()         = if (isPrimaryA) processorA         else processorB
    private fun secondaryProcessor()       = if (isPrimaryA) processorB         else processorA
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

    @Volatile private var lastPlaybackStartMs: Long = 0L
    @Volatile private var stallRecoveryCount: Int   = 0

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
            playerA = null; playerB = null; processorA = null; processorB = null
            waveformProcessorA = null; waveformProcessorB = null
            _state.value             = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm = 0f; currentTrackFirstBeatMs = 0L
            currentTrackBaseVolume = 1.0f; currentTrackAmplitude = 0f
            currentTrackMixOutMs = null; postCrossfadeGuardUntilMs = 0L
            lastPlaybackStartMs = 0L; stallRecoveryCount = 0
        }

        try {
            processorA = RubberBandAudioProcessor()
            processorB = RubberBandAudioProcessor()
            waveformProcessorA = WaveformCaptureAudioProcessor()
            waveformProcessorB = WaveformCaptureAudioProcessor()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "[LIFECYCLE] RubberBand native lib unavailable — tempo-sync disabled", e)
            processorA = null; processorB = null
            waveformProcessorA = WaveformCaptureAudioProcessor()
            waveformProcessorB = WaveformCaptureAudioProcessor()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        playerA = buildExoPlayer(attrs, handleAudioFocus = true,  isPlayerA = true)
        playerB = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = false)

        isInitialized = true
        Log.i(TAG, "[LIFECYCLE] CrossfadeEngine Initialized (DrcSuppression=ACTIVE)")
    }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        attrs: AudioAttributes,
        handleAudioFocus: Boolean,
        isPlayerA: Boolean
    ): ExoPlayer {
        val processors: Array<AudioProcessor> = listOfNotNull(
            if (isPlayerA) waveformProcessorA else waveformProcessorB,
            if (isPlayerA) processorA         else processorB
        ).toTypedArray()

        val drcFactory = DrcSuppressingMediaCodecAdapterFactory()

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
                    context, drcFactory, mediaCodecSelector, enableDecoderFallback,
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
                processorA?.reset(); processorA = null
                processorB?.reset(); processorB = null
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

        lastPlaybackStartMs = System.currentTimeMillis()
        stallRecoveryCount  = 0

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

                val primary = playerA ?: return@withContext
                primary.stop(); primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f; primary.prepare(); primary.play()
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
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f; secondary.prepare()
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                Log.d(TAG, "[PLAYBACK] Pre-buffered id=${audioFile.id}")
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
        nextTrack: AudioFile, firstBeatMs: Long = 0L, nextBpm: Float = 0f, nextAmplitude: Float = 0f
    ) {
        val primaryRef   = primaryPlayer()
        val secondaryRef = secondaryPlayer()
        if (primaryRef == null || secondaryRef == null) return
        abortCrossfade = false

        val decision = mixDecisionEngine.computeMixDecision(currentTrackBpm, nextBpm, crossfadeDurationMs)
        Log.i(TAG, "[MIXER] ${decision.djNote}")

        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f, currentMixStrategy = decision.strategy) }

        var bassKillEq: android.media.audiofx.Equalizer? = null

        try {
            val secondaryBaseVolume = if (nextAmplitude > 0f) (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume = currentTrackBaseVolume

            // ── 1. Prepare Secondary ──
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondaryRef.stop(); secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f; secondaryRef.prepare()
                } else {
                    prebufferedTrackId = null
                }
                if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                    secondaryProcessor()?.setTimeRatio(decision.stretchRatio)
                }
            }

            // ── 2. Wait for READY ──
            var waitMs = 0L; var ready = false
            while (waitMs < 3000L) {
                ready = withContext(Dispatchers.Main) { secondaryRef.playbackState == Player.STATE_READY && secondaryRef.currentMediaItem != null }
                if (ready) break
                delay(100L); waitMs += 100L
            }

            // ── 3. Clamp Seek ──
            val safeFirstBeatMs: Long = withContext(Dispatchers.Main) {
                val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (secDuration > 0L && firstBeatMs > 0L) {
                    val minRemaining = decision.effectiveCrossfadeDurationMs * 2
                    val maxSafe      = (secDuration - minRemaining).coerceAtLeast(0L)
                    firstBeatMs.coerceAtMost(maxSafe)
                } else firstBeatMs
            }
            if (safeFirstBeatMs > 0L) withContext(Dispatchers.Main) { secondaryRef.seekTo(safeFirstBeatMs) }

            // ── 4. Start Muted ──
            withContext(Dispatchers.Main) {
                try { secondaryRef.volume = 0f; secondaryRef.play() }
                catch (e: Exception) { Log.e(TAG, "[MIXER] Secondary play failed", e) }
            }

            var playWaitMs = 0L; var secondaryIsPlaying = false
            while (!secondaryIsPlaying && playWaitMs < 1000L) {
                delay(100L); playWaitMs += 100L
                secondaryIsPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }

            // ── 5. Equal-Power Ramp & Bass Kill ──
            //
            // 🔧 FIX: Capture the REAL current volume of the primary player before the
            // fade loop begins. On the first-ever crossfade, startPlayback sets
            // player.volume = 1f, while currentTrackBaseVolume is e.g. 0.42f (auto-gain
            // normalised). Using primaryBaseVolume directly as the start point caused an
            // immediate 1.0 → 0.42 jump on frame 1 — the audible "track dying and
            // resurrecting" artefact. Subsequent crossfades were fine because after every
            // swap the new primary was already playing at secondaryBaseVolume ≈ its own
            // currentTrackBaseVolume, so no jump occurred.
            //
            // By reading the live volume here, we always fade smoothly from wherever
            // the player actually is, regardless of normalisation state.
            val primaryStartVolume = withContext(Dispatchers.Main) { primaryRef.volume }

            val stepDelayMs = (decision.effectiveCrossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            var bassKillApplied = false

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break
                val progress = step.toFloat() / FADE_STEPS
                val angle    = progress * (PI.toFloat() / 2f)

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
                        } catch (e: Exception) { Log.w(TAG, "[MIXER] Bass kill EQ failed: ${e.message}") }
                    }
                }

                withContext(Dispatchers.Main) {
                    // 🔧 FIX: fade OUT from primaryStartVolume (real current volume),
                    // not from primaryBaseVolume (normalised target that may differ).
                    primaryRef.volume   = cos(angle) * primaryStartVolume
                    secondaryRef.volume = sin(angle) * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = sin(angle)) }
                delay(stepDelayMs)
            }

            // ── 6. Abort Check ──
            if (abortCrossfade) {
                secondaryProcessor()?.resetRatio()
                withContext(Dispatchers.Main) {
                    // 🔧 FIX: restore to where we started, not to the normalised target.
                    primaryRef.volume = primaryStartVolume
                    try { secondaryRef.pause(); secondaryRef.volume = 0f } catch (e: Exception) {}
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── 7. Swap Players ──
            withContext(Dispatchers.Main) {
                try {
                    primaryRef.pause()
                    primaryRef.volume = primaryBaseVolume  // safe: player is paused, resets for reuse
                    if (secondaryRef.isPlaying) { secondaryRef.volume = secondaryBaseVolume }
                } catch (e: Exception) {}
            }

            val swapped = withContext(Dispatchers.Main) {
                if (secondaryRef.isPlaying) { isPrimaryA = !isPrimaryA; true } else false
            }

            if (!swapped) {
                withContext(Dispatchers.Main) { try { primaryRef.volume = primaryBaseVolume; primaryRef.play() } catch (e: Exception) {} }
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            withContext(Dispatchers.Main) { try { primaryPlayer()?.play() } catch (e: Exception) {} }

            // ── 8. Reset State ──
            lastPlaybackStartMs = System.currentTimeMillis()
            stallRecoveryCount  = 0
            lastRequestedTrackId      = null
            prebufferedTrackId        = null
            lastPrebufferRequestedId  = null
            currentTrackMixOutMs      = null
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + decision.effectiveCrossfadeDurationMs

            _state.update { it.copy(currentTrack = nextTrack, isPlaying = true, isCrossfading = false, crossfadeProgressFraction = 0f) }
            Log.i(TAG, "[MIXER] Swap Complete -> Next Track: '${nextTrack.title}'")

            if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                withContext(Dispatchers.Main) { try { primaryProcessor()?.resetRatio() } catch (e: Exception) {} }
            }

            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading) _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            try { bassKillEq?.release(); bassKillEq = null } catch (e: Exception) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // L2 FALLBACK: BLUETOOTH STALL RECOVERY
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Layer 2 Protection against Android BT HAL freezes.
     * Loads the stalled track onto the secondary player to obtain a fresh AudioTrack
     * session that joins the already-warm Bluetooth pipeline.
     */
    private suspend fun performSameTrackRecoveryOnSecondary(track: AudioFile) {
        val frozenPrimary  = primaryPlayer()
        val freshSecondary = secondaryPlayer()

        if (frozenPrimary == null || freshSecondary == null) {
            if (track.id != lastRequestedTrackId) { lastRequestedTrackId = track.id; _nextTrackRequest.tryEmit(track.id) }
            return
        }

        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        withContext(Dispatchers.Main) {
            try { frozenPrimary.pause() } catch (e: Exception) {}
            frozenPrimary.volume = 0f

            freshSecondary.stop()
            freshSecondary.clearMediaItems()
            freshSecondary.setMediaItem(MediaItem.fromUri(track.uri))
            freshSecondary.volume = 0f
            freshSecondary.prepare()
        }

        var waitMs = 0L; var ready = false
        while (waitMs < 5_000L) {
            ready = withContext(Dispatchers.Main) { freshSecondary.playbackState == Player.STATE_READY && freshSecondary.currentMediaItem != null }
            if (ready) break
            delay(100L); waitMs += 100L
        }

        if (!ready) {
            Log.e(TAG, "[STALL_GUARD] L2 Recovery Failed (Timeout) -> Escalate to next track")
            withContext(Dispatchers.Main) { frozenPrimary.volume = currentTrackBaseVolume; try { frozenPrimary.play() } catch (e: Exception) {} }
            _state.update { it.copy(isCrossfading = false) }
            if (track.id != lastRequestedTrackId) { lastRequestedTrackId = track.id; _nextTrackRequest.tryEmit(track.id) }
            return
        }

        withContext(Dispatchers.Main) { freshSecondary.volume = 0f; try { freshSecondary.play() } catch (e: Exception) {} }

        var playWaitMs = 0L; var secondaryPlaying = false
        while (playWaitMs < 2_000L) {
            secondaryPlaying = withContext(Dispatchers.Main) { freshSecondary.isPlaying }
            if (secondaryPlaying) break
            delay(50L); playWaitMs += 50L
        }

        if (!secondaryPlaying) {
            withContext(Dispatchers.Main) { frozenPrimary.volume = currentTrackBaseVolume; try { frozenPrimary.play() } catch (e: Exception) {} }
            _state.update { it.copy(isCrossfading = false) }
            if (track.id != lastRequestedTrackId) { lastRequestedTrackId = track.id; _nextTrackRequest.tryEmit(track.id) }
            return
        }

        Log.i(TAG, "[STALL_GUARD] L2 Recovery Successful -> Swapping instances")

        val fadeSteps = 6
        for (step in 1..fadeSteps) {
            val progress = step.toFloat() / fadeSteps
            withContext(Dispatchers.Main) {
                freshSecondary.volume = progress * currentTrackBaseVolume
                frozenPrimary.volume  = (1f - progress) * currentTrackBaseVolume
            }
            delay(50L)
        }

        withContext(Dispatchers.Main) {
            isPrimaryA = !isPrimaryA
            try { frozenPrimary.stop() } catch (e: Exception) {}
            frozenPrimary.volume = 0f
            try { primaryPlayer()?.play() } catch (e: Exception) {}
        }

        lastPlaybackStartMs = System.currentTimeMillis()
        postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
        lastRequestedTrackId = null
        prebufferedTrackId = null

        _state.update { it.copy(isPlaying = true, isCrossfading = false, crossfadeProgressFraction = 0f) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═════════════════════════════════════════════════════════════════════════

    private fun startPositionMonitoring() {
        positionMonitorJob?.cancel()
        positionMonitorJob = engineScope.launch {

            var stallSinceMs = 0L

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
                val inTriggerZone   = duration > 0L && remaining in CROSSFADE_GUARD_MS..triggerWindow
                val inPrebufferZone = duration > 0L && remaining in triggerWindow..prebufferZone
                val postGuardActive = System.currentTimeMillis() < postCrossfadeGuardUntilMs
                val customMixOut    = currentTrackMixOutMs != null && position >= currentTrackMixOutMs!!

                val isMaxTime = if (isRealMixMode && duration > 0L) {
                    val mixAt = if (useHalfwayMix) (duration / 2L) + currentTrackFirstBeatMs else maxTrackDurationMs
                    position >= mixAt && remaining > crossfadeDurationMs
                } else false

                // ── 1. Pre-buffer request ──
                if (inPrebufferZone && !_state.value.isCrossfading && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                // ── 2. Crossfade Trigger ──
                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive && duration > 0L &&
                        (customMixOut || ((inTriggerZone || isMaxTime) && isOnBeat && (isAtPhrase || mustTrigger)))

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] Auto-Mix Triggered: remaining=${remaining}ms")
                    }
                }

                // ── 3. Stall Detector (L2 Guard) ──
                val startupGracePassed = System.currentTimeMillis() - lastPlaybackStartMs > STALL_STARTUP_GRACE_MS
                val stallCandidate = playing && position == 0L && duration > 0L && !_state.value.isCrossfading && startupGracePassed

                if (stallCandidate) {
                    if (stallSinceMs == 0L) {
                        stallSinceMs = System.currentTimeMillis()
                        Log.w(TAG, "[STALL_GUARD] Position stuck at 0. Verifying...")
                    }
                } else {
                    stallSinceMs = 0L
                }

                val stallConfirmed = stallCandidate && stallSinceMs > 0L && System.currentTimeMillis() - stallSinceMs > STALL_CONFIRM_MS

                if (stallConfirmed) {
                    stallSinceMs = 0L
                    val stalledTrack = _state.value.currentTrack

                    if (stalledTrack != null && stallRecoveryCount < 1) {
                        stallRecoveryCount++
                        lastPlaybackStartMs = System.currentTimeMillis()
                        Log.w(TAG, "[STALL_GUARD] Confirmed HAL Freeze -> Engaging L2 Recovery (Attempt $stallRecoveryCount)")

                        crossfadeJob?.cancel()
                        crossfadeJob = engineScope.launch { performSameTrackRecoveryOnSecondary(stalledTrack) }
                    } else {
                        val id = stalledTrack?.id
                        if (id != null && id != lastRequestedTrackId) {
                            lastRequestedTrackId = id
                            Log.e(TAG, "[STALL_GUARD] ❌ Recovery exhausted -> Skipping track")
                            _nextTrackRequest.tryEmit(id)
                        }
                    }
                }

                // ── 4. State update ──
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

                delay(if (inTriggerZone || inPrebufferZone || isMaxTime || customMixOut || stallCandidate) FAST_POLL_MS else POSITION_POLL_MS)
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
                if (error.errorCode == 1003) {
                    Log.w(TAG, "[STALL_GUARD] ExoPlayer 10s StuckPlayerException suppressed (Handled by L2 Guard)")
                    return
                }
                Log.e(TAG, "[PLAYBACK] Fatal Player Error: ${error.message} (Code: ${error.errorCode})", error)
                _state.update { it.copy(error = "Player error: ${error.message}") }
            }
        }
    }
}