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

@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "CrossfadeEngine"
        private const val POSITION_POLL_MS = 300L
        private const val FAST_POLL_MS = 50L
        private const val WAVEFORM_POLL_MS = 16L
        private const val CROSSFADE_GUARD_MS = 200L
        private const val FOCUS_RESUME_POLL_MS = 50L

        private const val FADE_STEPS = 80
        private const val TEMPO_SYNC_MAX_RATIO = 1.08f
        private const val TEMPO_SYNC_MIN_RATIO = 0.92f

        private const val WAVEFORM_BARS = 32

        private const val REAL_MIX_TRIGGER_MS = 60_000L
        private const val REAL_MIX_PREBUFFER_MS = 90_000L
        private const val TRIGGER_SETUP_BUFFER_MS = 2_000L
        private const val PREBUFFER_MULTIPLIER = 4L
        private const val MIN_PLAY_TIME_MS = 10_000L
        private const val POST_CROSSFADE_GUARD_MS = 30_000L
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audioFocusCoordinator: AudioFocusCoordinator by lazy {
        AudioFocusCoordinator(
            context = context,
            listener = object : AudioFocusCoordinator.Listener {
                override fun onFocusLost() = handleFocusLost()
                override fun onFocusLostTransient() = handleFocusLost()
                override fun onFocusGained() = handleFocusGain()
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

    private fun primaryPlayer() = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB else playerA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB

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

    private fun handleFocusLost() {
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
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
        if (!resumeAfterFocusGain) { Log.d(TAG, "[FOCUS] Gained but user-paused"); return }
        resumeAfterFocusGain = false
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
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
            engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isReleased = false
            isPrimaryA = true
            abortCrossfade = false
            resumeAfterFocusGain = false
            lastRequestedTrackId = null
            lastPrebufferRequestedId = null
            pendingNextTrack = null
            prebufferedTrackId = null
            isPrebufferingInProgress = false
            playerA = null; playerB = null
            waveformProcessorA = null; waveformProcessorB = null
            eqProcessorA = null; eqProcessorB = null
            _state.value = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm = 0f
            currentTrackBaseVolume = 1.0f
            currentTrackAmplitude = 0f
            currentTrackFirstBeatMs = 0L
            currentWaveformEnvelope = FloatArray(0)
            postCrossfadeGuardUntilMs = 0L
            currentSmartTriggerMs = -1L
        }

        waveformProcessorA = WaveformCaptureAudioProcessor()
        waveformProcessorB = WaveformCaptureAudioProcessor()
        eqProcessorA = BandEqAudioProcessor()
        eqProcessorB = BandEqAudioProcessor()

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
        val eqProcessor = if (isPlayerA) eqProcessorA else eqProcessorB
        val waveformProcessor = if (isPlayerA) waveformProcessorA else waveformProcessorB

        val processors: Array<AudioProcessor> = listOfNotNull(eqProcessor, waveformProcessor).toTypedArray()

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
                eqProcessorA = null
                eqProcessorB = null
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
        prebufferedTrackId = null
        lastPrebufferRequestedId = null
        isPrebufferingInProgress = false
        isPrimaryA = true
        _nextTrackRequest.resetReplayCache()
        postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
        lastRequestedTrackId = null
        waveformSmoothed.fill(0f)
        currentWaveformEnvelope = FloatArray(0)
        currentSmartTriggerMs = -1L
        resumeAfterFocusGain = false
        currentTrackBaseVolume = 1.0f

        engineScope.launch {
            withContext(Dispatchers.Main) { audioFocusCoordinator.request() }

            withContext(Dispatchers.Main) {
                playerB?.pause(); playerB?.clearMediaItems()
                playerB?.volume = 0f
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
                val primary = primaryPlayer() ?: return@withContext
                val secondary = secondaryPlayer()
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
        currentTrackBpm = bpm
        currentTrackFirstBeatMs = firstBeatMs
        currentTrackAmplitude = amplitude
        currentWaveformEnvelope = waveformEnvelope

        val normalisedVolume = if (amplitude > 0f)
            (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
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

    fun prebufferTrack(audioFile: AudioFile, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return

        isPrebufferingInProgress = true
        prebufferJob?.cancel()

        prebufferJob = engineScope.launch {
            withContext(Dispatchers.Main) {
                val secondary = secondaryPlayer() ?: run {
                    isPrebufferingInProgress = false; return@withContext
                }
                secondary.stop()
                secondary.clearMediaItems()
                secondary.playbackParameters = PlaybackParameters.DEFAULT
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f
                secondary.prepare()
                Log.d(TAG, "[PREBUFFER] id=${audioFile.id} prepared from position 0")
            }
            prebufferedTrackId = audioFile.id
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

    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        nextBpm: Float = 0f,
        nextFirstBeatMs: Long = 0L,
        nextAmplitude: Float = 0f
    ) {
        val primaryRef = primaryPlayer()
        val secondaryRef = secondaryPlayer()
        if (primaryRef == null || secondaryRef == null) return

        abortCrossfade = false

        val (tempoRatio, targetIncomingBpm) = if (currentTrackBpm > 0f && nextBpm > 0f) {
            val bestHarmonic = DjConstants.HARMONIC_RATIOS.minByOrNull { ratio ->
                abs((currentTrackBpm * ratio) - nextBpm)
            } ?: 1.0f
            val targetBpm = currentTrackBpm * bestHarmonic
            val speedRatio = targetBpm / nextBpm
            Pair(speedRatio, targetBpm)
        } else {
            Pair(1.0f, nextBpm)
        }

        val isLargeBpmGap = tempoRatio < TEMPO_SYNC_MIN_RATIO || tempoRatio > TEMPO_SYNC_MAX_RATIO
        val effectiveDurationMs = crossfadeDurationMs.coerceIn(2_000L, 16_000L)

        Log.i(TAG, "")
        Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║ DJ CROSSFADE START ║")
        Log.i(TAG, "╠══════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║ Outgoing : '${_state.value.currentTrack?.title}'")
        Log.i(TAG, "║ Incoming : '${nextTrack.title}' (starts from 0)")
        Log.i(TAG, "║ Mode : ${if (isRealMixMode) "REAL MIX" else "NEAR-END"}")
        Log.i(TAG, "║ Duration : ${effectiveDurationMs}ms Steps: $FADE_STEPS")
        Log.i(TAG, "║ Out BPM : $currentTrackBpm In BPM: $nextBpm")
        Log.i(TAG, "║ BPM ratio: ${"%.3f".format(tempoRatio)}" +
                if (isLargeBpmGap) " ⚠ LARGE GAP — tempo sync skipped"
                else " (within ±8% harmonic window)")
        Log.i(TAG, "╚══════════════════════════════════════════════════════════╝")

        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        val outgoingEq = if (isPrimaryA) eqProcessorA else eqProcessorB
        val originalGains = outgoingEq?.getGains() ?: DoubleArray(10) { 0.0 }

        try {
            val secondaryBaseVolume = (if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f).coerceIn(0f, 1f)
            val primaryBaseVolume = currentTrackBaseVolume.coerceIn(0f, 1f)

            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondaryRef.stop()
                    secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f
                    secondaryRef.prepare()
                    Log.d(TAG, "[CROSSFADE] ① Secondary: fresh prepare from position 0")
                } else {
                    prebufferedTrackId = null
                    Log.d(TAG, "[CROSSFADE] ① Secondary: reusing prebuffered track at position 0")
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
            }

            var waitMs = 0L
            var ready = false
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

            val outgoingPositionMs = withContext(Dispatchers.Main) { primaryRef.currentPosition }
            val phaseAlignedSeekMs = calculatePhaseAlignedSeekMs(
                outgoingPositionMs = outgoingPositionMs,
                outgoingFirstBeatMs = currentTrackFirstBeatMs,
                outgoingBpm = currentTrackBpm,
                incomingFirstBeatMs = nextFirstBeatMs
            )

            withContext(Dispatchers.Main) {
                secondaryRef.seekTo(phaseAlignedSeekMs)
                secondaryRef.volume = 0f
                try { secondaryRef.play() }
                catch (e: Exception) { Log.e(TAG, "[CROSSFADE] Secondary play() failed", e) }
            }

            var playWaitMs = 0L
            var secondaryPlaying = false
            while (!secondaryPlaying && playWaitMs < 1_500L) {
                delay(50L)
                playWaitMs += 50L
                secondaryPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }
            Log.i(TAG, "[CROSSFADE] ③ Secondary playing=${secondaryPlaying} after ${playWaitMs}ms")

            val tempoSyncRatio = if (!isLargeBpmGap && currentTrackBpm > 0f && nextBpm > 0f) {
                tempoRatio.coerceIn(TEMPO_SYNC_MIN_RATIO, TEMPO_SYNC_MAX_RATIO)
            } else {
                1.0f
            }
            val tempoSyncApplied = abs(tempoSyncRatio - 1.0f) > 0.001f
            if (tempoSyncApplied) {
                withContext(Dispatchers.Main) {
                    secondaryRef.playbackParameters = PlaybackParameters(tempoSyncRatio)
                }
                Log.i(TAG, "[CROSSFADE] ④ Tempo sync: ${nextBpm}bpm → " +
                        "${"%.1f".format(targetIncomingBpm)}bpm speed=${"%.4f".format(tempoSyncRatio)}")
            } else {
                Log.d(TAG, "[CROSSFADE] ④ Tempo sync skipped (largeBpmGap=$isLargeBpmGap ratio=${"%.4f".format(tempoSyncRatio)})")
            }

            val bassKillGains = originalGains.clone()
            if (bassKillGains.size >= 3) {
                bassKillGains[0] = -12.0
                bassKillGains[1] = -12.0
                bassKillGains[2] = -12.0
            }
            withContext(Dispatchers.Main) { outgoingEq?.setGains(bassKillGains) }
            Log.i(TAG, "[CROSSFADE] ④b Bass Kill: outgoing sub-bass dropped to -12 dB")

            val primaryStartVolume = withContext(Dispatchers.Main) { primaryRef.volume }.coerceIn(0f, 1f)
            val stepDelayMs = (effectiveDurationMs / FADE_STEPS).coerceAtLeast(16L)

            Log.i(TAG, "[CROSSFADE] ⑤ Fade: out ${"%.3f".format(primaryStartVolume)}→0 " +
                    "in 0→${"%.3f".format(secondaryBaseVolume)} ${FADE_STEPS} steps × ${stepDelayMs}ms")

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break

                while (!_state.value.isPlaying && engineScope.isActive && !abortCrossfade) {
                    delay(FOCUS_RESUME_POLL_MS)
                }
                if (abortCrossfade) break

                val theta = step.toFloat() / FADE_STEPS
                val angle = theta * (PI.toFloat() / 2f)
                val outVol = (cos(angle) * primaryStartVolume).coerceIn(0f, 1f)
                val inVol = (sin(angle) * secondaryBaseVolume).coerceIn(0f, 1f)

                withContext(Dispatchers.Main) {
                    primaryRef.volume = outVol
                    secondaryRef.volume = inVol
                }
                _state.update { it.copy(crossfadeProgressFraction = sin(angle)) }

                if (step == FADE_STEPS / 4 || step == FADE_STEPS / 2 || step == (FADE_STEPS * 3) / 4) {
                    val outPower = outVol * outVol
                    val inPower = inVol * inVol
                    val maxPower = (primaryStartVolume * primaryStartVolume) + (secondaryBaseVolume * secondaryBaseVolume)
                    val normPower = if (maxPower > 0f) (outPower + inPower) / maxPower else 0f
                    Log.i(TAG, "[CROSSFADE] ${(theta * 100).toInt()}% │ out=${"%.3f".format(outVol)} in=${"%.3f".format(inVol)} │ power=${"%.3f".format(normPower)} (1.000=perfect equal-power)")
                }

                delay(stepDelayMs)
            }

            if (abortCrossfade) {
                Log.w(TAG, "[CROSSFADE] ✗ Aborted — restoring primary volume")
                withContext(Dispatchers.Main) {
                    primaryRef.volume = primaryStartVolume
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    outgoingEq?.setGains(originalGains)
                    try {
                        secondaryRef.pause()
                        secondaryRef.volume = 0f
                        secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    } catch (_: Exception) {}
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            Log.i(TAG, "[CROSSFADE] ↔ Swap: '${nextTrack.title}' becomes primary")
            withContext(Dispatchers.Main) {
                if (_state.value.isPlaying) {
                    secondaryRef.volume = secondaryBaseVolume.coerceIn(0f, 1f)
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
                outgoingEq?.setGains(originalGains)

                isPrimaryA = !isPrimaryA

                try {
                    primaryRef.pause()
                    primaryRef.volume = 0f
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                } catch (_: Exception) {}

                if (_state.value.isPlaying) primaryPlayer()?.play()
            }

            val actualPlaying = withContext(Dispatchers.Main) { primaryPlayer()?.isPlaying ?: false }

            lastRequestedTrackId = null
            prebufferedTrackId = null
            lastPrebufferRequestedId = null
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + POST_CROSSFADE_GUARD_MS

            _state.update {
                it.copy(
                    currentTrack = nextTrack,
                    isPlaying = actualPlaying,
                    isCrossfading = false,
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
        val beatIntervalMs = 60_000.0 / outgoingBpm
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

    private fun isNearPhraseBoundary(posMs: Long, firstBeatMs: Long, bpm: Float): Boolean {
        if (bpm <= 0f || posMs < firstBeatMs) return false
        val beatInterval = 60000.0 / bpm
        val phraseInterval = beatInterval * 16
        val phase = (posMs - firstBeatMs).toDouble().mod(phraseInterval)
        val window = beatInterval * 1.5
        return phase < window || phase > (phraseInterval - window)
    }

    private fun calculateSmartTriggerThresholdMs(
        durationMs: Long,
        envelope: FloatArray,
        isRealMixMode: Boolean
    ): Long {
        if (!isRealMixMode || envelope.isEmpty() || durationMs < 120_000L) {
            return REAL_MIX_TRIGGER_MS
        }

        // Search earlier in the outro: 105s → 45s remaining
        val searchStartRemainingMs = 105_000L
        val searchEndRemainingMs = 45_000L

        val bucketMs = durationMs.toDouble() / envelope.size
        val startIdx = ((durationMs - searchStartRemainingMs) / bucketMs).toInt().coerceIn(0, envelope.size - 1)
        val endIdx = ((durationMs - searchEndRemainingMs) / bucketMs).toInt().coerceIn(0, envelope.size - 1)

        if (startIdx >= endIdx) return REAL_MIX_TRIGGER_MS

        var localSum = 0f
        for (i in startIdx..endIdx) localSum += envelope[i]
        val localAvg = localSum / (endIdx - startIdx + 1)

        var bestTriggerIdx = -1
        var maxDrop = 0f

        for (i in startIdx until endIdx) {
            val currentVal = envelope[i]
            val nextVal = envelope[i + 1]
            val drop = currentVal - nextVal
            if (drop > maxDrop && currentVal > localAvg * 0.75f && nextVal < localAvg * 1.2f) {
                maxDrop = drop
                bestTriggerIdx = i + 1
            }
        }

        return if (bestTriggerIdx != -1 && maxDrop > 0.18f) {
            (durationMs - (bestTriggerIdx * bucketMs).toLong()).coerceAtLeast(45_000L)
        } else {
            REAL_MIX_TRIGGER_MS
        }
    }

    private fun startPositionMonitoring() {
        positionMonitorJob?.cancel()
        positionMonitorJob = engineScope.launch {
            while (isActive) {
                val (position, duration, playing) = withContext(Dispatchers.Main) {
                    val p = primaryPlayer()
                    val dur = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
                    Triple(p?.currentPosition ?: 0L, dur, p?.isPlaying ?: false)
                }

                val remaining = duration - position

                // Calculate smart trigger once per track
                if (currentSmartTriggerMs == -1L && duration > 120_000L && isRealMixMode) {
                    currentSmartTriggerMs = calculateSmartTriggerThresholdMs(
                        durationMs = duration,
                        envelope = currentWaveformEnvelope,
                        isRealMixMode = true
                    )
                    Log.i(TAG, "[TRIGGER] Smart trigger calculated for this track: ${currentSmartTriggerMs}ms remaining")
                }

                val baseThreshold = if (isRealMixMode) {
                    currentSmartTriggerMs.takeIf { it > 0 } ?: REAL_MIX_TRIGGER_MS
                } else {
                    crossfadeDurationMs + TRIGGER_SETUP_BUFFER_MS
                }

                val prebufferThresholdMs = if (isRealMixMode) REAL_MIX_PREBUFFER_MS else crossfadeDurationMs * PREBUFFER_MULTIPLIER
                val postGuardActive = System.currentTimeMillis() < postCrossfadeGuardUntilMs

                // Prebuffer request
                if (duration > 0L && remaining in baseThreshold..prebufferThresholdMs &&
                    !_state.value.isCrossfading && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                // SMART Trigger
                val features = _state.value.audioFeatures
                val isMusicallyGood = features.isDropping ||
                        features.isLowEnergy ||
                        isNearPhraseBoundary(position, currentTrackFirstBeatMs, currentTrackBpm)

                val effectiveThreshold = if (isMusicallyGood && isRealMixMode) {
                    (baseThreshold + 30_000L).coerceAtMost(REAL_MIX_TRIGGER_MS + 40_000L)
                } else {
                    baseThreshold
                }

                val inTriggerZone = remaining in CROSSFADE_GUARD_MS..effectiveThreshold

                val shouldTrigger = playing &&
                        !_state.value.isCrossfading &&
                        !postGuardActive &&
                        position >= MIN_PLAY_TIME_MS &&
                        inTriggerZone &&
                        remaining <= (if (isMusicallyGood) effectiveThreshold else baseThreshold)

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] SMART auto-mix fired → remaining=${remaining}ms " +
                                "base=${baseThreshold}ms effective=${effectiveThreshold}ms " +
                                "isGood=$isMusicallyGood rms=${"%.3f".format(features.rms)} " +
                                "trend=${"%.3f".format(features.trend)}")
                    }
                }

                val timeToNextMixMs: Long? = when {
                    _state.value.isCrossfading || !playing || duration <= 0L -> null
                    inTriggerZone -> 0L
                    else -> null
                }

                _state.update {
                    it.copy(
                        currentPositionMs = position,
                        currentDurationMs = duration,
                        timeToNextMixMs = timeToNextMixMs
                    )
                }

                delay(if (inTriggerZone || remaining <= prebufferThresholdMs) FAST_POLL_MS else POSITION_POLL_MS)
            }
        }
    }

    private fun startWaveformLoop() {
        waveformJob?.cancel()
        waveformJob = engineScope.launch {
            while (isActive) {
                val processor = primaryWaveformProcessor()
                val features = processor?.computeCurrentFeatures()
                    ?: WaveformCaptureAudioProcessor.AudioFeatures(0.5f, 0f, false, false)
                val bands = processor?.computeCurrentBands() ?: FloatArray(32)

                _state.update {
                    it.copy(
                        waveform = generateBeatWaveformFromBands(bands),
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
                val srcIdx = (i.toFloat() / WAVEFORM_BARS * realBands.size).toInt().coerceIn(0, realBands.size - 1)
                val boosted = (realBands[srcIdx] * (1f + (i.toFloat() / WAVEFORM_BARS) * 2.5f)).coerceIn(0f, 1f)
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
                val role = if ((isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)) "PRIMARY" else "secondary"
                Log.i(TAG, "[PLAYBACK] Player $which ($role) ended")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            val which = if (isPlayerA) "A" else "B"
            val role = if (isPrimary) "PRIMARY" else "secondary"
            Log.e(TAG, "[PLAYBACK] Player $which ($role) error: ${error.message} (code=${error.errorCode})", error)
            if (isPrimary) {
                _state.update { it.copy(error = "Player error: ${error.message}") }
            }
        }
    }
}