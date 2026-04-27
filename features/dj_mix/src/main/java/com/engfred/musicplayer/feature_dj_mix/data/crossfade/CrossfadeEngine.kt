package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.engfred.musicplayer.core.data.audio.eq.BandEqAudioProcessor
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.feature_dj_mix.domain.util.DjConstants
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

@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        // ── Polling intervals ─────────────────────────────────────────────────
        private const val POSITION_POLL_MS       = 300L
        private const val FAST_POLL_MS           = 50L
        private const val WAVEFORM_POLL_MS       = 32L   // FIX: was 16L — halves Goertzel CPU cost, UI still smooth
        private const val FOCUS_RESUME_POLL_MS   = 50L

        // ── Crossfade parameters ──────────────────────────────────────────────
        private const val FADE_STEPS             = 80
        private const val TEMPO_SYNC_MAX_RATIO   = 1.08f
        private const val TEMPO_SYNC_MIN_RATIO   = 0.92f

        // ── Waveform ──────────────────────────────────────────────────────────
        private const val WAVEFORM_BARS          = 32

        // ── Trigger / prebuffer thresholds ────────────────────────────────────
        private const val REAL_MIX_TRIGGER_MS    = 60_000L
        private const val REAL_MIX_PREBUFFER_MS  = 90_000L
        private const val TRIGGER_SETUP_BUFFER_MS = 2_000L
        private const val PREBUFFER_MULTIPLIER   = 4L
        private const val MIN_PLAY_TIME_MS       = 10_000L
        private const val POST_CROSSFADE_GUARD_MS = 30_000L
        private const val CROSSFADE_GUARD_MS     = 200L

        // ── Smart trigger pull-forward ────────────────────────────────────────
        private const val MUSICAL_PULLFORWARD_MS     = 24_000L
        private const val MUSICAL_PULLFORWARD_CAP_MS = 32_000L

        // ── Phrase-boundary snap ──────────────────────────────────────────────
        private const val PHRASE_SNAP_MAX_DELTA_MS       = 5_000L
        private const val PHRASE_BOUNDARY_WINDOW_BEATS   = 2.0

        // ── Bass kill ─────────────────────────────────────────────────────────
        private const val BASS_RESTORE_STEP      = FADE_STEPS / 2

        // ── Half-time guard ───────────────────────────────────────────────────
        private const val MIN_HALF_TIME_FOR_SMART_SEARCH = 0.5f

        // ── Timing drift tolerance ────────────────────────────────────────────
        //    If a fade step fires more than this many ms late, log a warning.
        //    Used to detect severe system load without flooding logcat.
        private const val DRIFT_WARN_THRESHOLD_MS = 30L
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── FIX: Single shared Handler for all main-thread volume posts ───────────
    //    Handler.post / postAtTime are fire-and-forget — they never block the
    //    calling (Default dispatcher) coroutine the way withContext(Main) does.
    //    postAtTime uses absolute SystemClock.uptimeMillis() timestamps, so
    //    even if the message queue is momentarily busy, the post is delivered
    //    as close to the target time as the OS allows — and the coroutine side
    //    compensates with clock-corrected delays (see executeCrossfade).
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioFocusCoordinator: AudioFocusCoordinator by lazy {
        AudioFocusCoordinator(
            context = context,
            listener = object : AudioFocusCoordinator.Listener {
                override fun onFocusLost()          = handleFocusLost()
                override fun onFocusLostTransient() = handleFocusLost()
                override fun onFocusGained()        = handleFocusGain()
            }
        )
    }

    @Volatile private var resumeAfterFocusGain: Boolean = false

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null
    private var eqProcessorA: BandEqAudioProcessor? = null
    private var eqProcessorB: BandEqAudioProcessor? = null

    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()            = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB else playerA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB
    private fun outgoingEqProcessor()      = if (isPrimaryA) eqProcessorA else eqProcessorB
    private fun incomingEqProcessor()      = if (isPrimaryA) eqProcessorB else eqProcessorA

    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    var crossfadeDurationMs: Long = 8_000L
    @Volatile var isRealMixMode: Boolean = true

    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job? = null
    private var waveformJob: Job? = null
    private var prebufferJob: Job? = null

    @Volatile private var currentTrackBpm: Float = 0f
    @Volatile private var currentTrackBaseVolume: Float = 1.0f
    @Volatile private var currentTrackAmplitude: Float = 0f
    @Volatile private var currentTrackFirstBeatMs: Long = 0L
    @Volatile private var currentWaveformEnvelope: FloatArray = FloatArray(0)

    @Volatile private var postCrossfadeGuardUntilMs: Long = 0L
    @Volatile private var currentSmartTriggerMs: Long = -1L
    @Volatile private var prebufferedTrackId: Long? = null
    @Volatile private var isPrebufferingInProgress = false
    private var lastPrebufferRequestedId: Long? = null
    private var lastRequestedTrackId: Long? = null
    @Volatile private var pendingNextTrack: PendingTrack? = null
    @Volatile private var isReleased = false
    private var isInitialized = false
    @Volatile private var abortCrossfade = false

    val isActive: Boolean get() = isInitialized && !isReleased

    private val waveformSmoothed = FloatArray(WAVEFORM_BARS) { 0f }

    private data class PendingTrack(
        val audioFile: AudioFile,
        val bpm: Float,
        val firstBeatMs: Long,
        val amplitude: Float
    )

    // ═══════════════════════════════════════════════════════════════════════
    // AUDIO FOCUS
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleFocusLost() {
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary   = primaryPlayer()   ?: return@withContext
                val secondary = secondaryPlayer()
                resumeAfterFocusGain = primary.isPlaying ||
                        (_state.value.isCrossfading && secondary?.isPlaying == true)
                primary.pause()
                secondary?.pause()
            }
            _state.update { it.copy(isPlaying = false) }
            Log.i(TAG, "[FOCUS] Lost — both paused. resumeAfter=$resumeAfterFocusGain")
        }
    }

    private fun handleFocusGain() {
        if (!resumeAfterFocusGain) {
            Log.d(TAG, "[FOCUS] Gained but user-paused")
            return
        }
        resumeAfterFocusGain = false
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary   = primaryPlayer()   ?: return@withContext
                val secondary = secondaryPlayer()
                primary.play()
                if (_state.value.isCrossfading) secondary?.play()
            }
            _state.update { it.copy(isPlaying = true) }
            Log.i(TAG, "[FOCUS] Regained — players resumed")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    fun initialize() {
        if (isInitialized) {
            _nextTrackRequest.resetReplayCache()
            return
        }
        if (isReleased) {
            engineScope                = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isReleased                 = false
            isPrimaryA                 = true
            abortCrossfade             = false
            resumeAfterFocusGain       = false
            lastRequestedTrackId       = null
            lastPrebufferRequestedId   = null
            pendingNextTrack           = null
            prebufferedTrackId         = null
            isPrebufferingInProgress   = false
            playerA = null; playerB    = null
            waveformProcessorA = null; waveformProcessorB = null
            eqProcessorA = null;       eqProcessorB       = null
            _state.value               = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm            = 0f
            currentTrackBaseVolume     = 1.0f
            currentTrackAmplitude      = 0f
            currentTrackFirstBeatMs    = 0L
            currentWaveformEnvelope    = FloatArray(0)
            postCrossfadeGuardUntilMs  = 0L
            currentSmartTriggerMs      = -1L
        }

        waveformProcessorA = WaveformCaptureAudioProcessor()
        waveformProcessorB = WaveformCaptureAudioProcessor()
        eqProcessorA       = BandEqAudioProcessor()
        eqProcessorB       = BandEqAudioProcessor()

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        playerA = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = true)
        playerB = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = false)

        isInitialized = true
        Log.i(TAG, "[LIFECYCLE] Initialized — crossfade engine ready")
    }

    fun applyEqPreset(preset: AudioPreset) {
        eqProcessorA?.setPreset(preset)
        eqProcessorB?.setPreset(preset)
        Log.d(TAG, "[EQ] Preset applied: $preset")
    }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        attrs: AudioAttributes,
        handleAudioFocus: Boolean,
        isPlayerA: Boolean
    ): ExoPlayer {
        val eqProcessor       = if (isPlayerA) eqProcessorA else eqProcessorB
        val waveformProcessor = if (isPlayerA) waveformProcessorA else waveformProcessorB
        val processors: Array<AudioProcessor> =
            listOfNotNull(eqProcessor, waveformProcessor).toTypedArray()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(processors)
                .build()

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: android.os.Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                out.add(
                    MediaCodecAudioRenderer(
                        context, mediaCodecSelector, enableDecoderFallback,
                        eventHandler, eventListener, audioSink
                    )
                )
            }
        }

        return ExoPlayer.Builder(context, renderersFactory).build().apply {
            setAudioAttributes(attrs, handleAudioFocus)
            skipSilenceEnabled = false
            addListener(createPlayerListener(isPlayerA))
        }
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        waveformJob?.cancel()
        prebufferJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null) // FIX: cancel any pending volume posts
        audioFocusCoordinator.abandon()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
            } catch (e: Exception) {
                Log.e(TAG, "[LIFECYCLE] Error releasing players", e)
            } finally {
                waveformProcessorA?.reset(); waveformProcessorA = null
                waveformProcessorB?.reset(); waveformProcessorB = null
                eqProcessorA = null; eqProcessorB = null
                isInitialized = false
                _state.update { it.copy(waveform = emptyList()) }
            }
        }
        engineScope.cancel()
        Log.i(TAG, "[LIFECYCLE] Released")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROL
    // ═══════════════════════════════════════════════════════════════════════

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return
        Log.i(TAG, "[PLAYBACK] Starting: id=${audioFile.id} '${audioFile.title}'")

        crossfadeJob?.cancel(); crossfadeJob = null
        prebufferJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null) // FIX: clear any in-flight volume posts
        prebufferedTrackId         = null
        lastPrebufferRequestedId   = null
        isPrebufferingInProgress   = false
        isPrimaryA                 = true
        _nextTrackRequest.resetReplayCache()
        postCrossfadeGuardUntilMs  = System.currentTimeMillis() + 5_000L
        lastRequestedTrackId       = null
        waveformSmoothed.fill(0f)
        currentWaveformEnvelope    = FloatArray(0)
        currentSmartTriggerMs      = -1L
        resumeAfterFocusGain       = false
        currentTrackBaseVolume     = 1.0f

        engineScope.launch {
            withContext(Dispatchers.Main) { audioFocusCoordinator.request() }
            withContext(Dispatchers.Main) {
                playerB?.pause(); playerB?.clearMediaItems()
                playerB?.volume             = 0f
                playerB?.playbackParameters = PlaybackParameters.DEFAULT

                val primary = playerA ?: return@withContext
                primary.stop(); primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume             = 1f
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
                val primary       = primaryPlayer()   ?: return@withContext
                val secondary     = secondaryPlayer()
                val isCrossfading = _state.value.isCrossfading
                if (primary.isPlaying) {
                    resumeAfterFocusGain = false
                    primary.pause()
                    if (isCrossfading) secondary?.pause()
                } else {
                    primary.play()
                    if (isCrossfading) secondary?.play()
                }
                _state.update { it.copy(isPlaying = primary.isPlaying) }
            }
        }
    }

    fun updateCurrentBpmInfo(
        bpm: Float,
        firstBeatMs: Long = 0L,
        amplitude: Float = 0f,
        waveformEnvelope: FloatArray = FloatArray(0)
    ) {
        currentTrackBpm         = bpm
        currentTrackFirstBeatMs = firstBeatMs
        currentTrackAmplitude   = amplitude
        currentWaveformEnvelope = waveformEnvelope
        currentSmartTriggerMs   = -1L

        val normalisedVolume = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        currentTrackBaseVolume = normalisedVolume

        engineScope.launch {
            withContext(Dispatchers.Main) {
                primaryPlayer()?.volume = normalisedVolume.coerceIn(0f, 1f)
            }
        }
        Log.d(TAG, "[METADATA] BPM=$bpm amplitude=${"%.4f".format(amplitude)} normVol=${"%.3f".format(normalisedVolume)}")
    }

    fun triggerMixNow() {
        if (isReleased || _state.value.isCrossfading) return
        val currentId = _state.value.currentTrack?.id ?: return
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.i(TAG, "[MIXER] Manual mix triggered")
    }

    fun abortCurrentCrossfade() {
        if (!_state.value.isCrossfading) return
        abortCrossfade = true
        crossfadeJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null) // FIX: cancel pending volume posts immediately
        Log.w(TAG, "[MIXER] Crossfade aborted by caller")
    }

    fun prebufferTrack(audioFile: AudioFile, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return

        isPrebufferingInProgress = true
        prebufferJob?.cancel()
        prebufferJob = engineScope.launch {
            withContext(Dispatchers.Main) {
                val secondary = secondaryPlayer() ?: run { isPrebufferingInProgress = false; return@withContext }
                secondary.stop()
                secondary.clearMediaItems()
                secondary.playbackParameters = PlaybackParameters.DEFAULT
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f
                secondary.prepare()
                Log.d(TAG, "[PREBUFFER] id=${audioFile.id} prepared from position 0")
            }
            prebufferedTrackId       = audioFile.id
            isPrebufferingInProgress = false
        }
    }

    fun queueNextTrack(
        audioFile: AudioFile,
        nextBpm: Float = 0f,
        nextFirstBeatMs: Long = 0L,
        nextAmplitude: Float = 0f
    ) {
        if (isReleased) return
        if (_state.value.isCrossfading) {
            pendingNextTrack = PendingTrack(audioFile, nextBpm, nextFirstBeatMs, nextAmplitude)
            Log.d(TAG, "[MIXER] Crossfade in progress — '${audioFile.title}' queued as pending")
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile, nextBpm, nextFirstBeatMs, nextAmplitude)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROSSFADE EXECUTION
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        nextBpm: Float = 0f,
        nextFirstBeatMs: Long = 0L,
        nextAmplitude: Float = 0f
    ) {
        val primaryRef   = primaryPlayer()   ?: return
        val secondaryRef = secondaryPlayer() ?: return

        abortCrossfade = false

        // ── Tempo ratio ───────────────────────────────────────────────────────
        val (tempoRatio, targetIncomingBpm) = if (currentTrackBpm > 0f && nextBpm > 0f) {
            val bestHarmonic = DjConstants.HARMONIC_RATIOS.minByOrNull { ratio ->
                abs((currentTrackBpm * ratio) - nextBpm)
            } ?: 1.0f
            val targetBpm  = currentTrackBpm * bestHarmonic
            val speedRatio = targetBpm / nextBpm
            Pair(speedRatio, targetBpm)
        } else {
            Pair(1.0f, nextBpm)
        }

        val isLargeBpmGap       = tempoRatio < TEMPO_SYNC_MIN_RATIO || tempoRatio > TEMPO_SYNC_MAX_RATIO
        val effectiveDurationMs = crossfadeDurationMs.coerceIn(2_000L, 16_000L)

        Log.i(TAG, "")
        Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║ DJ CROSSFADE START ║")
        Log.i(TAG, "╠══════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║ Outgoing : '${_state.value.currentTrack?.title}'")
        Log.i(TAG, "║ Incoming : '${nextTrack.title}' (starts from 0)")
        Log.i(TAG, "║ Mode     : ${if (isRealMixMode) "REAL MIX" else "NEAR-END"}")
        Log.i(TAG, "║ Duration : ${effectiveDurationMs}ms  Steps: $FADE_STEPS")
        Log.i(TAG, "║ Out BPM  : $currentTrackBpm  In BPM: $nextBpm")
        Log.i(TAG, "║ BPM ratio: ${"%.3f".format(tempoRatio)}" +
                if (isLargeBpmGap) " ⚠ LARGE GAP — tempo sync skipped"
                else " (within ±8% harmonic window)")
        Log.i(TAG, "╚══════════════════════════════════════════════════════════╝")

        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        val outgoingEq       = outgoingEqProcessor()
        val incomingEq       = incomingEqProcessor()
        val originalOutGains = outgoingEq?.getGains() ?: DoubleArray(10) { 0.0 }
        val originalInGains  = incomingEq?.getGains() ?: DoubleArray(10) { 0.0 }

        try {
            val normalizedPrimaryVol = currentTrackBaseVolume.coerceIn(0.2f, 1.0f)
            val secondaryBaseVolume  = (if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f).coerceIn(0f, 1f)

            withContext(Dispatchers.Main) {
                primaryRef.volume = normalizedPrimaryVol
            }

            // ── ① Secondary prepare / reuse ───────────────────────────────────
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondaryRef.stop()
                    secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume             = 0f
                    secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    secondaryRef.prepare()
                    Log.d(TAG, "[CROSSFADE] ① Secondary: fresh prepare from position 0")
                } else {
                    prebufferedTrackId = null
                    Log.d(TAG, "[CROSSFADE] ① Secondary: reusing prebuffered track at position 0")
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
            }

            // ── ② Wait for STATE_READY ────────────────────────────────────────
            var waitMs = 0L
            var ready  = false
            while (waitMs < 5_000L) {
                ready = withContext(Dispatchers.Main) {
                    secondaryRef.playbackState == Player.STATE_READY &&
                            secondaryRef.currentMediaItem != null
                }
                if (ready) break
                delay(50L)
                waitMs += 50L
            }
            Log.i(TAG, "[CROSSFADE] ② Ready in ${waitMs}ms (ready=$ready prebuffered=$alreadyPrebuffered)")

            if (!ready) {
                Log.e(TAG, "[CROSSFADE] ✗ Secondary never reached STATE_READY — aborting")
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── FIX #1: Phase alignment — Stage A ────────────────────────────
            val initialOutgoingPositionMs = withContext(Dispatchers.Main) { primaryRef.currentPosition }
            val rawPhaseAlignedMs = calculatePhaseAlignedSeekMs(
                outgoingPositionMs  = initialOutgoingPositionMs,
                outgoingFirstBeatMs = currentTrackFirstBeatMs,
                outgoingBpm         = currentTrackBpm,
                incomingFirstBeatMs = nextFirstBeatMs
            )
            val initialSeekMs = snapToPhraseBoundaryMs(
                seekMs              = rawPhaseAlignedMs,
                incomingFirstBeatMs = nextFirstBeatMs,
                incomingBpm         = nextBpm
            )

            withContext(Dispatchers.Main) {
                secondaryRef.seekTo(initialSeekMs)
                secondaryRef.volume = 0f
                try { secondaryRef.play() } catch (e: Exception) {
                    Log.e(TAG, "[CROSSFADE] Secondary play() failed", e)
                }
            }

            // Wait for secondary to confirm playing
            var playWaitMs       = 0L
            var secondaryPlaying = false
            while (!secondaryPlaying && playWaitMs < 1_500L) {
                delay(50L)
                playWaitMs       += 50L
                secondaryPlaying  = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }
            Log.i(TAG, "[CROSSFADE] ③ Secondary playing=${secondaryPlaying} after ${playWaitMs}ms")

            // ── FIX #1: Phase alignment — Stage B (corrective seek) ──────────
            val correctedOutgoingPositionMs = withContext(Dispatchers.Main) { primaryRef.currentPosition }
            val correctedRawPhaseMs = calculatePhaseAlignedSeekMs(
                outgoingPositionMs  = correctedOutgoingPositionMs,
                outgoingFirstBeatMs = currentTrackFirstBeatMs,
                outgoingBpm         = currentTrackBpm,
                incomingFirstBeatMs = nextFirstBeatMs
            )
            val correctedSeekMs     = snapToPhraseBoundaryMs(
                seekMs              = correctedRawPhaseMs,
                incomingFirstBeatMs = nextFirstBeatMs,
                incomingBpm         = nextBpm
            )
            val secondaryCurrentPos = withContext(Dispatchers.Main) { secondaryRef.currentPosition }
            val correctionDeltaMs   = abs(correctedSeekMs - secondaryCurrentPos)

            if (correctionDeltaMs > 20L) {
                withContext(Dispatchers.Main) { secondaryRef.seekTo(correctedSeekMs) }
                Log.i(TAG, "[PHASE SYNC] Corrective seek: ${secondaryCurrentPos}ms → ${correctedSeekMs}ms " +
                        "(Δ=${correctionDeltaMs}ms, outgoing advanced ${correctedOutgoingPositionMs - initialOutgoingPositionMs}ms during setup)")
            } else {
                Log.d(TAG, "[PHASE SYNC] Corrective seek skipped — Δ=${correctionDeltaMs}ms within tolerance")
            }

            // ── ④ Tempo sync ──────────────────────────────────────────────────
            val tempoSyncRatio   = if (!isLargeBpmGap && currentTrackBpm > 0f && nextBpm > 0f)
                tempoRatio.coerceIn(TEMPO_SYNC_MIN_RATIO, TEMPO_SYNC_MAX_RATIO)
            else 1.0f

            val tempoSyncApplied = abs(tempoSyncRatio - 1.0f) > 0.001f
            if (tempoSyncApplied) {
                withContext(Dispatchers.Main) {
                    secondaryRef.playbackParameters = PlaybackParameters(tempoSyncRatio)
                }
                Log.i(TAG, "[CROSSFADE] ④ Tempo sync: ${nextBpm}bpm → " +
                        "${"%.1f".format(targetIncomingBpm)}bpm speed=${"%.4f".format(tempoSyncRatio)}")
            } else {
                Log.d(TAG, "[CROSSFADE] ④ Tempo sync skipped (largeBpmGap=$isLargeBpmGap " +
                        "ratio=${"%.4f".format(tempoSyncRatio)})")
            }

            // ── ④b Bass kill ──────────────────────────────────────────────────
            val outBassKillGains = originalOutGains.clone()
            val inBassKillGains  = originalInGains.clone()
            if (outBassKillGains.size >= 3) {
                outBassKillGains[0] = -12.0; outBassKillGains[1] = -12.0; outBassKillGains[2] = -12.0
            }
            if (inBassKillGains.size >= 3) {
                inBassKillGains[0] = -12.0; inBassKillGains[1] = -12.0; inBassKillGains[2] = -12.0
            }
            withContext(Dispatchers.Main) {
                outgoingEq?.setGains(outBassKillGains)
                incomingEq?.setGains(inBassKillGains)
            }
            Log.i(TAG, "[CROSSFADE] ④b Bass Kill: outgoing -12dB sub-bass | incoming -12dB sub-bass")

            // ── ⑤ Equal-power fade — CLOCK-COMPENSATED, NON-BLOCKING ─────────
            //
            // ORIGINAL PROBLEM (proven by log timestamps):
            //   withContext(Dispatchers.Main) blocks the Default-dispatcher coroutine
            //   while waiting for the main thread. Over 80 steps, this compounds:
            //     Crossfade 1: configured 8000ms → actual 11,231ms (+40% drift)
            //     Crossfade 2: configured 8000ms → actual 10,806ms (+35% drift)
            //     Crossfade 3: configured 8000ms → actual 10,083ms (+26% drift)
            //
            // FIX — two changes working together:
            //
            //   A) Handler.postAtTime instead of withContext(Main):
            //      mainHandler.postAtTime(runnable, uptimeMs) posts the volume
            //      update with an ABSOLUTE timestamp. The main thread delivers
            //      it as close to that time as possible, but the calling coroutine
            //      is NOT blocked waiting for it — it moves on immediately.
            //
            //   B) Clock-compensated delay instead of fixed delay(stepDelayMs):
            //      Each iteration calculates the ACTUAL wall-clock time remaining
            //      until the next step's target time. If a previous step ran late,
            //      this naturally shortens the next wait — catching up without
            //      accumulating drift. If a step somehow runs early, the wait
            //      increases to hold timing.
            //
            //   Together these guarantee the fade completes in effectiveDurationMs
            //   regardless of main-thread load. On the device that showed +40% drift,
            //   this should bring actual duration within ~1-2% of configured.
            //
            // PAUSE HANDLING:
            //   Pause is tracked via a pauseStartedAt timestamp. When playback
            //   resumes, the fade clock is shifted forward by the pause duration
            //   so the remaining steps still play out at the correct speed.
            //
            // ABORT:
            //   mainHandler.removeCallbacksAndMessages(null) cancels all pending
            //   postAtTime volume updates atomically before restoring volumes.

            val commonFadeRef   = maxOf(normalizedPrimaryVol, secondaryBaseVolume).coerceIn(0f, 1f)
            val stepDelayMs     = (effectiveDurationMs / FADE_STEPS).coerceAtLeast(16L)
            val bassRestoreStep = BASS_RESTORE_STEP

            Log.i(TAG, "[CROSSFADE] ⑤ Fade: out ${"%.3f".format(normalizedPrimaryVol)}→0 " +
                    "in 0→${"%.3f".format(secondaryBaseVolume)} " +
                    "commonRef=${"%.3f".format(commonFadeRef)} " +
                    "$FADE_STEPS steps × ${stepDelayMs}ms (clock-compensated)")

            // Pre-compute all volume values — no math inside the timed loop
            val outVols = FloatArray(FADE_STEPS + 1)
            val inVols  = FloatArray(FADE_STEPS + 1)
            for (step in 1..FADE_STEPS) {
                val angle = (step.toFloat() / FADE_STEPS) * (PI.toFloat() / 2f)
                outVols[step] = (cos(angle) * commonFadeRef).coerceIn(0f, 1f)
                inVols[step]  = (sin(angle) * commonFadeRef).coerceIn(0f, 1f)
            }

            var incomingBassRestored = false

            // Fade clock anchored to uptimeMillis (monotonic, unaffected by wall-clock changes)
            val fadeStartUptimeMs = SystemClock.uptimeMillis()
            // Track accumulated pause time so the clock is paused when audio is paused
            var pauseStartUptimeMs = 0L
            var totalPausedMs      = 0L
            var wasPaused          = false

            var maxDriftWarnedMs   = 0L // suppress duplicate drift warnings

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break

                // ── Pause-aware clock ─────────────────────────────────────────
                // If playback is paused, spin here and accumulate pause duration.
                // The fade clock is shifted forward by totalPausedMs so the
                // remaining steps still play at the configured speed after resume.
                while (!_state.value.isPlaying && engineScope.isActive && !abortCrossfade) {
                    if (!wasPaused) {
                        pauseStartUptimeMs = SystemClock.uptimeMillis()
                        wasPaused = true
                    }
                    delay(FOCUS_RESUME_POLL_MS)
                }
                if (wasPaused) {
                    totalPausedMs += SystemClock.uptimeMillis() - pauseStartUptimeMs
                    wasPaused = false
                }
                if (abortCrossfade) break

                // ── Bass restore at midpoint (EQ call, still needs Main) ───────
                // This is a rare call (once per crossfade) so withContext is fine.
                if (!incomingBassRestored && step >= bassRestoreStep) {
                    incomingBassRestored = true
                    withContext(Dispatchers.Main) { incomingEq?.setGains(originalInGains) }
                    val pct = (step.toFloat() / FADE_STEPS * 100).toInt()
                    Log.i(TAG, "[CROSSFADE] ⑥ Bass restore: incoming sub-bass restored at $pct%")
                }

                // ── FIX A: Fire-and-forget volume update via postAtTime ────────
                val outV = outVols[step]
                val inV  = inVols[step]
                val targetUptimeMs = fadeStartUptimeMs + totalPausedMs + step * stepDelayMs
                mainHandler.postAtTime({
                    primaryRef.volume   = outV
                    secondaryRef.volume = inV
                }, targetUptimeMs)

                // Progress update — cheap state copy, no blocking
                val sinAngle = inV / commonFadeRef.coerceAtLeast(0.001f)
                _state.update { it.copy(crossfadeProgressFraction = sinAngle.coerceIn(0f, 1f)) }

                // Milestone logging (3 points per crossfade — minimal overhead)
                if (step == FADE_STEPS / 4 || step == FADE_STEPS / 2 || step == (FADE_STEPS * 3) / 4) {
                    val outPower  = outV * outV
                    val inPower   = inV  * inV
                    val totalRef  = commonFadeRef * commonFadeRef
                    val normPower = if (totalRef > 0f) (outPower + inPower) / totalRef else 0f
                    Log.i(TAG, "[CROSSFADE] ${(step * 100 / FADE_STEPS)}% │ " +
                            "out=${"%.3f".format(outV)} in=${"%.3f".format(inV)} │ " +
                            "power=${"%.3f".format(normPower)} (1.000=perfect equal-power)")
                }

                // ── FIX B: Clock-compensated delay ────────────────────────────
                // How many ms until this step's target time?
                // If negative (we're already late), delay(0) effectively yields
                // the coroutine for one frame rather than skipping entirely.
                val now          = SystemClock.uptimeMillis()
                val waitMs       = (targetUptimeMs - now).coerceAtLeast(0L)
                val driftMs      = now - targetUptimeMs  // positive = running late

                if (driftMs > DRIFT_WARN_THRESHOLD_MS && driftMs > maxDriftWarnedMs) {
                    maxDriftWarnedMs = driftMs
                    Log.w(TAG, "[CROSSFADE] Timing drift: step=$step behind by ${driftMs}ms — compensating")
                }

                if (waitMs > 0L) delay(waitMs)
                // If waitMs == 0: we're late. Don't delay at all — the next step's
                // targetUptimeMs is in the past too, so it will also fire immediately,
                // catching up until we're back on schedule.
            }

            if (abortCrossfade) {
                mainHandler.removeCallbacksAndMessages(null) // cancel all pending posts
                Log.w(TAG, "[CROSSFADE] ✗ Aborted — restoring primary volume")
                withContext(Dispatchers.Main) {
                    primaryRef.volume             = normalizedPrimaryVol
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    outgoingEq?.setGains(originalOutGains)
                    incomingEq?.setGains(originalInGains)
                    try {
                        secondaryRef.pause()
                        secondaryRef.volume             = 0f
                        secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    } catch (_: Exception) {}
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── ↔ Swap ────────────────────────────────────────────────────────
            // Small safety wait: let the last postAtTime volume post land before
            // we swap roles. At this point we're post-loop so the coroutine is
            // free — a brief yield is fine and prevents a race where swap
            // completes before the final volume=0 post fires.
            delay(stepDelayMs.coerceAtMost(32L))

            Log.i(TAG, "[CROSSFADE] ↔ Swap: '${nextTrack.title}' becomes primary")
            withContext(Dispatchers.Main) {
                if (_state.value.isPlaying) {
                    secondaryRef.volume = secondaryBaseVolume.coerceIn(0f, 1f)
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT

                outgoingEq?.setGains(originalOutGains)
                if (!incomingBassRestored) incomingEq?.setGains(originalInGains)

                isPrimaryA = !isPrimaryA

                try {
                    primaryRef.pause()
                    primaryRef.volume             = 0f
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                } catch (_: Exception) {}

                if (_state.value.isPlaying) primaryPlayer()?.play()
            }

            val actualPlaying = withContext(Dispatchers.Main) { primaryPlayer()?.isPlaying ?: false }

            lastRequestedTrackId      = null
            prebufferedTrackId        = null
            lastPrebufferRequestedId  = null
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + POST_CROSSFADE_GUARD_MS

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = actualPlaying,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }

            Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
            Log.i(TAG, "║ DJ CROSSFADE COMPLETE ✓ ║")
            Log.i(TAG, "╚══════════════════════════════════════════════════════════╝")

            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.bpm, pending.firstBeatMs, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE ALIGNMENT
    // ═══════════════════════════════════════════════════════════════════════

    private fun calculatePhaseAlignedSeekMs(
        outgoingPositionMs: Long,
        outgoingFirstBeatMs: Long,
        outgoingBpm: Float,
        incomingFirstBeatMs: Long
    ): Long {
        if (outgoingBpm <= 0f) {
            Log.d(TAG, "[PHASE SYNC] No outgoing BPM — falling back to incomingFirstBeat=${incomingFirstBeatMs}ms")
            return incomingFirstBeatMs.coerceAtLeast(0L)
        }
        val beatIntervalMs  = 60_000.0 / outgoingBpm
        val outgoingPhaseMs = if (outgoingPositionMs >= outgoingFirstBeatMs) {
            (outgoingPositionMs - outgoingFirstBeatMs).toDouble().mod(beatIntervalMs)
        } else {
            outgoingPositionMs.toDouble().mod(beatIntervalMs)
        }
        val seekMs = (incomingFirstBeatMs + outgoingPhaseMs).toLong().coerceAtLeast(0L)

        Log.i(TAG, "[PHASE SYNC] outPos=${outgoingPositionMs}ms " +
                "outFirstBeat=${outgoingFirstBeatMs}ms " +
                "beatInterval=${"%.1f".format(beatIntervalMs)}ms " +
                "outPhase=${"%.1f".format(outgoingPhaseMs)}ms " +
                "inFirstBeat=${incomingFirstBeatMs}ms " +
                "→ seekTo=${seekMs}ms")
        return seekMs
    }

    private fun snapToPhraseBoundaryMs(
        seekMs: Long,
        incomingFirstBeatMs: Long,
        incomingBpm: Float
    ): Long {
        if (incomingBpm <= 0f || incomingFirstBeatMs <= 0L) return seekMs
        if (seekMs < incomingFirstBeatMs) return seekMs

        val beatIntervalMs = 60_000.0 / incomingBpm
        val barIntervalMs  = beatIntervalMs * 4.0

        val bestMs = listOf(8, 16).map { bars ->
            val phraseMs     = barIntervalMs * bars
            val posRelative  = (seekMs - incomingFirstBeatMs).toDouble()
            val phraseCount  = (posRelative / phraseMs).toLong()
            val currentStart = incomingFirstBeatMs + (phraseCount * phraseMs).toLong()
            val nextStart    = currentStart + phraseMs.toLong()
            if (abs(seekMs - currentStart) <= abs(nextStart - seekMs)) currentStart else nextStart
        }.minByOrNull { abs(it - seekMs) } ?: seekMs

        val delta = abs(bestMs - seekMs)
        return if (delta <= PHRASE_SNAP_MAX_DELTA_MS) {
            if (delta > 50L) {
                Log.i(TAG, "[PHASE SYNC] Phrase snap: ${seekMs}ms → ${bestMs}ms " +
                        "(Δ=${delta}ms, moved ${if (bestMs > seekMs) "forward" else "back"})")
            }
            bestMs.coerceAtLeast(incomingFirstBeatMs)
        } else {
            Log.d(TAG, "[PHASE SYNC] Phrase snap skipped — nearest boundary Δ=${delta}ms > cap ${PHRASE_SNAP_MAX_DELTA_MS}ms")
            seekMs
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POSITION MONITORING & TRIGGER LOGIC
    // ═══════════════════════════════════════════════════════════════════════

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

                if (currentSmartTriggerMs == -1L &&
                    duration > 120_000L && isRealMixMode &&
                    position >= (duration * MIN_HALF_TIME_FOR_SMART_SEARCH).toLong()) {

                    currentSmartTriggerMs = calculateSmartTriggerThresholdMs(
                        durationMs    = duration,
                        envelope      = currentWaveformEnvelope,
                        isRealMixMode = true
                    )
                    Log.i(TAG, "[TRIGGER] Smart trigger set: ${currentSmartTriggerMs}ms remaining " +
                            "(position=${position}ms, duration=${duration}ms)")
                }

                val baseThreshold = if (isRealMixMode) {
                    currentSmartTriggerMs.takeIf { it > 0 } ?: REAL_MIX_TRIGGER_MS
                } else {
                    crossfadeDurationMs + TRIGGER_SETUP_BUFFER_MS
                }

                val prebufferThresholdMs = if (isRealMixMode)
                    REAL_MIX_PREBUFFER_MS else crossfadeDurationMs * PREBUFFER_MULTIPLIER
                val postGuardActive = System.currentTimeMillis() < postCrossfadeGuardUntilMs

                if (duration > 0L && remaining in baseThreshold..prebufferThresholdMs &&
                    !_state.value.isCrossfading && prebufferedTrackId == null &&
                    !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                val features           = _state.value.audioFeatures
                val nearPhraseBoundary = isNearPhraseBoundary(
                    posMs       = position,
                    firstBeatMs = currentTrackFirstBeatMs,
                    bpm         = currentTrackBpm
                )
                val isMusicallyGood = features.isDropping || features.isLowEnergy || nearPhraseBoundary

                val effectiveThreshold = if (isMusicallyGood && isRealMixMode) {
                    (baseThreshold + MUSICAL_PULLFORWARD_MS)
                        .coerceAtMost(REAL_MIX_TRIGGER_MS + MUSICAL_PULLFORWARD_CAP_MS)
                } else {
                    baseThreshold
                }

                val inTriggerZone = remaining in CROSSFADE_GUARD_MS..effectiveThreshold

                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive &&
                        position >= MIN_PLAY_TIME_MS && inTriggerZone &&
                        remaining <= (if (isMusicallyGood) effectiveThreshold else baseThreshold)

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] SMART auto-mix fired → remaining=${remaining}ms " +
                                "base=${baseThreshold}ms effective=${effectiveThreshold}ms " +
                                "isGood=$isMusicallyGood nearPhrase=$nearPhraseBoundary " +
                                "rms=${"%.3f".format(features.rms)} " +
                                "trend=${"%.3f".format(features.trend)}")
                    }
                }

                val timeToNextMixMs: Long? = when {
                    _state.value.isCrossfading || !playing || duration <= 0L -> null
                    inTriggerZone -> 0L
                    else          -> null
                }

                _state.update {
                    it.copy(
                        currentPositionMs = position,
                        currentDurationMs = duration,
                        timeToNextMixMs   = timeToNextMixMs
                    )
                }

                delay(
                    if (inTriggerZone || remaining <= prebufferThresholdMs) FAST_POLL_MS
                    else POSITION_POLL_MS
                )
            }
        }
    }

    private fun isNearPhraseBoundary(posMs: Long, firstBeatMs: Long, bpm: Float): Boolean {
        if (bpm <= 0f || posMs < firstBeatMs) return false
        val beatInterval = 60_000.0 / bpm
        val window       = beatInterval * PHRASE_BOUNDARY_WINDOW_BEATS

        for (phraseBars in listOf(8, 16)) {
            val phraseInterval = beatInterval * phraseBars * 4
            val phase          = (posMs - firstBeatMs).toDouble().mod(phraseInterval)
            if (phase < window || phase > (phraseInterval - window)) return true
        }
        return false
    }

    private fun calculateSmartTriggerThresholdMs(
        durationMs: Long,
        envelope: FloatArray,
        isRealMixMode: Boolean
    ): Long {
        if (!isRealMixMode || envelope.isEmpty() || durationMs < 120_000L) {
            return REAL_MIX_TRIGGER_MS
        }

        val searchStartRemainingMs = 85_000L
        val searchEndRemainingMs   = 55_000L

        val bucketMs = durationMs.toDouble() / envelope.size
        val startIdx = ((durationMs - searchStartRemainingMs) / bucketMs).toInt()
            .coerceIn(0, envelope.size - 1)
        val endIdx   = ((durationMs - searchEndRemainingMs) / bucketMs).toInt()
            .coerceIn(0, envelope.size - 1)

        if (startIdx >= endIdx) return REAL_MIX_TRIGGER_MS

        var localSum = 0f
        for (i in startIdx..endIdx) localSum += envelope[i]
        val localAvg = localSum / (endIdx - startIdx + 1)

        var bestTriggerIdx = -1
        var maxDrop        = 0f

        for (i in startIdx until endIdx) {
            val drop = envelope[i] - envelope[i + 1]
            if (drop > maxDrop &&
                envelope[i]     > localAvg * 0.80f &&
                envelope[i + 1] < localAvg * 1.12f) {
                maxDrop        = drop
                bestTriggerIdx = i + 1
            }
        }

        return if (bestTriggerIdx != -1 && maxDrop > 0.22f) {
            (durationMs - (bestTriggerIdx * bucketMs).toLong()).coerceAtLeast(55_000L)
        } else {
            REAL_MIX_TRIGGER_MS
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WAVEFORM LOOP
    // ═══════════════════════════════════════════════════════════════════════

    private fun startWaveformLoop() {
        waveformJob?.cancel()
        waveformJob = engineScope.launch {
            while (isActive) {
                val processor = primaryWaveformProcessor()
                val features  = processor?.computeCurrentFeatures()
                    ?: WaveformCaptureAudioProcessor.AudioFeatures(0.5f, 0f, false, false)
                val bands     = processor?.computeCurrentBands() ?: FloatArray(32)
                _state.update {
                    it.copy(
                        waveform      = generateBeatWaveformFromBands(bands),
                        audioFeatures = features
                    )
                }
                delay(WAVEFORM_POLL_MS)
            }
        }
    }

    private fun generateBeatWaveformFromBands(realBands: FloatArray): List<Float> {
        return if (realBands.any { it > 0.01f }) {
            List(WAVEFORM_BARS) { i ->
                val srcIdx  = (i.toFloat() / WAVEFORM_BARS * realBands.size).toInt()
                    .coerceIn(0, realBands.size - 1)
                val boosted = (realBands[srcIdx] * (1f + (i.toFloat() / WAVEFORM_BARS) * 2.5f))
                    .coerceIn(0f, 1f)
                waveformSmoothed[i] = waveformSmoothed[i] * 0.15f + boosted * 0.85f
                waveformSmoothed[i]
            }
        } else {
            List(WAVEFORM_BARS) { i ->
                val target = 0.10f + (sin(i * 1.618 + 0.5) * 0.04f).toFloat()
                waveformSmoothed[i] = waveformSmoothed[i] * 0.80f + target * 0.20f
                waveformSmoothed[i]
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PLAYER LISTENERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun createPlayerListener(isPlayerA: Boolean) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            if (isPrimary) _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val which = if (isPlayerA) "A" else "B"
                val role  = if ((isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)) "PRIMARY" else "secondary"
                Log.i(TAG, "[PLAYBACK] Player $which ($role) ended")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            val which     = if (isPlayerA) "A" else "B"
            val role      = if (isPrimary) "PRIMARY" else "secondary"
            Log.e(TAG, "[PLAYBACK] Player $which ($role) error: ${error.message} (code=${error.errorCode})", error)
            if (isPrimary) _state.update { it.copy(error = "Player error: ${error.message}") }
        }
    }
}