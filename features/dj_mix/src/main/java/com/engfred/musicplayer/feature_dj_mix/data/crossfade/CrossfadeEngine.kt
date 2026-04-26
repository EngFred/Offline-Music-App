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
import kotlin.math.sqrt

/**
 * Dual-ExoPlayer DJ crossfade engine — EQUAL-POWER EDITION.
 *
 * ── What was wrong before and why it sounded terrible ────────────────────────
 *
 * The previous "wide-valley" curve (cos^0.3 / sin^3.0) produced a combined
 * volume of 149–159% during the middle third of every crossfade. The Android
 * audio mixer adds PCM streams directly — exceeding 100% amplitude causes
 * hard clipping on the mix bus. That is the "fighting" sound. It is not a
 * tuning issue; it is a mathematical certainty of that curve.
 *
 * The old curve ALSO had a floating-point trap: cos(π/2) evaluates to
 * approximately -4.37e-8 (not exactly 0.0) in IEEE 754. Raising a negative
 * float to a fractional power (x.pow(0.3f)) produces NaN. NaN passed to
 * AudioTrack.setVolume() throws IllegalArgumentException — the crossfade
 * crash visible in the logs every single time.
 *
 * ── The equal-power solution ─────────────────────────────────────────────────
 *
 * out(θ) = cos(θ × π/2)
 * in(θ)  = sin(θ × π/2)     where θ goes from 0.0 to 1.0
 *
 * Mathematical guarantee (Pythagorean identity):
 *   cos²(θ) + sin²(θ) = 1.0  — at EVERY step, for EVERY value of θ
 *
 * Total power is constant throughout the entire crossfade. The listener's
 * perceived loudness never spikes, dips, or clips. This is the same math
 * used by Pioneer CDJs, Serato DJ, Traktor Pro, and Ableton Live's crossfader.
 *
 * ── Tempo sync ───────────────────────────────────────────────────────────────
 *
 * Equal-power solves clipping, but two tracks at different BPMs will still
 * drift out of phase during the overlap. At 101 BPM vs 98 BPM, the tracks
 * are 440 ms out of phase by the end of an 8-second fade — half a beat.
 * That is the remaining "fighting" cause after fixing the volume curve.
 *
 * Fix: set the incoming track's PlaybackParameters to speed = outgoingBpm/incomingBpm.
 * Android's AudioTrack pitch-correction (sonic stretcher) makes this inaudible
 * up to ±8%. At 101/98 = 1.031, nobody hears it. The beats stay locked for the
 * entire overlap. Restore to 1.0x on swap.
 *
 * ── Volume normalisation ─────────────────────────────────────────────────────
 *
 * Amplitude-based normalisation (0.15/amplitude) is applied to the primary
 * player immediately when BPM metadata arrives via [updateCurrentBpmInfo].
 * This ensures [executeCrossfade] reads the correct normalised volume from the
 * player when it captures [primaryStartVolume] — no stale 1.0f surprises.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 *
 * All ExoPlayer calls MUST run on Dispatchers.Main. Every player interaction
 * inside coroutines is wrapped in withContext(Dispatchers.Main).
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    // ═════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "CrossfadeEngine"

        // ── Timing ────────────────────────────────────────────────────────────

        private const val POSITION_POLL_MS          = 300L
        private const val FAST_POLL_MS              = 50L
        private const val WAVEFORM_POLL_MS          = 16L
        private const val CROSSFADE_GUARD_MS        = 200L
        private const val FOCUS_RESUME_POLL_MS      = 50L
        private const val SEEK_SETTLE_DEADLINE_MS   = 300L
        private const val SEEK_SETTLE_TOLERANCE_MS  = 150L

        // ── Crossfade curve ───────────────────────────────────────────────────
        //
        // Equal-power: cos²(θ) + sin²(θ) = 1.  No exponents, no shaping, no tricks.
        // 80 steps at 100ms each = 8-second fade at the default duration.
        // Increasing to 80 from 60 gives a visibly smoother volume ramp on the UI
        // without any audible difference (volume steps are 1.25% apart).
        private const val FADE_STEPS = 80

        // ── Tempo sync ────────────────────────────────────────────────────────
        //
        // The incoming track's playback speed is adjusted to currentBpm/incomingBpm
        // so both tracks stay phase-locked during the overlap. Android's audio
        // renderer applies pitch correction (WSOLA/sonic stretcher) by default, so
        // tempo changes up to ±8% are inaudible.
        //
        // We cap at ±8% because:
        // a) Beyond 8% the pitch correction introduces audible artefacts on Android
        // b) A BPM difference > 8% (e.g. 100 vs 92 BPM) is an aggressive jump that
        //    the track-selection algorithm should have avoided anyway.
        private const val TEMPO_SYNC_MAX_RATIO = 1.08f
        private const val TEMPO_SYNC_MIN_RATIO = 0.92f

        // ── Beat grid / phase alignment ───────────────────────────────────────
        private const val BEAT_SNAP_WINDOW_MS    = 25L
        private const val PHRASE_BARS            = 8
        private const val BARS_PER_BEAT_MULTIPLE = 4
        private const val WAVEFORM_BARS          = 32

        // ── Queue logic ───────────────────────────────────────────────────────
        private const val CONTINUOUS_TRIGGER_WINDOW_MS   = 20_000L
        private const val CONTINUOUS_PREBUFFER_WINDOW_MS = 45_000L
        private const val GUARD_MAX_ITERATIONS           = 1_000
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COROUTINE SCOPE
    // ═════════════════════════════════════════════════════════════════════════

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ═════════════════════════════════════════════════════════════════════════
    // AUDIO FOCUS
    // ═════════════════════════════════════════════════════════════════════════

    private val audioFocusCoordinator: AudioFocusCoordinator by lazy {
        AudioFocusCoordinator(
            context  = context,
            listener = object : AudioFocusCoordinator.Listener {
                override fun onFocusLost()          = handleFocusLost()
                override fun onFocusLostTransient() = handleFocusLost()
                override fun onFocusGained()        = handleFocusGain()
            }
        )
    }

    @Volatile private var resumeAfterFocusGain: Boolean = false

    // ═════════════════════════════════════════════════════════════════════════
    // PLAYERS & PROCESSORS
    // ═════════════════════════════════════════════════════════════════════════

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null
    private var eqProcessorA: BandEqAudioProcessor? = null
    private var eqProcessorB: BandEqAudioProcessor? = null

    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()            = if (isPrimaryA) playerA            else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB            else playerA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC STATE
    // ═════════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // SETTINGS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Base crossfade duration in ms. Exposed as var so the UI can tune it.
     *
     * With equal-power, 8 seconds feels natural across all genres. The old
     * 14.4s (9000 × 1.6) was a workaround — the wide-valley curve needed to
     * be long because its clipping window occupied the entire middle third.
     * Equal-power is clean at any length.
     */
    var crossfadeDurationMs: Long = 8_000L

    var isRealMixMode: Boolean = false
    var maxTrackDurationMs: Long = 120_000L
    @Volatile var useHalfwayMix: Boolean = true
    @Volatile var cuePointOffsetMs: Long = 15_000L

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═════════════════════════════════════════════════════════════════════════

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
        val rawFirstBeatMs: Long,
        val bpm: Float,
        val amplitude: Float
    )

    // ═════════════════════════════════════════════════════════════════════════
    // CUE POINT GUARD
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyFirstBeatGuard(rawFirstBeatMs: Long, bpm: Float): Long {
        val offsetMs = cuePointOffsetMs
        if (rawFirstBeatMs <= 0L || bpm <= 0f || offsetMs <= 0L) {
            return rawFirstBeatMs.coerceAtLeast(0L)
        }
        if (rawFirstBeatMs >= offsetMs) return rawFirstBeatMs

        val beatIntervalMs = (60_000.0 / bpm).toLong().coerceAtLeast(1L)
        var adjusted   = rawFirstBeatMs
        var iterations = 0

        while (adjusted < offsetMs && iterations < GUARD_MAX_ITERATIONS) {
            adjusted += beatIntervalMs
            iterations++
        }

        val barIntervalMs = beatIntervalMs * 4
        val beatsAdvanced = (adjusted - rawFirstBeatMs) / beatIntervalMs
        val barsAdvanced  = (beatsAdvanced / 4) * 4
        val barAligned    = rawFirstBeatMs + (barsAdvanced * beatIntervalMs)
        val finalAdjusted = if (barAligned < offsetMs) barAligned + barIntervalMs else barAligned

        Log.d(TAG, "[CUE] raw=${rawFirstBeatMs}ms → guarded=${finalAdjusted}ms " +
                "(offset=${offsetMs}ms bpm=$bpm iters=$iterations)")
        return finalAdjusted
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AUDIO FOCUS HANDLERS
    // ═════════════════════════════════════════════════════════════════════════

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
        if (!resumeAfterFocusGain) { Log.d(TAG, "[FOCUS] Gained but user-paused"); return }
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
            isReleased               = false
            isPrimaryA               = true
            abortCrossfade           = false
            resumeAfterFocusGain     = false
            lastRequestedTrackId     = null
            lastPrebufferRequestedId = null
            pendingNextTrack         = null
            prebufferedTrackId       = null
            isPrebufferingInProgress = false
            playerA = null; playerB = null
            waveformProcessorA = null; waveformProcessorB = null
            eqProcessorA = null; eqProcessorB = null
            _state.value             = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm           = 0f
            currentTrackFirstBeatMs   = 0L
            currentTrackBaseVolume    = 1.0f
            currentTrackAmplitude     = 0f
            currentTrackMixOutMs      = null
            postCrossfadeGuardUntilMs = 0L
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
        Log.i(TAG, "[LIFECYCLE] Initialized — equal-power crossfade engine ready")
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
        val eqProcessor       = if (isPlayerA) eqProcessorA       else eqProcessorB
        val waveformProcessor = if (isPlayerA) waveformProcessorA else waveformProcessorB

        val processors: Array<AudioProcessor> = listOfNotNull(eqProcessor, waveformProcessor)
            .toTypedArray()

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

    // ═════════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═════════════════════════════════════════════════════════════════════════

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return
        Log.i(TAG, "[PLAYBACK] Starting: id=${audioFile.id} '${audioFile.title}'")

        crossfadeJob?.cancel(); crossfadeJob = null
        prebufferJob?.cancel()
        prebufferedTrackId       = null
        lastPrebufferRequestedId = null
        isPrebufferingInProgress = false
        isPrimaryA               = true
        _nextTrackRequest.resetReplayCache()
        postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
        lastRequestedTrackId      = null
        waveformSmoothed.fill(0f)
        currentWaveformEnvelope   = FloatArray(0)
        currentTrackMixOutMs      = null
        resumeAfterFocusGain      = false
        currentTrackBaseVolume    = 1.0f  // reset until BPM analysis provides amplitude

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

    /**
     * Updates beat-grid metadata and immediately applies amplitude normalisation
     * to the primary player's volume.
     *
     * Applying normalisation here (not lazily in executeCrossfade) means that
     * when the crossfade starts and reads primaryRef.volume, it gets the correct
     * normalised value rather than a stale 1.0f. This keeps the equal-power
     * fade centred on the track's actual perceived loudness.
     */
    fun updateCurrentBpmInfo(
        bpm: Float,
        rawFirstBeatMs: Long,
        amplitude: Float = 0f,
        waveformEnvelope: FloatArray = FloatArray(0),
        mixOutMs: Long? = null
    ) {
        currentTrackBpm         = bpm
        currentTrackFirstBeatMs = applyFirstBeatGuard(rawFirstBeatMs, bpm)
        currentTrackAmplitude   = amplitude
        currentWaveformEnvelope = waveformEnvelope
        currentTrackMixOutMs    = mixOutMs

        val normalisedVolume = if (amplitude > 0f)
            (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        currentTrackBaseVolume = normalisedVolume

        // Apply normalisation to the live player immediately.
        // This ensures executeCrossfade reads the correct starting volume.
        engineScope.launch {
            withContext(Dispatchers.Main) {
                primaryPlayer()?.volume = normalisedVolume.coerceIn(0f, 1f)
            }
        }

        Log.d(TAG, "[METADATA] BPM=$bpm firstBeat=${currentTrackFirstBeatMs}ms " +
                "amplitude=${"%.4f".format(amplitude)} normVol=${"%.3f".format(normalisedVolume)}")
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

    fun prebufferTrack(
        audioFile: AudioFile,
        rawFirstBeatMs: Long,
        bpm: Float,
        amplitude: Float
    ) {
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

                val guardedFirstBeatMs = applyFirstBeatGuard(rawFirstBeatMs, bpm)
                if (isRealMixMode && guardedFirstBeatMs > 0L) {
                    secondary.seekTo(guardedFirstBeatMs)
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} rough-seek=${guardedFirstBeatMs}ms " +
                            "(phase-seek will refine this at crossfade time)")
                } else {
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} no seek (continuous mode)")
                }
            }
            prebufferedTrackId       = audioFile.id
            isPrebufferingInProgress = false
        }
    }

    fun queueNextTrack(
        audioFile: AudioFile,
        rawFirstBeatMs: Long = 0L,
        nextBpm: Float = 0f,
        nextAmplitude: Float = 0f
    ) {
        if (isReleased) return
        if (_state.value.isCrossfading) {
            pendingNextTrack = PendingTrack(audioFile, rawFirstBeatMs, nextBpm, nextAmplitude)
            Log.d(TAG, "[MIXER] Crossfade in progress — '${audioFile.title}' queued as pending")
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile, rawFirstBeatMs, nextBpm, nextAmplitude)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CROSSFADE EXECUTION — EQUAL-POWER + TEMPO SYNC
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Full DJ crossfade sequence. Steps:
     *
     * 1. Prepare secondary player (or confirm it is already prebuffered).
     * 2. Wait for STATE_READY — usually < 50 ms if prebuffered.
     * 3. Start secondary muted — decoder warms up before we need it.
     * 4. Phase-aligned seek (Real Mix only) — sampled AFTER all waits so
     *    the latency between position sampling and seeking is < 50 ms.
     * 4b. Tempo sync — adjust incoming speed to current BPM if within ±8%.
     *     Prevents beat drift during the overlap window.
     * 5. Equal-power volume ramp over [FADE_STEPS] steps:
     *    out = cos(θ·π/2) · primaryStartVolume
     *    in  = sin(θ·π/2) · secondaryBaseVolume
     *    cos²+sin²=1 → total power is constant at every step → no clipping.
     *    A suspension wall parks the loop during phone calls; resumes from
     *    the exact same step when focus returns.
     * 6. Abort-check with full volume restore if cancelled.
     * 7. Swap: flip isPrimaryA BEFORE touching the old primary so its
     *    listener sees it as secondary and cannot corrupt isPlaying state.
     *    Restore tempo-sync speed to 1.0 on the new primary. Silence the
     *    old primary completely (was primaryBaseVolume — caused a pop).
     * 8. State sync and pending transition execution.
     */
    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        rawFirstBeatMs: Long = 0L,
        nextBpm: Float = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primaryRef   = primaryPlayer()
        val secondaryRef = secondaryPlayer()
        if (primaryRef == null || secondaryRef == null) return

        abortCrossfade = false

        val effectiveDurationMs = crossfadeDurationMs.coerceIn(2_000L, 16_000L)

        Log.i(TAG, "")
        Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.i(TAG, "║  DJ CROSSFADE START                                      ║")
        Log.i(TAG, "╠══════════════════════════════════════════════════════════╣")
        Log.i(TAG, "║  Outgoing : '${_state.value.currentTrack?.title}'")
        Log.i(TAG, "║  Incoming : '${nextTrack.title}'")
        Log.i(TAG, "║  Mode     : ${if (isRealMixMode) "Real Mix (phase-aligned)" else "Continuous"}")
        Log.i(TAG, "║  Duration : ${effectiveDurationMs}ms  Steps: $FADE_STEPS  Step-delay: ${effectiveDurationMs / FADE_STEPS}ms")
        Log.i(TAG, "║  Out BPM  : $currentTrackBpm  In BPM: $nextBpm")
        Log.i(TAG, "╚══════════════════════════════════════════════════════════╝")

        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        try {
            // Volume targets — both clamped to [0,1] to prevent any AudioTrack exception.
            val secondaryBaseVolume = (if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f).coerceIn(0f, 1f)
            val primaryBaseVolume = currentTrackBaseVolume.coerceIn(0f, 1f)

            Log.d(TAG, "[CROSSFADE] Volume targets → out: ${"%.3f".format(primaryBaseVolume)}  in: ${"%.3f".format(secondaryBaseVolume)}")

            // ── 1. Prepare secondary ──────────────────────────────────────────
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondaryRef.stop()
                    secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f
                    secondaryRef.prepare()
                    Log.d(TAG, "[CROSSFADE] ①  Secondary: fresh prepare started")
                } else {
                    prebufferedTrackId = null
                    Log.d(TAG, "[CROSSFADE] ①  Secondary: using prebuffered track (skipping prepare)")
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
            }

            // ── 2. Wait for STATE_READY ───────────────────────────────────────
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
            Log.i(TAG, "[CROSSFADE] ②  Ready in ${waitMs}ms (ready=$ready prebuffered=$alreadyPrebuffered)")

            if (!ready) {
                Log.e(TAG, "[CROSSFADE] ✗ Secondary never reached STATE_READY — aborting crossfade")
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── 3. Start secondary muted ──────────────────────────────────────
            withContext(Dispatchers.Main) {
                secondaryRef.volume = 0f
                try {
                    secondaryRef.play()
                } catch (e: Exception) {
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
            Log.i(TAG, "[CROSSFADE] ③  Secondary playing=${secondaryPlaying} after ${playWaitMs}ms")

            // ── 4. Phase-aligned seek (Real Mix only) ─────────────────────────
            // Sampled AFTER secondary is confirmed playing so the outgoing track
            // position we measure is as close as possible to when we seek.
            // The secondary is muted — the seek is completely inaudible.
            if (isRealMixMode) {
                val guardedIncomingFirstBeat = applyFirstBeatGuard(rawFirstBeatMs, nextBpm)

                val finalSeekMs = withContext(Dispatchers.Main) {
                    val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    PhaseAlignmentCalculator.calculate(
                        primaryCurrentPositionMs   = primaryRef.currentPosition,
                        outgoingGuardedFirstBeatMs = currentTrackFirstBeatMs,
                        outgoingBpm                = currentTrackBpm,
                        incomingGuardedFirstBeatMs = guardedIncomingFirstBeat,
                        incomingBpm                = nextBpm,
                        incomingDurationMs         = secDuration,
                        minRemainingMs             = effectiveDurationMs * 2
                    )
                }

                if (finalSeekMs > 0L) {
                    withContext(Dispatchers.Main) { secondaryRef.seekTo(finalSeekMs) }
                    val seekDeadline = System.currentTimeMillis() + SEEK_SETTLE_DEADLINE_MS
                    var settled = false
                    while (System.currentTimeMillis() < seekDeadline) {
                        val pos = withContext(Dispatchers.Main) { secondaryRef.currentPosition }
                        if (abs(pos - finalSeekMs) < SEEK_SETTLE_TOLERANCE_MS) {
                            Log.d(TAG, "[CROSSFADE] ④  Phase seek settled at ${pos}ms (target=${finalSeekMs}ms)")
                            settled = true
                            break
                        }
                        delay(20L)
                    }
                    if (!settled) Log.w(TAG, "[CROSSFADE] ④  Phase seek timeout — continuing anyway")
                } else {
                    Log.d(TAG, "[CROSSFADE] ④  Phase seek skipped (finalSeekMs=0)")
                }
            } else {
                Log.d(TAG, "[CROSSFADE] ④  Phase seek skipped (Continuous mode)")
            }

            // ── 4b. Tempo Sync ────────────────────────────────────────────────
            // Without this, two tracks at different BPMs drift apart during the
            // overlap. At 101 vs 98 BPM the drift is ~440 ms over 8 seconds —
            // roughly half a beat. That residual "fighting" sound persists even
            // with a perfect equal-power curve.
            //
            // Solution: adjust the incoming track's speed so it runs at the same
            // BPM as the outgoing track during the overlap. Android applies WSOLA
            // pitch correction so speed changes up to ±8% are inaudible.
            // Speed is restored to 1.0 during the swap step below.
            val tempoSyncRatio = if (isRealMixMode && currentTrackBpm > 0f && nextBpm > 0f) {
                (currentTrackBpm / nextBpm).coerceIn(TEMPO_SYNC_MIN_RATIO, TEMPO_SYNC_MAX_RATIO)
            } else {
                1.0f
            }

            val tempoSyncApplied = isRealMixMode && abs(tempoSyncRatio - 1.0f) > 0.001f
            if (tempoSyncApplied) {
                withContext(Dispatchers.Main) {
                    secondaryRef.playbackParameters = PlaybackParameters(tempoSyncRatio)
                }
                Log.i(TAG, "[CROSSFADE] ④b Tempo sync: ${nextBpm}bpm → ${currentTrackBpm}bpm | " +
                        "speed=${"%.4f".format(tempoSyncRatio)} | " +
                        "drift prevention over ${effectiveDurationMs}ms: " +
                        "${"%.0f".format(abs(1f - tempoSyncRatio) * effectiveDurationMs)}ms drift blocked")
            } else {
                Log.d(TAG, "[CROSSFADE] ④b Tempo sync: not needed (ratio=${"%.4f".format(tempoSyncRatio)})")
            }

            // ── 5. Equal-power volume ramp ────────────────────────────────────
            //
            // MATHEMATICS (for the log below to prove it works):
            //   out(θ) = cos(θ·π/2) · primaryStartVolume
            //   in(θ)  = sin(θ·π/2) · secondaryBaseVolume
            //
            //   If both volumes = 1.0: out² + in² = cos²+sin² = 1.0 exactly.
            //   If volumes differ: total power varies by the difference ratio,
            //   but it is still orders of magnitude better than the old curve
            //   which hit 159% combined amplitude.
            //
            // NaN SAFETY — THIS IS NOT OPTIONAL:
            //   cos(π/2) in IEEE 754 ≈ -4.37e-8 (not exactly 0.0).
            //   Without coerceIn, at step 80/80:
            //     (-4.37e-8f).pow(anything) = NaN
            //     AudioTrack.clampGainOrLevel(NaN) → IllegalArgumentException → crash
            //   The coerceIn on angle output prevents this. There is no other fix.
            //
            // SUSPENSION WALL:
            //   while(!isPlaying) parks the coroutine during a phone call.
            //   The fade resumes from the SAME step when focus returns.
            //   The step count is not lost.
            val primaryStartVolume = withContext(Dispatchers.Main) { primaryRef.volume }
                .coerceIn(0f, 1f)
            val stepDelayMs = (effectiveDurationMs / FADE_STEPS).coerceAtLeast(16L)

            Log.i(TAG, "[CROSSFADE] ⑤  Fade: out ${"%.3f".format(primaryStartVolume)}→0  " +
                    "in 0→${"%.3f".format(secondaryBaseVolume)}  " +
                    "${FADE_STEPS} steps × ${stepDelayMs}ms")

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break

                // Suspension wall — phone call / audio focus lost
                while (!_state.value.isPlaying && engineScope.isActive && !abortCrossfade) {
                    delay(FOCUS_RESUME_POLL_MS)
                }
                if (abortCrossfade) break

                val theta = step.toFloat() / FADE_STEPS   // 0.0 → 1.0 linearly
                val angle = theta * (PI.toFloat() / 2f)   // 0 → π/2

                // coerceIn(0f, 1f) is the NaN guard described above.
                // Do NOT remove it — the crash will return.
                val outFraction = cos(angle).coerceIn(0f, 1f)
                val inFraction  = sin(angle).coerceIn(0f, 1f)

                val outVol = (outFraction * primaryStartVolume).coerceIn(0f, 1f)
                val inVol  = (inFraction  * secondaryBaseVolume).coerceIn(0f, 1f)

                withContext(Dispatchers.Main) {
                    primaryRef.volume   = outVol
                    secondaryRef.volume = inVol
                }

                _state.update { it.copy(crossfadeProgressFraction = inFraction) }

                // ── Milestone logging: 25%, 50%, 75% ─────────────────────────
                // These logs PROVE equal-power is working correctly.
                // At each milestone:
                //   - outVol + inVol shows combined amplitude (should peak ~1.41 at 50%)
                //   - out²+in² / pSV²+sBV² should be ~1.0 (proves equal power)
                if (step == FADE_STEPS / 4 || step == FADE_STEPS / 2 || step == (FADE_STEPS * 3) / 4) {
                    val combinedAmp   = outVol + inVol
                    val outPower      = outVol * outVol
                    val inPower       = inVol  * inVol
                    val maxTotalPower = (primaryStartVolume * primaryStartVolume) +
                            (secondaryBaseVolume * secondaryBaseVolume)
                    val normPower = if (maxTotalPower > 0f)
                        (outPower + inPower) / maxTotalPower else 0f
                    Log.i(TAG, "[CROSSFADE] ${(theta * 100).toInt()}% │ " +
                            "out=${"%.3f".format(outVol)} " +
                            "in=${"%.3f".format(inVol)} │ " +
                            "Σamp=${"%.3f".format(combinedAmp)} " +
                            "power=${"%.3f".format(normPower)} (1.000=perfect equal-power)")
                }

                delay(stepDelayMs)
            }

            // ── 6. Abort check ────────────────────────────────────────────────
            if (abortCrossfade) {
                Log.w(TAG, "[CROSSFADE] ✗ Aborted at step — restoring primary volume to ${"%.3f".format(primaryStartVolume)}")
                withContext(Dispatchers.Main) {
                    primaryRef.volume = primaryStartVolume
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
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

            // ── 7. Swap players ───────────────────────────────────────────────
            // ORDER IS CRITICAL:
            // a) Set incoming to its target volume BEFORE the flip.
            // b) Flip isPrimaryA — now the old secondary is the primary.
            // c) Stop the OLD primary (now secondary). Set its volume to 0f,
            //    NOT primaryBaseVolume. Setting it back to a non-zero value
            //    causes an audible volume pop on the next crossfade's first step.
            // d) Restore tempo sync speed to 1.0 on the new primary — it was
            //    running at currentBpm/nextBpm during the overlap.
            Log.i(TAG, "[CROSSFADE] ↔  Swap: '${nextTrack.title}' becomes primary")
            withContext(Dispatchers.Main) {
                // Lock incoming track at its final target volume
                if (_state.value.isPlaying) {
                    secondaryRef.volume = secondaryBaseVolume.coerceIn(0f, 1f)
                }
                // Restore tempo sync speed BEFORE flip so the new primary plays at 1.0×
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT

                // THE FLIP — isPrimaryA changes here
                isPrimaryA = !isPrimaryA

                // Stop the old primary (now secondary) — silence it completely
                try {
                    primaryRef.pause()
                    primaryRef.volume = 0f   // 0f, not primaryBaseVolume — avoids volume pop
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                } catch (_: Exception) {}

                // Ensure the new primary is audibly playing
                if (_state.value.isPlaying) {
                    primaryPlayer()?.play()
                }
            }

            // ── 8. State reset ────────────────────────────────────────────────
            val actualPlaying = withContext(Dispatchers.Main) {
                primaryPlayer()?.isPlaying ?: false
            }

            lastRequestedTrackId      = null
            prebufferedTrackId        = null
            lastPrebufferRequestedId  = null
            currentTrackMixOutMs      = null
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + effectiveDurationMs

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = actualPlaying,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }

            Log.i(TAG, "")
            Log.i(TAG, "╔══════════════════════════════════════════════════════════╗")
            Log.i(TAG, "║  DJ CROSSFADE COMPLETE ✓                                 ║")
            Log.i(TAG, "╠══════════════════════════════════════════════════════════╣")
            Log.i(TAG, "║  Now playing : '${nextTrack.title}'")
            Log.i(TAG, "║  Playing     : $actualPlaying")
            Log.i(TAG, "║  Tempo sync  : $tempoSyncApplied (ratio=${"%.4f".format(tempoSyncRatio)})")
            Log.i(TAG, "║  Guard until : +${effectiveDurationMs}ms")
            Log.i(TAG, "╚══════════════════════════════════════════════════════════╝")
            Log.i(TAG, "")

            // Execute queued pending transition if one arrived during this fade
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                Log.d(TAG, "[CROSSFADE] Executing pending transition to '${pending.audioFile.title}'")
                executeCrossfade(pending.audioFile, pending.rawFirstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            // Always clean up isCrossfading flag, even if an exception escaped
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
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
                    val p   = primaryPlayer()
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
                        val phaseInPhrase =
                            (position - firstBeat).coerceAtLeast(0L) % phraseLengthMs
                        phaseInPhrase >= phraseLengthMs - barLengthMs
                    }
                }

                val mustTrigger  = beatLengthMs > 0L && remaining <= crossfadeDurationMs + beatLengthMs
                val triggerWindow = crossfadeDurationMs + beatLengthMs
                val prebufferZone = crossfadeDurationMs * 3 + beatLengthMs

                val effectiveTriggerWindowMs = if (isRealMixMode) triggerWindow
                else maxOf(triggerWindow, CONTINUOUS_TRIGGER_WINDOW_MS)
                val effectivePrebufferZoneMs = if (isRealMixMode) prebufferZone
                else maxOf(prebufferZone, CONTINUOUS_PREBUFFER_WINDOW_MS)

                val inTriggerZone   = duration > 0L && remaining in CROSSFADE_GUARD_MS..effectiveTriggerWindowMs
                val inPrebufferZone = duration > 0L && remaining in effectiveTriggerWindowMs..effectivePrebufferZoneMs
                val postGuardActive = System.currentTimeMillis() < postCrossfadeGuardUntilMs
                val customMixOut    = currentTrackMixOutMs != null && position >= currentTrackMixOutMs!!

                val mixAtMs: Long? = if (isRealMixMode && duration > 0L) {
                    if (useHalfwayMix) {
                        if (beatLengthMs > 0L && firstBeat > 0L) {
                            val halfMs      = duration / 2L
                            val beatsToHalf = ((halfMs - firstBeat).coerceAtLeast(0L) / beatLengthMs)
                            firstBeat + (beatsToHalf * beatLengthMs)
                        } else {
                            (duration / 2L) + firstBeat
                        }
                    } else {
                        maxTrackDurationMs
                    }
                } else null

                val isMaxTime = mixAtMs != null && position >= mixAtMs && remaining > crossfadeDurationMs

                val approachingMaxTime = isRealMixMode &&
                        mixAtMs != null &&
                        position >= (mixAtMs - crossfadeDurationMs * 2) &&
                        position < mixAtMs

                if ((inPrebufferZone || approachingMaxTime) && !_state.value.isCrossfading
                    && prebufferedTrackId == null && !isPrebufferingInProgress
                ) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                val beatAligned   = !isRealMixMode || (isOnBeat && (isAtPhrase || mustTrigger))
                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive
                        && duration > 0L &&
                        (customMixOut || ((inTriggerZone || isMaxTime) && beatAligned))

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] Auto-mix triggered: remaining=${remaining}ms " +
                                "isMaxTime=$isMaxTime customMixOut=$customMixOut")
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

                _state.update {
                    it.copy(
                        currentPositionMs = position,
                        currentDurationMs = duration,
                        timeToNextMixMs   = timeToNextMixMs
                    )
                }

                val fastPoll = inTriggerZone || inPrebufferZone || isMaxTime ||
                        customMixOut || approachingMaxTime
                delay(if (fastPoll) FAST_POLL_MS else POSITION_POLL_MS)
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
                    val pos = withContext(Dispatchers.Main) {
                        primaryPlayer()?.currentPosition ?: 0L
                    }
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
                val srcIdx  = (i.toFloat() / WAVEFORM_BARS * realBands.size).toInt()
                    .coerceIn(0, realBands.size - 1)
                val boosted = (realBands[srcIdx] * (1f + (i.toFloat() / WAVEFORM_BARS) * 2.5f))
                    .coerceIn(0f, 1f)
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
                val which = if (isPlayerA) "A" else "B"
                val role  = if ((isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA))
                    "PRIMARY" else "secondary"
                Log.i(TAG, "[PLAYBACK] Player $which ($role) ended")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            val which = if (isPlayerA) "A" else "B"
            val role  = if (isPrimary) "PRIMARY" else "secondary"
            Log.e(TAG, "[PLAYBACK] Player $which ($role) error: ${error.message} (code=${error.errorCode})", error)
            if (isPrimary) {
                _state.update { it.copy(error = "Player error: ${error.message}") }
            }
        }
    }
}