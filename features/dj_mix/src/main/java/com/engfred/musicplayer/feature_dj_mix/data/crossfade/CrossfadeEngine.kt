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

/**
 * Handles dual-player audio playback, phase-aligned seeking, and DJ-style crossfading.
 *
 * IMPORTANT HISTORICAL WARNING FOR FUTURE DEVELOPERS:
 * Do NOT attempt to implement "gain matching" or normalize track volumes to a common
 * reference amplitude before or during the crossfade. Previous iterations attempted to
 * use "equal-power math" by forcing both tracks to a matched baseline volume.
 * This resulted in an unnatural, noticeable volume sag and a poor listener experience.
 * Let the tracks crossfade naturally from their unadjusted 1.0f volume peaks.
 *
 * DYNAMIC EQ SWEEP NOTE:
 * The mid-range sweep in the execution loop (step 6) is calculated relative to
 * `originalOutGains`, NOT `outBassKillGains`. This is intentional and critical.
 * If you base the sweep on `outBassKillGains`, the mid reduction only becomes
 * perceptible in the second half of the fade when the user has a mid-boost EQ
 * preset active (e.g. originalOutGains[3] = +6dB means outBassKillGains[3] = +6dB,
 * and midReduction only crosses zero at theta=0.5). Always sweep from the
 * original user EQ state downward to -12dB for a consistent, audible effect.
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        private const val POSITION_POLL_MS       = 300L
        private const val FAST_POLL_MS           = 50L
        private const val WAVEFORM_POLL_MS       = 16L
        private const val FOCUS_RESUME_POLL_MS   = 50L

        private const val FADE_STEPS             = 80
        private const val TEMPO_SYNC_MAX_RATIO   = 1.08f
        private const val TEMPO_SYNC_MIN_RATIO   = 0.92f

        private const val WAVEFORM_BARS          = 32

        private const val REAL_MIX_TRIGGER_MS    = 60_000L
        private const val REAL_MIX_PREBUFFER_MS  = 90_000L
        private const val TRIGGER_SETUP_BUFFER_MS = 2_000L
        private const val PREBUFFER_MULTIPLIER   = 4L
        private const val MIN_PLAY_TIME_MS       = 10_000L
        private const val POST_CROSSFADE_GUARD_MS = 30_000L
        private const val CROSSFADE_GUARD_MS     = 200L

        /**
         * When a phrase boundary or energy drop is detected, the trigger fires
         * up to MUSICAL_PULLFORWARD_MS earlier than the base threshold.
         */
        private const val MUSICAL_PULLFORWARD_MS = 24_000L
        private const val MUSICAL_PULLFORWARD_CAP_MS = 32_000L

        /**
         * Snap incoming seekTo position to the nearest 8- or 16-bar phrase boundary.
         * Only applies if the snap moves the seek by less than the max delta.
         */
        private const val PHRASE_SNAP_MAX_DELTA_MS = 5_000L

        /**
         * The window (in beats) on either side of a phrase boundary that counts as a match.
         */
        private const val PHRASE_BOUNDARY_WINDOW_BEATS = 2.0

        /**
         * Step at which incoming bass is restored (0=start, FADE_STEPS=end).
         * Restoring at 50% means bass clash only overlaps for the first half.
         */
        private const val BASS_RESTORE_STEP      = FADE_STEPS / 2

        private const val MIN_HALF_TIME_FOR_SMART_SEARCH = 0.5f
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    @Volatile private var tempoRestoreJob: Job? = null

    @Volatile private var currentTrackBpm: Float = 0f
    @Volatile private var nextTrackBpm: Float = 0f
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
            currentTrackAmplitude      = 0f
            currentTrackFirstBeatMs    = 0L
            currentWaveformEnvelope    = FloatArray(0)
            postCrossfadeGuardUntilMs  = 0L
            currentSmartTriggerMs      = -1L
            tempoRestoreJob            = null
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
        tempoRestoreJob?.cancel()
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

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return
        Log.i(TAG, "[PLAYBACK] Starting: id=${audioFile.id} '${audioFile.title}'")

        crossfadeJob?.cancel(); crossfadeJob = null
        prebufferJob?.cancel()
        tempoRestoreJob?.cancel(); tempoRestoreJob = null
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
        nextTrackBpm               = 0f
        resumeAfterFocusGain       = false

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

        engineScope.launch {
            withContext(Dispatchers.Main) {
                primaryPlayer()?.volume = 1.0f
            }
        }
        Log.d(TAG, "[METADATA] BPM=$bpm amplitude=${"%.4f".format(amplitude)} volume=1.0f")
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
        Log.w(TAG, "[MIXER] Crossfade aborted by caller")
    }

    fun prebufferTrack(audioFile: AudioFile, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return
        nextTrackBpm = bpm // store so position monitor can decide trigger threshold

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

    /**
     * Executes the complete crossfade sequence:
     * ① Secondary prepare / prebuffer reuse
     * ② Wait for STATE_READY
     * ③ Phase alignment (initial + corrective seek)
     * ④ Tempo sync
     * ④b Bass kill (outgoing + incoming sub-bass to -12 dB)
     * ⑤ Execution loop — equal-power volume fade + dynamic mid EQ sweep
     * ⑥ Bass restore on incoming at 50% progress
     * ↔ Player swap
     */
    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        nextBpm: Float = 0f,
        nextFirstBeatMs: Long = 0L,
        nextAmplitude: Float = 0f
    ) {
        val primaryRef   = primaryPlayer()   ?: return
        val secondaryRef = secondaryPlayer() ?: return

        abortCrossfade = false
        tempoRestoreJob?.cancel()

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
        Log.i(TAG, "║ DJ CROSSFADE START                                       ║")
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
            withContext(Dispatchers.Main) {
                primaryRef.volume = 1.0f
            }

            /**
             * ① Secondary Prepare / Reuse
             */
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

            /**
             * ② Wait for STATE_READY
             */
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

            /**
             * ③ Phase alignment with latency compensation
             *
             * Stage A: Initial seek based on best estimate of current position.
             * Stage B: Corrective seek to fix discrepancies after playback starts.
             */
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

            var playWaitMs       = 0L
            var secondaryPlaying = false
            while (!secondaryPlaying && playWaitMs < 1_500L) {
                delay(50L)
                playWaitMs      += 50L
                secondaryPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }
            Log.i(TAG, "[CROSSFADE] ③ Secondary playing=${secondaryPlaying} after ${playWaitMs}ms")

            // Corrective seek — re-compute phase after the setup delay has elapsed
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
                        "(Δ=${correctionDeltaMs}ms, outgoing advanced " +
                        "${correctedOutgoingPositionMs - initialOutgoingPositionMs}ms during setup)")
            } else {
                Log.d(TAG, "[PHASE SYNC] Corrective seek skipped — Δ=${correctionDeltaMs}ms within tolerance")
            }

            /**
             * ④ Tempo Sync
             */
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

            /**
             * ④b Bass Kill
             * Both outgoing and incoming sub-bass bands are cut to -12 dB immediately.
             * This prevents kick-drum clashes during the entire overlap window.
             * The incoming bass is restored at step BASS_RESTORE_STEP (50%) in the loop below.
             */
            val outBassKillGains = originalOutGains.clone()
            val inBassKillGains  = originalInGains.clone()
            if (outBassKillGains.size >= 3) {
                outBassKillGains[0] = -12.0
                outBassKillGains[1] = -12.0
                outBassKillGains[2] = -12.0
            }
            if (inBassKillGains.size >= 3) {
                inBassKillGains[0] = -12.0
                inBassKillGains[1] = -12.0
                inBassKillGains[2] = -12.0
            }
            withContext(Dispatchers.Main) {
                outgoingEq?.setGains(outBassKillGains)
                incomingEq?.setGains(inBassKillGains)
            }
            Log.i(TAG, "[CROSSFADE] ④b Bass Kill: outgoing -12dB sub-bass | incoming -12dB sub-bass")

            /**
             * ⑤ Execution Loop — Equal-power volume fade + Dynamic mid EQ sweep
             *
             * Volume: outgoing cos(θ·π/2) → 0, incoming 0 → sin(θ·π/2)
             *
             * EQ sweep (every 5 steps):
             * The outgoing track's mid bands [3..6] are swept from their original
             * user-preset values down to -12 dB as theta goes 0 → 1.
             *
             * CRITICAL: We always compute relative to `originalOutGains[i]`, not
             * `outBassKillGains[i]`. This guarantees the sweep is perceptible from
             * the very first step regardless of whether the user has a mid-boost
             * EQ preset active. The target at full fade (theta=1.0) is -12 dB for
             * bands 3–5 and -6 dB for band 6 (shakers/hi-hats get a lighter cut).
             *
             * Band index reference (10-band EQ):
             * 0=31Hz  1=62Hz  2=125Hz  3=250Hz  4=500Hz
             * 5=1kHz  6=2kHz  7=4kHz   8=8kHz   9=16kHz
             */

            /**
             * Time-based fade loop.
             * By tracking real elapsed time instead of counting steps, the fade is guaranteed
             * to complete in exactly [effectiveDurationMs] regardless of thread starvation or delays.
             */
            Log.i(TAG, "[CROSSFADE] ⑤ Fade: out 1.000→0 in 0→1.000 ${effectiveDurationMs}ms (time-based)")
            var incomingBassRestored = false
            var logged25 = false; var logged50 = false; var logged75 = false
            val fadeStartMs   = System.currentTimeMillis()
            var totalPausedMs = 0L
            var pauseStartMs  = 0L
            var lastEqUpdateMs = -80L // ensure first frame triggers an EQ update immediately
            while (engineScope.isActive && !abortCrossfade) {
                // Pause-aware: clock stops while paused so fade position is preserved
                if (!_state.value.isPlaying) {
                    if (pauseStartMs == 0L) pauseStartMs = System.currentTimeMillis()
                    delay(FOCUS_RESUME_POLL_MS)
                    continue
                } else if (pauseStartMs != 0L) {
                    totalPausedMs += System.currentTimeMillis() - pauseStartMs
                    pauseStartMs   = 0L
                }
                val elapsed = (System.currentTimeMillis() - fadeStartMs - totalPausedMs).coerceAtLeast(0L)
                val theta   = (elapsed.toFloat() / effectiveDurationMs).coerceIn(0f, 1f)
                val angle   = theta * (PI.toFloat() / 2f)
                val outVol  = cos(angle).coerceIn(0f, 1f)
                val inVol   = sin(angle).coerceIn(0f, 1f)

                // Bass restore at 50% of real elapsed time — one-shot, always checked
                if (!incomingBassRestored && theta >= 0.5f) {
                    incomingBassRestored = true
                    withContext(Dispatchers.Main) { incomingEq?.setGains(originalInGains) }
                    Log.i(TAG, "[CROSSFADE] ⑥ Bass restore: incoming sub-bass restored at 50%")
                }
                // Dynamic mid EQ sweep — throttled to 80ms to prevent BandEqAudioProcessor
                // coefficient thrashing, which causes audible distortion at 16ms update rates.
                // Volume stays at 16ms for perceptually smooth fading. These are decoupled intentionally.
                if (elapsed - lastEqUpdateMs >= 80L) {
                    lastEqUpdateMs = elapsed
                    val sweepReduction  = -12.0 * theta
                    val dynamicOutGains = outBassKillGains.clone()
                    if (dynamicOutGains.size >= 7) {
                        dynamicOutGains[3] = (originalOutGains[3] + sweepReduction).coerceIn(-12.0, 12.0)
                        dynamicOutGains[4] = (originalOutGains[4] + sweepReduction).coerceIn(-12.0, 12.0)
                        dynamicOutGains[5] = (originalOutGains[5] + sweepReduction).coerceIn(-12.0, 12.0)
                        dynamicOutGains[6] = (originalOutGains[6] + sweepReduction * 0.5).coerceIn(-12.0, 12.0)
                    }
                    withContext(Dispatchers.Main) { outgoingEq?.setGains(dynamicOutGains) }
                }
                withContext(Dispatchers.Main) {
                    primaryRef.volume   = outVol
                    secondaryRef.volume = inVol
                }
                _state.update { it.copy(crossfadeProgressFraction = inVol) }
                val pct = (theta * 100).toInt()
                if (!logged25 && pct >= 25) { logged25 = true
                    Log.i(TAG, "[CROSSFADE] 25% │ out=${"%.3f".format(outVol)} in=${"%.3f".format(inVol)} midSweep=${"%.1f".format(-12.0*theta)}dB") }
                if (!logged50 && pct >= 50) { logged50 = true
                    Log.i(TAG, "[CROSSFADE] 50% │ out=${"%.3f".format(outVol)} in=${"%.3f".format(inVol)} midSweep=${"%.1f".format(-12.0*theta)}dB") }
                if (!logged75 && pct >= 75) { logged75 = true
                    Log.i(TAG, "[CROSSFADE] 75% │ out=${"%.3f".format(outVol)} in=${"%.3f".format(inVol)} midSweep=${"%.1f".format(-12.0*theta)}dB") }
                if (theta >= 1f) break
                delay(16L)
            }

            // ── Abort path ────────────────────────────────────────────────────────────
            if (abortCrossfade) {
                Log.w(TAG, "[CROSSFADE] ✗ Aborted — restoring primary volume and EQ")
                withContext(Dispatchers.Main) {
                    primaryRef.volume             = 1.0f
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

            /**
             * ↔ Swap active players once fade is complete.
             */
            Log.i(TAG, "[CROSSFADE] ↔ Swap: '${nextTrack.title}' becomes primary")
            withContext(Dispatchers.Main) {
                if (_state.value.isPlaying) {
                    secondaryRef.volume = 1.0f
                }

                // Restore both EQs to their original user-preset state
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
            nextTrackBpm              = 0f
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + POST_CROSSFADE_GUARD_MS

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = actualPlaying,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }

            // ── Tempo Pitch Glide ──────────────────────────────────────────────────────
            if (tempoSyncApplied) {
                tempoRestoreJob = engineScope.launch {
                    val slideDurationMs = 12_000L // 12 seconds to gently return pitch to zero
                    val slideSteps = 60
                    val delayMs = slideDurationMs / slideSteps

                    Log.i(TAG, "[TEMPO] ↘ Sliding tempo from ${"%.3f".format(tempoSyncRatio)}x back to 1.0x over ${slideDurationMs}ms")

                    for (i in 1..slideSteps) {
                        if (!isActive) break
                        val progress = i.toFloat() / slideSteps
                        val currentRatio = tempoSyncRatio + (1.0f - tempoSyncRatio) * progress
                        withContext(Dispatchers.Main) {
                            primaryPlayer()?.playbackParameters = PlaybackParameters(currentRatio)
                        }
                        delay(delayMs)
                    }
                    withContext(Dispatchers.Main) {
                        primaryPlayer()?.playbackParameters = PlaybackParameters.DEFAULT
                    }
                    Log.i(TAG, "[TEMPO] ✓ Tempo restore complete (1.0x)")
                }
            } else {
                withContext(Dispatchers.Main) {
                    primaryPlayer()?.playbackParameters = PlaybackParameters.DEFAULT
                }
            }
            // ───────────────────────────────────────────────────────────────────────────

            Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
            Log.i(TAG, "║ DJ CROSSFADE COMPLETE ✓                                  ║")
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

    /**
     * Computes the seek position on the incoming track that phase-aligns it
     * with the outgoing track at the moment crossfade begins.
     *
     * We take the outgoing track's current phase within its beat interval
     * (how far past the last beat it is) and seek the incoming track to
     * the same phase offset from its first detected beat.
     *
     * Result: both tracks are on the same beat grid when audio overlaps.
     */
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

    /**
     * Phrase-boundary snap on incoming track.
     *
     * After computing the phase-aligned seek position, find the nearest 8-bar
     * or 16-bar phrase boundary on the incoming track. Snap to it if it is
     * within PHRASE_SNAP_MAX_DELTA_MS.
     */
    private fun snapToPhraseBoundaryMs(
        seekMs: Long,
        incomingFirstBeatMs: Long,
        incomingBpm: Float
    ): Long {
        if (incomingBpm <= 0f || incomingFirstBeatMs <= 0L) return seekMs
        if (seekMs < incomingFirstBeatMs) return seekMs

        val beatIntervalMs = 60_000.0 / incomingBpm
        val barIntervalMs  = beatIntervalMs * 4.0  // assumes 4/4 time

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

                // If the BPM gap between current and next track is too large for tempo sync,
                // treat it as near-end mode regardless of isRealMixMode. A hard-cut mix fired
                // 60s early with no tempo alignment sounds terrible. Play to near-end instead.
                val isBpmGapTooLarge = run {
                    val cur  = currentTrackBpm
                    val next = nextTrackBpm
                    if (cur <= 0f || next <= 0f) false
                    else {
                        val bestHarmonic = DjConstants.HARMONIC_RATIOS.minByOrNull { ratio ->
                            abs((cur * ratio) - next)
                        } ?: 1.0f
                        val ratio = (cur * bestHarmonic) / next
                        ratio < TEMPO_SYNC_MIN_RATIO || ratio > TEMPO_SYNC_MAX_RATIO
                    }
                }
                val baseThreshold = if (isRealMixMode && !isBpmGapTooLarge) {
                    currentSmartTriggerMs.takeIf { it > 0 } ?: REAL_MIX_TRIGGER_MS
                } else {
                    if (isBpmGapTooLarge) Log.d(TAG, "[TRIGGER] BPM gap too large — forcing near-end trigger")
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

                /** * Base threshold trigger evaluation.
                 * Real-time checks have been removed to ensure the trigger relies solely on
                 * pre-calculated, musically-safe smart waveform analysis (baseThreshold).
                 */
                val inTriggerZone = remaining in CROSSFADE_GUARD_MS..baseThreshold
                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive &&
                        position >= MIN_PLAY_TIME_MS && inTriggerZone
                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] SMART auto-mix fired → remaining=${remaining}ms base=${baseThreshold}ms")
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

    /**
     * Phrase-boundary detection for trigger logic.
     * Checks both 8-bar and 16-bar phrase lengths.
     */
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

    /**
     * Analyses the track's waveform envelope to find the optimal crossfade trigger point.
     */
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