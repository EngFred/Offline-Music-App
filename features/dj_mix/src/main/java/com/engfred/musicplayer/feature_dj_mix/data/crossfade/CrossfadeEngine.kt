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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dual-[ExoPlayer] DJ crossfade engine with BPM-aware mix strategy selection.
 * Pure volume-based crossfading without tempo stretching.
 *
 * 🚨 THREAD SAFETY 🚨
 * ALL ExoPlayer method calls (play, pause, getAudioSessionId, etc.) MUST be executed
 * inside `withContext(Dispatchers.Main)`. Calling them on a background coroutine
 * throws IllegalStateException and crashes the app.
 *
 * ── EQ Architecture ───────────────────────────────────────────────────────────
 *
 * The global EQ preset (selected by the user in Settings) applies to ALL audio
 * output — both the normal player and the DJ mix. This mirrors the behaviour of
 * professional audio apps (Spotify, Apple Music, Poweramp).
 *
 * Each of the two ExoPlayer instances in this engine owns its own
 * [BandEqAudioProcessor] instance (eqProcessorA / eqProcessorB). Both are
 * created inside [initialize] with no constructor dependencies. When the user
 * changes the preset, [AutoMixService] calls [applyEqPreset], which forwards
 * the new preset to both processors simultaneously.
 *
 * Why two instances? Because each ExoPlayer runs its own audio thread with its
 * own render pipeline. Sharing one [BandEqAudioProcessor] across two pipelines
 * would cause filter-state corruption (the delay elements w1/w2 written by one
 * thread would be immediately clobbered by the other).
 *
 * ── Cue Point Guard (moved here from BpmAnalyzer) ─────────────────────────────
 *
 * BpmAnalyzer now returns a RAW firstBeatMs (aubio beat-0, onset-offset included,
 * but NO minimum-offset guard). The guard is applied in THIS class via
 * [applyFirstBeatGuard], using the user-configurable [cuePointOffsetMs] (0–30 s).
 *
 * The single point-of-application rule:
 *   EVERY use of firstBeatMs that comes from outside the engine (via
 *   [updateCurrentBpmInfo], [prebufferTrack], or [queueNextTrack]) MUST pass
 *   through [applyFirstBeatGuard] before the value is stored or passed to
 *   ExoPlayer.seekTo(). Internal references to [currentTrackFirstBeatMs] are
 *   always the already-guarded value.
 *
 * ── Mix-timing and cue-point interplay ────────────────────────────────────────
 *
 * The halfway-mix trigger in startPositionMonitoring snaps to the nearest beat
 * boundary before the halfway point, ensuring the crossfade trigger always fires
 * on a musical beat rather than an arbitrary millisecond.
 *
 * ── Continuous Play Mode (isRealMixMode = false) ──────────────────────────────
 * First-beat seeking is SKIPPED — incoming tracks start from position 0.
 * Prebuffering skips the firstBeatMs seek for the same reason.
 *
 * ── Phase Alignment Architecture ─────────────────────────────────────────────
 * The phase-aligned seek now happens AFTER the secondary player is confirmed
 * playing at muted volume=0. This eliminates the latency bug where the phase
 * was calculated using primaryRef.currentPosition at time T₀, but the secondary
 * didn't produce audio until T₀ + 100–300ms later. The primary beat grid had
 * moved forward by then, leaving the secondary misaligned. Now the seek uses
 * the primary's position at the freshest possible moment, while the secondary
 * is already buffered so the seek resolves inaudibly in ~50ms.
 *
 * ── Tempo Sync Architecture ───────────────────────────────────────────────────
 * PlaybackParameters(speed) is called EXACTLY ONCE per crossfade — before the
 * fade loop begins, while the secondary player is still muted (volume=0).
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
         * many ms before the track ends.
         */
        private const val CONTINUOUS_TRIGGER_WINDOW_MS = 12_000L

        /**
         * In continuous mode, request prebuffering this many ms before track end.
         */
        private const val CONTINUOUS_PREBUFFER_WINDOW_MS = 30_000L

        /**
         * Safety cap on the phase-advance loop in [applyFirstBeatGuard].
         */
        private const val GUARD_MAX_ITERATIONS = 1_000
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Players & Processors ──────────────────────────────────────────────────
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
    @Volatile var cuePointOffsetMs: Long = 15_000L

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
        var adjusted = rawFirstBeatMs
        var iterations = 0

        while (adjusted < offsetMs && iterations < GUARD_MAX_ITERATIONS) {
            adjusted += beatIntervalMs
            iterations++
        }

        Log.d(TAG,
            "[CUE] Guard applied: raw=${rawFirstBeatMs}ms → guarded=${adjusted}ms " +
                    "(cueOffset=${offsetMs}ms, bpm=$bpm, " +
                    "interval=${beatIntervalMs}ms, advances=$iterations)"
        )
        return adjusted
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
            isReleased               = false; isPrimaryA = true; abortCrossfade = false
            lastRequestedTrackId     = null; lastPrebufferRequestedId = null
            pendingNextTrack         = null; prebufferedTrackId = null
            isPrebufferingInProgress = false
            playerA = null; playerB = null
            waveformProcessorA = null; waveformProcessorB = null
            eqProcessorA = null; eqProcessorB = null
            _state.value             = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm = 0f; currentTrackFirstBeatMs = 0L
            currentTrackBaseVolume = 1.0f; currentTrackAmplitude = 0f
            currentTrackMixOutMs = null; postCrossfadeGuardUntilMs = 0L
        }

        waveformProcessorA = WaveformCaptureAudioProcessor()
        waveformProcessorB = WaveformCaptureAudioProcessor()
        eqProcessorA = BandEqAudioProcessor()
        eqProcessorB = BandEqAudioProcessor()

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        playerA = buildExoPlayer(attrs, handleAudioFocus = true,  isPlayerA = true)
        playerB = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = false)

        isInitialized = true
        Log.i(TAG, "[LIFECYCLE] CrossfadeEngine Initialized")
    }

    fun applyEqPreset(preset: AudioPreset) {
        eqProcessorA?.setPreset(preset)
        eqProcessorB?.setPreset(preset)
        Log.d(TAG, "[EQ] Preset applied to both DJ players: $preset")
    }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        attrs: AudioAttributes,
        handleAudioFocus: Boolean,
        isPlayerA: Boolean
    ): ExoPlayer {
        val eqProcessor       = if (isPlayerA) eqProcessorA       else eqProcessorB
        val waveformProcessor = if (isPlayerA) waveformProcessorA else waveformProcessorB

        val processors: Array<AudioProcessor> = listOfNotNull(
            eqProcessor,
            waveformProcessor
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
                eqProcessorA = null
                eqProcessorB = null
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
        currentTrackBaseVolume  = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        currentTrackMixOutMs    = mixOutMs
        Log.d(TAG, "[METADATA] BPM=$bpm rawFirstBeat=${rawFirstBeatMs}ms " +
                "guardedFirstBeat=${currentTrackFirstBeatMs}ms " +
                "cueOffset=${cuePointOffsetMs}ms Vol=$currentTrackBaseVolume")
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
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} " +
                            "raw=${rawFirstBeatMs}ms guarded=${guardedFirstBeatMs}ms")
                } else {
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} " +
                            if (!isRealMixMode) "continuous play — no seek"
                            else "guardedFirstBeat=0 — no seek")
                }
            }
            prebufferedTrackId = audioFile.id
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
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            executeCrossfade(audioFile, rawFirstBeatMs, nextBpm, nextAmplitude)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CROSSFADE EXECUTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Key change vs the original: the phase-aligned seek now happens AFTER the
     * secondary player is confirmed playing at muted volume=0, not before.
     *
     * Old order: seekTo(stale position) → play() → wait → fade
     * New order: play() → wait for playing → seekTo(fresh position) → settle → fade
     *
     * Because the secondary is muted, the seek is completely inaudible.
     * Because the secondary is prebuffered, the seek resolves in ~50ms.
     * Because we sample primaryRef.currentPosition right before the seek,
     * the phase calculation uses the freshest possible primary position —
     * eliminating the 100–300ms latency error that caused beat misalignment.
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
                isCrossfading             = true,
                crossfadeProgressFraction = 0f,
                currentMixStrategy        = decision.strategy
            )
        }

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
            var ready  = false
            while (waitMs < 3000L) {
                ready = withContext(Dispatchers.Main) {
                    secondaryRef.playbackState == Player.STATE_READY &&
                            secondaryRef.currentMediaItem != null
                }
                if (ready) break
                delay(100L)
                waitMs += 100L
            }
            Log.i(TAG, "[TIMING] ready_wait=${waitMs}ms (ready=$ready, prebuffered=$alreadyPrebuffered)")

            // ── 3. Start Secondary MUTED ──────────────────────────────────────
            // Start playing at volume=0 FIRST so the decoder is running and
            // audio is flowing through the pipeline. The phase-aligned seek
            // (Step 4) happens after this while the player is already active.
            // Because the secondary is muted and prebuffered, that seek is both
            // inaudible and near-instantaneous (~50ms). This is the core fix for
            // beat misalignment: the phase calculation uses the primary's position
            // at the LAST possible moment before the fade begins, not up to
            // several seconds earlier.
            withContext(Dispatchers.Main) {
                try {
                    secondaryRef.volume = 0f
                    secondaryRef.play()
                } catch (e: Exception) {
                    Log.e(TAG, "[MIXER] Secondary play failed", e)
                }
            }

            var playWaitMs       = 0L
            var secondaryPlaying = false
            while (!secondaryPlaying && playWaitMs < 1000L) {
                delay(50L)
                playWaitMs       += 50L
                secondaryPlaying  = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }
            Log.i(TAG, "[TIMING] play_wait=${playWaitMs}ms (playing=$secondaryPlaying)")

            // ── 4. PHASE-ALIGNED SEEK — sampled RIGHT NOW after all async waits ─
            // Secondary is muted (volume=0) → seek is completely inaudible.
            // Secondary is already playing/buffered → seek resolves in ~50ms.
            // Primary position is sampled HERE, giving the freshest possible
            // phase reading before the fade loop starts.
            if (isRealMixMode) {
                val guardedIncomingFirstBeat = applyFirstBeatGuard(rawFirstBeatMs, nextBpm)

                val finalSeekMs = withContext(Dispatchers.Main) {
                    val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L

                    if (secDuration <= 0L || guardedIncomingFirstBeat <= 0L ||
                        currentTrackBpm <= 0f || nextBpm <= 0f
                    ) {
                        Log.d(TAG, "[SEEK] Fallback to bare cue point: ${guardedIncomingFirstBeat}ms")
                        guardedIncomingFirstBeat
                    } else {
                        // Sample primary position at this exact moment — after all waits
                        val primaryNow      = primaryRef.currentPosition
                        val outgoingBeatLen = 60_000f / currentTrackBpm
                        val incomingBeatLen = 60_000f / nextBpm

                        // Where in its beat cycle is the primary right now?
                        val rawPhase     = (primaryNow - currentTrackFirstBeatMs).coerceAtLeast(0L)
                        val primaryPhase = (rawPhase % outgoingBeatLen.toLong() +
                                outgoingBeatLen.toLong()) % outgoingBeatLen.toLong()

                        // Map that fractional position onto the incoming beat grid
                        val phaseFraction = primaryPhase.toFloat() / outgoingBeatLen
                        val sourcePhaseMs = (phaseFraction * incomingBeatLen).toLong()

                        val minRemaining = decision.effectiveCrossfadeDurationMs * 2
                        val maxSafe      = (secDuration - minRemaining).coerceAtLeast(0L)
                        val target       = (guardedIncomingFirstBeat + sourcePhaseMs)
                            .coerceAtMost(maxSafe)
                            .coerceAtLeast(0L)

                        Log.d(TAG,
                            "[SEEK] primaryNow=${primaryNow}ms primaryPhase=${primaryPhase}ms " +
                                    "phaseFraction=${"%.3f".format(phaseFraction)} " +
                                    "guarded=${guardedIncomingFirstBeat}ms " +
                                    "sourcePhase=${sourcePhaseMs}ms → target=${target}ms"
                        )
                        target
                    }
                }

                if (finalSeekMs > 0L) {
                    withContext(Dispatchers.Main) {
                        secondaryRef.seekTo(finalSeekMs)
                    }
                    // Short settle: secondary is already buffered so decoder
                    // flush is fast. 80ms is enough for the audio pipeline to
                    // resume from the new position before the fade loop starts.
                    delay(80L)
                }
            } else {
                // Continuous Play mode — no seek, play from wherever prebuffer left it
                Log.d(TAG, "[MIXER] Continuous Play mode — skipping phase seek")
            }

            // ── 5. ONE-SHOT TEMPO PRIME ───────────────────────────────────────
            // Set outgoing speed once while secondary is still muted — prevents
            // Sonic flush artefacts during the audible overlap window.
            if (decision.isEffectivelyTempoSynced) {
                withContext(Dispatchers.Main) {
                    try {
                        primaryRef.playbackParameters =
                            PlaybackParameters(decision.stretchRatio.toFloat())
                        Log.d(TAG, "[TEMPO] One-shot prime: outgoing speed → " +
                                "${String.format("%.4f", decision.stretchRatio)} " +
                                "(${decision.outgoingBpm.fmt()} → ${decision.incomingBpm.fmt()} BPM)")
                    } catch (e: Exception) {
                        Log.w(TAG, "[TEMPO] One-shot prime failed: ${e.message}")
                    }
                }
            }

            // ── 5.5. IMMEDIATE BASS KILL on outgoing track ────────────────────
            withContext(Dispatchers.Main) {
                try {
                    val sessionId = primaryRef.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        val eq        = android.media.audiofx.Equalizer(0, sessionId)
                        val bassIndex = mixDecisionEngine.findBassBandIndex(eq)
                        if (bassIndex != null) {
                            eq.enabled = true
                            eq.setBandLevel(bassIndex, eq.bandLevelRange[0])
                            bassKillEq = eq
                            Log.d(TAG, "[MIXER] Immediate bass kill applied at crossfade start")
                        } else {
                            eq.release()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[MIXER] Immediate bass kill failed: ${e.message}")
                }
            }

            // ── 6. Equal-Power Ramp ───────────────────────────────────────────
            val primaryStartVolume = withContext(Dispatchers.Main) { primaryRef.volume }
            val stepDelayMs = (decision.effectiveCrossfadeDurationMs / FADE_STEPS)
                .coerceAtLeast(16L)

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break

                val progress = step.toFloat() / FADE_STEPS
                val angle    = progress * (PI.toFloat() / 2f)

                withContext(Dispatchers.Main) {
                    primaryRef.volume   = cos(angle) * primaryStartVolume
                    secondaryRef.volume = sin(angle) * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = sin(angle)) }
                delay(stepDelayMs)
            }

            // ── 7. Abort Check ────────────────────────────────────────────────
            if (abortCrossfade) {
                withContext(Dispatchers.Main) {
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    primaryRef.volume = primaryStartVolume
                    try { secondaryRef.pause(); secondaryRef.volume = 0f } catch (e: Exception) {}
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── 8. Swap Players ───────────────────────────────────────────────
            withContext(Dispatchers.Main) {
                try {
                    primaryRef.pause()
                    primaryRef.volume             = primaryBaseVolume
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                    if (secondaryRef.isPlaying) secondaryRef.volume = secondaryBaseVolume
                } catch (e: Exception) {}
            }

            val swapped = withContext(Dispatchers.Main) {
                if (secondaryRef.isPlaying) { isPrimaryA = !isPrimaryA; true } else false
            }

            if (!swapped) {
                withContext(Dispatchers.Main) {
                    try { primaryRef.volume = primaryBaseVolume; primaryRef.play() } catch (e: Exception) {}
                }
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            withContext(Dispatchers.Main) {
                try { primaryPlayer()?.play() } catch (e: Exception) {}
            }

            // ── 9. Reset State ────────────────────────────────────────────────
            lastRequestedTrackId     = null
            prebufferedTrackId       = null
            lastPrebufferRequestedId = null
            currentTrackMixOutMs     = null
            postCrossfadeGuardUntilMs =
                System.currentTimeMillis() + decision.effectiveCrossfadeDurationMs

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = true,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }

            Log.i(TAG, "[MIXER] Swap Complete → Next Track: '${nextTrack.title}'")

            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(
                    pending.audioFile,
                    pending.rawFirstBeatMs,
                    pending.bpm,
                    pending.amplitude
                )
            }

        } finally {
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try { bassKillEq?.release(); bassKillEq = null } catch (e: Exception) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Key change vs the original: the halfway-mix trigger (mixAtMs) now snaps
     * to the nearest beat boundary before the halfway point. This means the
     * crossfade trigger always fires on a musical beat, not an arbitrary
     * millisecond that could land mid-beat and make the transition feel dragged.
     */
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

                val mustTrigger   = beatLengthMs > 0L && remaining <= crossfadeDurationMs + beatLengthMs
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

                // ── Snapped halfway-mix trigger ───────────────────────────────
                // Instead of firing at an arbitrary (duration / 2) millisecond,
                // we count how many beat intervals fit before the halfway point
                // and snap to the last one. This guarantees the trigger always
                // fires on a beat boundary, producing a cleaner musical transition.
                val mixAtMs: Long? = if (isRealMixMode && duration > 0L) {
                    if (useHalfwayMix) {
                        if (beatLengthMs > 0L && firstBeat > 0L) {
                            val halfTimeMs   = duration / 2L
                            val beatsToHalf  = ((halfTimeMs - firstBeat).coerceAtLeast(0L) / beatLengthMs)
                            firstBeat + (beatsToHalf * beatLengthMs)
                        } else {
                            // No beat data — fall back to the original formula
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
                    && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                val beatAligned = !isRealMixMode || (isOnBeat && (isAtPhrase || mustTrigger))
                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive
                        && duration > 0L &&
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
                val srcIdx = (i.toFloat() / WAVEFORM_BARS * realBands.size).toInt()
                    .coerceIn(0, realBands.size - 1)
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

    private fun Float.fmt() = String.format("%.1f", this)
}