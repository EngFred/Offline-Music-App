package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
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

// ── Public data types ─────────────────────────────────────────────────────────

data class CrossfadeEngineState(
    val currentTrack: AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val currentMixStrategy: MixStrategy = MixStrategy.SMOOTH,
    val error: String? = null,
    val timeToNextMixMs: Long? = null
)

/**
 * Classifies the BPM relationship between outgoing and incoming tracks.
 * Each strategy drives different crossfade duration, tempo-sync behaviour, and EQ treatment.
 */
enum class MixStrategy {
    /** ≤3 BPM delta — transparent straight crossfade, no tempo adjustment. */
    TRANSPARENT,
    /** 3–8 BPM delta — tempo-sync + equal-power fade + ease-back. */
    SMOOTH,
    /** 8–15 BPM delta — early bass kill, extended fade, moderate tempo-sync. */
    POWER_MIX,
    /** Harmonic ratio (half-time, double-time, 3:2, 4:3) — short clean fade, no tempo-sync. */
    HARMONIC,
    /** >15 BPM delta — energy-valley technique, no tempo-sync, aggressive bass kill. */
    WIDE_TRANSITION
}

/**
 * Full decision record for one crossfade. Computed by [CrossfadeEngine.computeMixDecision];
 * drives every parameter in [CrossfadeEngine.executeCrossfade].
 *
 * @param stretchRatio  RubberBand time-stretch ratio = incomingBpm/outgoingBpm (1.0 = no stretch).
 * < 1.0 speeds up incoming track; > 1.0 slows it down.
 */
data class MixDecision(
    val outgoingBpm: Float,
    val incomingBpm: Float,
    val rawBpmDelta: Float,
    val effectiveBpmDelta: Float,
    val strategy: MixStrategy,
    val isHarmonic: Boolean,
    val effectiveCrossfadeDurationMs: Long,
    val shouldTempoSync: Boolean,
    val stretchRatio: Double,
    val bassKillThresholdFraction: Float,
    val djNote: String
)

// ── Engine ────────────────────────────────────────────────────────────────────

/**
 * Dual-[ExoPlayer] DJ crossfade engine with BPM-aware mix strategy selection.
 *
 * Key capabilities:
 * - **5-strategy mix engine** ([computeMixDecision]): classifies every BPM pair into
 * TRANSPARENT / SMOOTH / POWER_MIX / HARMONIC / WIDE_TRANSITION and derives crossfade
 * duration, tempo-sync flag, and bass-kill timing automatically.
 * - **RubberBand tempo sync**: music-quality time-stretching via [RubberBandAudioProcessor]
 * (replaces ExoPlayer's Sonic which is speech-optimised and distorts music above ~1.08×).
 * Each player owns its own processor instance; ratio changes are applied live.
 * - **Correct bass-kill EQ**: queries [android.media.audiofx.Equalizer.getBandFreqRange] to
 * find the actual bass band rather than blindly assuming band 0 (OEM HALs vary widely).
 * - **Pre-buffer**: silently loads the next track before the crossfade fires.
 * - **Beat-grid waveform**: 60 fps synthetic visualiser from BPM + RMS envelope; no
 * RECORD_AUDIO permission required.
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val smartNextTrack: GetSmartNextTrackUseCase
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        private const val POSITION_POLL_MS     = 300L
        private const val FAST_POLL_MS         = 50L
        private const val WAVEFORM_POLL_MS     = 16L
        private const val FADE_STEPS           = 60
        private const val CROSSFADE_GUARD_MS   = 200L
        private const val BEAT_SNAP_WINDOW_MS  = 25L // Tightened down for real DJ snapping
        private const val PHRASE_BARS          = 8
        private const val BARS_PER_BEAT_MULTIPLE = 4
        private const val WAVEFORM_BARS        = 32

        // Stretch ratio bounds for SMOOTH / POWER_MIX tempo sync.
        // RubberBand handles these ranges with professional quality (unlike Sonic).
        private const val MAX_STRETCH_RATIO    = 1.33  // incoming ~25% slower than outgoing
        private const val MIN_STRETCH_RATIO    = 0.75  // incoming ~25% faster than outgoing

        // Crossfade duration scale factors per strategy
        private const val TRANSPARENT_MULT     = 0.70f
        private const val SMOOTH_MULT          = 1.00f
        private const val POWER_MIX_MULT       = 1.40f
        private const val HARMONIC_MULT        = 0.80f
        private const val WIDE_TRANSITION_MULT = 1.60f
        private const val MIN_CROSSFADE_MS     = 2_000L
        private const val MAX_CROSSFADE_MS     = 14_000L

        // Bass-kill trigger points (fraction of crossfade elapsed).  Lower = earlier.
        private const val BASS_KILL_TRANSPARENT    = 0.70f
        private const val BASS_KILL_SMOOTH         = 0.50f
        private const val BASS_KILL_POWER_MIX      = 0.35f
        private const val BASS_KILL_HARMONIC       = 0.55f
        private const val BASS_KILL_WIDE_TRANSITION = 0.25f
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Players & processors (one-to-one; each player owns its processor) ────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var processorA: RubberBandAudioProcessor? = null
    private var processorB: RubberBandAudioProcessor? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null
    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()     = if (isPrimaryA) playerA    else playerB
    private fun secondaryPlayer()   = if (isPrimaryA) playerB    else playerA
    private fun primaryProcessor()  = if (isPrimaryA) processorA else processorB
    private fun secondaryProcessor()= if (isPrimaryA) processorB else processorA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB

    // ── State & settings ─────────────────────────────────────────────────────
    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest  = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest  = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    var crossfadeDurationMs: Long       = 5_000L
    var isRealMixMode: Boolean          = false
    var maxTrackDurationMs: Long        = 120_000L
    @Volatile var useHalfwayMix: Boolean = true

    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var waveformJob: Job?        = null
    private var prebufferJob: Job?       = null

    @Volatile private var currentTrackBpm: Float           = 0f
    @Volatile private var currentTrackFirstBeatMs: Long    = 0L
    @Volatile private var currentTrackBaseVolume: Float    = 1.0f
    @Volatile private var currentTrackAmplitude: Float     = 0f
    @Volatile private var currentWaveformEnvelope: FloatArray = FloatArray(0)
    @Volatile private var currentTrackMixOutMs: Long?      = null // ── NEW

    @Volatile private var prebufferedTrackId: Long?        = null
    @Volatile private var isPrebufferingInProgress         = false
    private var lastPrebufferRequestedId: Long?            = null
    private var lastRequestedTrackId: Long?                = null
    @Volatile private var pendingNextTrack: PendingTrack?  = null
    @Volatile private var isReleased                       = false
    private var isInitialized                              = false
    @Volatile private var abortCrossfade                   = false

    val isActive: Boolean get() = isInitialized && !isReleased

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
            currentTrackMixOutMs = null
        }

        // Initialize synchronously on the calling thread (Main) to avoid startup races
        try {
            processorA = RubberBandAudioProcessor()
            processorB = RubberBandAudioProcessor()
            waveformProcessorA = WaveformCaptureAudioProcessor()
            waveformProcessorB = WaveformCaptureAudioProcessor()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "RubberBand native lib unavailable — tempo-sync disabled", e)
            processorA = null; processorB = null
            waveformProcessorA = WaveformCaptureAudioProcessor()
            waveformProcessorB = WaveformCaptureAudioProcessor()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        playerA = buildExoPlayer(context, processorA, waveformProcessorA, attrs, true,  isPlayerA = true)
        playerB = buildExoPlayer(context, processorB, waveformProcessorB, attrs, false, isPlayerA = false)

        isInitialized = true
        Log.d(TAG, "initialize: ready (RubberBand=${processorA != null})")
    }

    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(
        ctx: Context,
        rubberBandProcessor: RubberBandAudioProcessor?,
        waveformProcessor: WaveformCaptureAudioProcessor?,
        attrs: AudioAttributes,
        handleAudioBecomingNoisy: Boolean,
        isPlayerA: Boolean
    ): ExoPlayer {
        val processors: Array<AudioProcessor> = listOfNotNull(
            waveformProcessor,
            rubberBandProcessor
        ).toTypedArray()

        val renderersFactory = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(processors)
                    .build()
            }
        }

        return ExoPlayer.Builder(ctx, renderersFactory)
            .build()
            .apply {
                setAudioAttributes(attrs, handleAudioBecomingNoisy)
                // FIX 1: Disable silence skipping. RubberBand needs to produce its own
                // startup silence to keep the ExoPlayer clock synchronized.
                skipSilenceEnabled = false
                addListener(createPlayerListener(isPlayerA))
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
        prebufferedTrackId = null; lastPrebufferRequestedId = null; isPrebufferingInProgress = false
        waveformSmoothed.fill(0f); currentWaveformEnvelope = FloatArray(0)

        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                primary.stop(); primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f; primary.prepare(); primary.play()
                lastRequestedTrackId = null
            }
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
        firstBeatMs: Long,
        amplitude: Float = 0f,
        waveformEnvelope: FloatArray = FloatArray(0),
        mixOutMs: Long? = null
    ) {
        currentTrackBpm           = bpm
        currentTrackFirstBeatMs   = firstBeatMs
        currentTrackAmplitude     = amplitude
        currentWaveformEnvelope   = waveformEnvelope
        currentTrackBaseVolume    = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        currentTrackMixOutMs      = mixOutMs
    }

    fun triggerMixNow() {
        if (isReleased || _state.value.isCrossfading) return
        val currentId = _state.value.currentTrack?.id ?: return
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
    }

    fun abortCurrentCrossfade() {
        if (!_state.value.isCrossfading) return
        abortCrossfade = true
        crossfadeJob?.cancel()
    }

    fun prebufferTrack(audioFile: AudioFile, firstBeatMs: Long, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return
        isPrebufferingInProgress = true
        prebufferJob?.cancel()

        prebufferJob = engineScope.launch {
            withContext(Dispatchers.Main) {
                val secondary = secondaryPlayer() ?: run { isPrebufferingInProgress = false; return@withContext }
                secondary.stop(); secondary.clearMediaItems()
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f; secondary.prepare()
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
            }
            prebufferedTrackId = audioFile.id
            isPrebufferingInProgress = false
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
        positionMonitorJob?.cancel(); crossfadeJob?.cancel()
        waveformJob?.cancel(); prebufferJob?.cancel()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
            } catch (e: Exception) {
                Log.e(TAG, "release: error releasing players", e)
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
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MIX DECISION ENGINE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Classifies the BPM pair and derives all crossfade parameters.
     *
     * Decision order:
     * 1. Check harmonic compatibility first (120→60 is HARMONIC, not WIDE_TRANSITION).
     * 2. Classify by raw delta into TRANSPARENT / SMOOTH / POWER_MIX / WIDE_TRANSITION.
     * 3. Scale crossfade duration by strategy multiplier.
     * 4. Compute RubberBand stretch ratio = incomingBpm / outgoingBpm (coerced to safe range).
     * 5. Select bass-kill trigger point based on strategy.
     */
    private fun computeMixDecision(
        outgoingBpm: Float,
        incomingBpm: Float,
        userCrossfadeDurationMs: Long
    ): MixDecision {
        val rawDelta       = abs(outgoingBpm - incomingBpm)
        val effectiveDelta = smartNextTrack.minimumHarmonicDelta(outgoingBpm, incomingBpm)
        val isHarmonic     = smartNextTrack.isHarmonicallyCompatible(outgoingBpm, incomingBpm)

        val strategy = when {
            isHarmonic && rawDelta > 3f -> MixStrategy.HARMONIC
            rawDelta <= 3f              -> MixStrategy.TRANSPARENT
            rawDelta <= 8f              -> MixStrategy.SMOOTH
            rawDelta <= 15f             -> MixStrategy.POWER_MIX
            else                        -> MixStrategy.WIDE_TRANSITION
        }

        val durationMult = when (strategy) {
            MixStrategy.TRANSPARENT     -> TRANSPARENT_MULT
            MixStrategy.SMOOTH          -> SMOOTH_MULT
            MixStrategy.POWER_MIX       -> POWER_MIX_MULT
            MixStrategy.HARMONIC        -> HARMONIC_MULT
            MixStrategy.WIDE_TRANSITION -> WIDE_TRANSITION_MULT
        }
        val effectiveDurationMs = (userCrossfadeDurationMs * durationMult)
            .toLong().coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        // Tempo sync only for SMOOTH / POWER_MIX.
        // HARMONIC: the harmonic lock IS the mix — sync fights it.
        // WIDE_TRANSITION: delta too large; visible speed artefacts even with RubberBand.
        val shouldTempoSync = strategy == MixStrategy.SMOOTH || strategy == MixStrategy.POWER_MIX

        // stretchRatio < 1 = speed up incoming; > 1 = slow it down to match outgoing BPM.
        val stretchRatio: Double = if (shouldTempoSync && outgoingBpm > 0f && incomingBpm > 0f) {
            (incomingBpm / outgoingBpm).toDouble().coerceIn(MIN_STRETCH_RATIO, MAX_STRETCH_RATIO)
        } else 1.0

        val bassKillThreshold = when (strategy) {
            MixStrategy.TRANSPARENT     -> BASS_KILL_TRANSPARENT
            MixStrategy.SMOOTH          -> BASS_KILL_SMOOTH
            MixStrategy.POWER_MIX       -> BASS_KILL_POWER_MIX
            MixStrategy.HARMONIC        -> BASS_KILL_HARMONIC
            MixStrategy.WIDE_TRANSITION -> BASS_KILL_WIDE_TRANSITION
        }

        val djNote = buildString {
            append("[DJ DECISION] ${strategy.name}: ")
            append("${outgoingBpm.fmt()} → ${incomingBpm.fmt()} BPM")
            append(" | rawΔ=${rawDelta.fmt()} effectiveΔ=${effectiveDelta.fmt()}")
            if (isHarmonic) append(" | ★ HARMONIC")
            append(" | fade=${effectiveDurationMs}ms")
            if (shouldTempoSync) {
                val speed = 1.0 / stretchRatio
                append(" | RubberBand stretch=${stretchRatio.fmt3()} (×${speed.fmt3()})")
            } else {
                append(" | NO tempo-sync")
            }
            append(" | bass kill at ${(bassKillThreshold * 100).toInt()}%")
            append("\n         ↳ ")
            when (strategy) {
                MixStrategy.TRANSPARENT     -> append("Silky smooth — nothing to hide.")
                MixStrategy.SMOOTH          -> append("Standard club technique — RubberBand stretch.")
                MixStrategy.POWER_MIX       -> append("Early bass kill gives incoming track space to breathe.")
                MixStrategy.HARMONIC        -> append("Half/double-time — harmonic lock does the work.")
                MixStrategy.WIDE_TRANSITION -> append("Energy valley technique — the BPM jump IS the moment.")
            }
        }

        return MixDecision(
            outgoingBpm                  = outgoingBpm,
            incomingBpm                  = incomingBpm,
            rawBpmDelta                  = rawDelta,
            effectiveBpmDelta            = effectiveDelta,
            strategy                     = strategy,
            isHarmonic                   = isHarmonic,
            effectiveCrossfadeDurationMs = effectiveDurationMs,
            shouldTempoSync              = shouldTempoSync,
            stretchRatio                 = stretchRatio,
            bassKillThresholdFraction    = bassKillThreshold,
            djNote                       = djNote
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CROSSFADE EXECUTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Full crossfade flow:
     * 1. [computeMixDecision] → classify & derive all params.
     * 2. Prepare secondary player (or resume pre-buffered).
     * 3. Apply RubberBand stretch to secondary processor (SMOOTH / POWER_MIX only).
     * 4. Equal-power sin/cos volume ramp; apply bass-kill EQ at strategy threshold.
     * 5. Swap players; Snap tempo ratio to 1.0 if stretch was applied.
     * 6. Execute any [pendingNextTrack] queued during the crossfade.
     */
    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        firstBeatMs: Long    = 0L,
        nextBpm: Float       = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primary   = primaryPlayer()   ?: return
        val secondary = secondaryPlayer() ?: return
        abortCrossfade = false

        val decision = computeMixDecision(currentTrackBpm, nextBpm, crossfadeDurationMs)
        Log.i(TAG, decision.djNote)

        _state.update {
            it.copy(isCrossfading = true, crossfadeProgressFraction = 0f,
                currentMixStrategy = decision.strategy)
        }

        var bassKillEq: android.media.audiofx.Equalizer? = null

        try {
            val secondaryBaseVolume = if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume   = currentTrackBaseVolume

            // ── Prepare secondary ─────────────────────────────────────────────
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondary.stop(); secondary.clearMediaItems()
                    secondary.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondary.volume = 0f; secondary.prepare()
                    if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                    secondary.play()
                } else {
                    secondary.play()
                    prebufferedTrackId = null
                }
                // Apply RubberBand stretch to secondary processor
                if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                    secondaryProcessor()?.setTimeRatio(decision.stretchRatio)
                    Log.d(TAG, "RubberBand: secondary stretch=${decision.stretchRatio.fmt3()}")
                }
            }

            if (!alreadyPrebuffered) {
                var waitMs = 0L
                while (waitMs < 2_000L) {
                    val ready = withContext(Dispatchers.Main) {
                        secondary.playbackState == Player.STATE_READY || secondary.isPlaying
                    }
                    if (ready) break
                    delay(100L); waitMs += 100L
                }
            } else {
                Log.d(TAG, "executeCrossfade: using pre-buffered track")
            }

            // ── Equal-power ramp + strategy-timed bass kill ───────────────────
            val stepDelayMs = (decision.effectiveCrossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
            var bassKillApplied = false

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break
                val progress = step.toFloat() / FADE_STEPS
                val angle    = progress * (PI.toFloat() / 2f)

                // Bass-kill: find the actual bass band via frequency range query (not band 0 assumption)
                if (!bassKillApplied && progress >= decision.bassKillThresholdFraction) {
                    bassKillApplied = true
                    withContext(Dispatchers.Main) {
                        try {
                            val sessionId = primary.audioSessionId
                            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                                val eq = android.media.audiofx.Equalizer(0, sessionId)
                                val bassIndex = findBassBandIndex(eq)
                                if (bassIndex != null) {
                                    eq.enabled = true
                                    eq.setBandLevel(bassIndex, eq.bandLevelRange[0])
                                    bassKillEq = eq
                                    Log.d(TAG, "Bass kill band=$bassIndex at ${(progress*100).toInt()}%")
                                } else {
                                    eq.release()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Bass kill EQ failed (non-fatal): ${e.message}")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    primary.volume   = cos(angle) * primaryBaseVolume
                    secondary.volume = sin(angle) * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = sin(angle)) }
                delay(stepDelayMs)
            }

            // ── Abort recovery ────────────────────────────────────────────────
            if (abortCrossfade) {
                Log.d(TAG, "executeCrossfade: ABORTED — restoring primary")
                secondaryProcessor()?.resetRatio()
                withContext(Dispatchers.Main) {
                    primary.volume = primaryBaseVolume
                    secondary.pause(); secondary.volume = 0f
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── Finalise player swap ──────────────────────────────────────────
            withContext(Dispatchers.Main) {
                primary.pause(); primary.volume = 1f
                secondary.volume = secondaryBaseVolume
            }

            isPrimaryA               = !isPrimaryA
            lastRequestedTrackId     = null
            prebufferedTrackId       = null
            lastPrebufferRequestedId = null

            _state.update {
                it.copy(currentTrack = nextTrack, isPlaying = true,
                    isCrossfading = false, crossfadeProgressFraction = 0f)
            }
            Log.d(TAG, "executeCrossfade: COMPLETE strategy=${decision.strategy.name}")

            // ── Post-crossfade tempo snap ─────────────────────────────────────
            // Snap immediately — crossfade just ended, the transition masked it
            if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                primaryProcessor()?.resetRatio()
                Log.d(TAG, "Tempo snap-back to 1.0 (ratio was ${decision.stretchRatio.fmt3()})")
            }

            // ── Execute pending track ─────────────────────────────────────────
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try { bassKillEq?.release(); bassKillEq = null } catch (e: Exception) {
                Log.e(TAG, "EQ release failed", e)
            }
        }
    }

    /**
     * Finds the bass equalizer band by inspecting each band's frequency range via
     * [android.media.audiofx.Equalizer.getBandFreqRange], which returns millihertz values.
     * Returns the band whose upper limit is lowest and ≤ 300 Hz (300 000 mHz).
     * Falls back to band 0 as a last resort so the kill always applies something.
     *
     * This avoids the broken assumption that band 0 is always bass — on many Samsung,
     * Xiaomi, and other OEM devices, the band ordering differs from the Pixel baseline.
     */
    private fun findBassBandIndex(eq: android.media.audiofx.Equalizer): Short? {
        val bandCount = eq.numberOfBands.toInt()
        if (bandCount == 0) return null

        val BASS_UPPER_LIMIT_MHZ = 300_000 // 300 Hz
        var lowestUpperMhz = Int.MAX_VALUE
        var bestBand = -1

        for (i in 0 until bandCount) {
            val upperMhz = eq.getBandFreqRange(i.toShort())[1]
            if (upperMhz < lowestUpperMhz) {
                lowestUpperMhz = upperMhz
                bestBand = i
            }
        }
        // Only use it if it's actually bass-range; otherwise fall back to the lowest band found
        return if (bestBand >= 0 && lowestUpperMhz <= BASS_UPPER_LIMIT_MHZ) bestBand.toShort()
        else if (bestBand >= 0) bestBand.toShort() // fallback: lowest band available
        else 0.toShort()
    }

    // ── Position monitoring ───────────────────────────────────────────────────

    /**
     * Polls playback position; emits [_prebufferRequest] and [_nextTrackRequest] at the
     * right times. Accelerates to [FAST_POLL_MS] inside the trigger / pre-buffer zone.
     * Crossfade triggers require beat alignment AND phrase-boundary proximity
     * (or a safety valve when within one beat of the hard end).
     */
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
                val isAtPhrase     = when {
                    phraseLengthMs <= 0L || firstBeat <= 0L -> true
                    else -> {
                        val phaseInPhrase = (position - firstBeat).coerceAtLeast(0L) % phraseLengthMs
                        phaseInPhrase >= phraseLengthMs - barLengthMs
                    }
                }

                val mustTrigger    = beatLengthMs > 0L && remaining <= crossfadeDurationMs + beatLengthMs
                val triggerWindow  = crossfadeDurationMs + beatLengthMs
                val prebufferZone  = crossfadeDurationMs * 3 + beatLengthMs
                val inTriggerZone  = duration > 0L && remaining in CROSSFADE_GUARD_MS..triggerWindow
                val inPrebufferZone= duration > 0L && remaining in triggerWindow..prebufferZone

                val isMaxTime = if (isRealMixMode && duration > 0L) {
                    val mixAt = if (useHalfwayMix) duration / 2L else maxTrackDurationMs
                    position >= mixAt && remaining > crossfadeDurationMs
                } else false

                // ── NEW: Custom user override ──
                val customMixOutTrigger = currentTrackMixOutMs != null && position >= currentTrackMixOutMs!!

                if (inPrebufferZone && !_state.value.isCrossfading
                    && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                // ── UPDATED TRIGGER LOGIC ──
                // It triggers IF it hits the user's custom point, OR if the standard AI conditions are met
                val shouldTrigger = playing && !_state.value.isCrossfading && duration > 0L && (
                        customMixOutTrigger ||
                                ((inTriggerZone || isMaxTime) && isOnBeat && (isAtPhrase || mustTrigger))
                        )

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                    }
                }

                val timeToNextMixMs: Long? = when {
                    _state.value.isCrossfading -> null
                    !playing || duration <= 0L -> null
                    customMixOutTrigger        -> 0L
                    currentTrackMixOutMs != null -> (currentTrackMixOutMs!! - position).coerceAtLeast(0L)
                    inTriggerZone              -> 0L
                    inPrebufferZone            -> (remaining - triggerWindow).coerceAtLeast(0L)
                    else                       -> null
                }

                _state.update {
                    it.copy(currentPositionMs = position, currentDurationMs = duration,
                        timeToNextMixMs = timeToNextMixMs)
                }

                delay(if (inTriggerZone || inPrebufferZone || isMaxTime || customMixOutTrigger) FAST_POLL_MS else POSITION_POLL_MS)
            }
        }
    }

    // ── 60fps waveform loop ───────────────────────────────────────────────────

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

    /**
     * Generates [WAVEFORM_BARS] normalised heights for the beat-grid visualiser.
     *
     * Shape priority:
     * 1. Real RMS envelope from [BpmAnalyzer] (track-specific, best quality).
     * 2. Synthetic sine curve (fallback until analysis completes).
     * 3. Low-amplitude placeholder (first ~1s before any BPM data).
     *
     * In all cases a beat-pulse (kick + snare phase from BPM grid) is multiplied in,
     * so bars pulse to the beat without RECORD_AUDIO permission.
     */
    private fun generateBeatWaveform(positionMs: Long): List<Float> {
        // Read real bands from the audio processor — this is actual music energy
        val realBands = primaryWaveformProcessor()?.getBands()

        return if (realBands != null && realBands.any { it > 0.01f }) {
            // Real data path: direct from PCM analysis
            // Upsample from BAND_COUNT to WAVEFORM_BARS with smoothing
            List(WAVEFORM_BARS) { i ->
                val srcIdx = (i.toFloat() / WAVEFORM_BARS * realBands.size)
                    .toInt().coerceIn(0, realBands.size - 1)
                val raw = realBands[srcIdx]
                waveformSmoothed[i] = waveformSmoothed[i] * 0.55f + raw * 0.45f
                waveformSmoothed[i]
            }
        } else {
            // Fallback only during the brief moment before ExoPlayer configures the processor
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

    // ── Formatting helpers ────────────────────────────────────────────────────

    private fun Float.fmt()  = String.format("%.1f", this)
    private fun Double.fmt3() = String.format("%.3f", this)
    private fun Float.fmt3() = String.format("%.3f", this)
}