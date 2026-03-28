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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
 * Split into three files for maintainability:
 *  - [CrossfadeModels]    — data types (CrossfadeEngineState, MixStrategy, MixDecision)
 *  - [MixDecisionEngine]  — pure BPM classification + bass-kill EQ logic
 *  - [CrossfadeEngine]    — this file: player lifecycle, crossfade execution, position monitoring
 *
 * Robustness notes:
 *  - startPlayback() always resets to PlayerA (audio focus holder) and clears stale
 *    crossfade jobs, prebuffer state, and the nextTrackRequest replay cache to prevent
 *    a spurious immediate crossfade on fresh session start.
 *  - A 5-second postCrossfadeGuard is applied after startPlayback so the position
 *    monitor cannot trigger an auto-crossfade before the track has a chance to play.
 *  - executeCrossfade() clamps the incoming seek point so the incoming track cannot
 *    end mid-fade, re-asserts audio focus after a player swap, and recovers the
 *    primary if the secondary swap fails.
 *
 * Known open issue (MTK / AAC DRC stall):
 *  On MediaTek devices, certain AAC files cause the AudioTrack to silently freeze
 *  after the AAC decoder emits two DRC format changes (~400ms post-play). Position
 *  stays at 0 indefinitely even though isPlaying=true. seekTo(0) re-triggers the
 *  DRC loop and does not help. The user can work around this by pressing "Mix Now"
 *  which loads the file into PlayerB where it plays correctly.
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
    }

    // ── Coroutine scope ───────────────────────────────────────────────────────

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Players & processors ──────────────────────────────────────────────────

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var processorA: RubberBandAudioProcessor? = null
    private var processorB: RubberBandAudioProcessor? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null

    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()            = if (isPrimaryA) playerA          else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB          else playerA
    private fun primaryProcessor()         = if (isPrimaryA) processorA       else processorB
    private fun secondaryProcessor()       = if (isPrimaryA) processorB       else processorA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB

    // ── Public state ──────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ── Settings (write from ViewModel / Service) ─────────────────────────────

    var crossfadeDurationMs: Long        = 5_000L
    var isRealMixMode: Boolean           = false
    var maxTrackDurationMs: Long         = 120_000L
    @Volatile var useHalfwayMix: Boolean = true

    // ── Internal jobs ─────────────────────────────────────────────────────────

    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var waveformJob: Job?        = null
    private var prebufferJob: Job?       = null

    // ── Current-track metadata ────────────────────────────────────────────────

    @Volatile private var currentTrackBpm: Float         = 0f
    @Volatile private var currentTrackFirstBeatMs: Long  = 0L
    @Volatile private var currentTrackBaseVolume: Float  = 1.0f
    @Volatile private var currentTrackAmplitude: Float   = 0f
    @Volatile private var currentWaveformEnvelope: FloatArray = FloatArray(0)
    @Volatile private var currentTrackMixOutMs: Long?    = null
    @Volatile private var postCrossfadeGuardUntilMs: Long = 0L

    // ── Pre-buffer / queue state ──────────────────────────────────────────────

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
    // INITIALISATION / RELEASE
    // ═════════════════════════════════════════════════════════════════════════

    fun initialize() {
        if (isInitialized) {
            // Clear stale replay on no-op init so a previous session's nextTrackRequest
            // cannot trigger a spurious crossfade when a new session starts.
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
        }

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

        // PlayerA owns audio focus. After a crossfade swap, play() is called on the
        // new primary to re-request focus (see executeCrossfade FIX 2).
        playerA = buildExoPlayer(attrs, handleAudioFocus = true,  isPlayerA = true)
        playerB = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = false)

        isInitialized = true
        Log.d(TAG, "initialize: ready (RubberBand=${processorA != null})")
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

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(processors)
                .build()
        }

        return ExoPlayer.Builder(context, renderersFactory).build().apply {
            setAudioAttributes(attrs, handleAudioFocus)
            skipSilenceEnabled = false   // RubberBand needs startup silence for clock sync
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
        Log.d(TAG, "release: engine released")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return

        // Cancel any stale crossfade — its volume ramp must not overwrite the new track.
        crossfadeJob?.cancel()
        crossfadeJob = null

        // Cancel any in-flight prebuffer — PlayerB belongs to the new session now.
        prebufferJob?.cancel()
        prebufferedTrackId = null
        lastPrebufferRequestedId = null
        isPrebufferingInProgress = false

        // Always start on PlayerA so audio focus is correctly held from the first track.
        isPrimaryA = true

        // Clear replay cache — prevents a stale nextTrackRequest from a previous session
        // being delivered to the service and immediately crossfading the new first track.
        _nextTrackRequest.resetReplayCache()

        // Guard the position monitor for 5 s so it cannot fire a crossfade before
        // the track has had time to start playing.
        postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L

        lastRequestedTrackId = null
        waveformSmoothed.fill(0f)
        currentWaveformEnvelope = FloatArray(0)
        currentTrackMixOutMs = null

        engineScope.launch {
            withContext(Dispatchers.Main) {
                // Silence PlayerB so it cannot bleed into the new session.
                playerB?.pause()
                playerB?.clearMediaItems()
                playerB?.volume = 0f

                val primary = playerA ?: return@withContext
                Log.d(TAG, "startPlayback: loading ${audioFile.id} uri=${audioFile.uri}")
                primary.stop()
                primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.prepare()
                primary.play()
                Log.d(TAG, "startPlayback: play() called; playbackState=${primary.playbackState} " +
                        "audioSessionId=${primary.audioSessionId}")
            }

            // Re-stamp the guard after the Main block to account for scheduling delay.
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
                Log.d(TAG, "playPause: isPlaying=${p.isPlaying}")
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
        Log.d(TAG, "updateCurrentBpmInfo: bpm=$bpm firstBeatMs=$firstBeatMs " +
                "amplitude=$amplitude mixOutMs=$mixOutMs")
    }

    fun triggerMixNow() {
        if (isReleased || _state.value.isCrossfading) return
        val currentId = _state.value.currentTrack?.id ?: return
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.d(TAG, "triggerMixNow: emitted nextTrackRequest for id=$currentId")
    }

    fun abortCurrentCrossfade() {
        if (!_state.value.isCrossfading) return
        abortCrossfade = true
        crossfadeJob?.cancel()
        Log.d(TAG, "abortCurrentCrossfade: signalled")
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
                Log.d(TAG, "prebufferTrack: preparing id=${audioFile.id}")
                secondary.stop(); secondary.clearMediaItems()
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f; secondary.prepare()
                // Seek is applied here speculatively; executeCrossfade will clamp it safely.
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                Log.d(TAG, "prebufferTrack: prepared audioSessionId=${secondary.audioSessionId}")
            }
            prebufferedTrackId       = audioFile.id
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
            Log.d(TAG, "queueNextTrack: queued pendingNextTrack id=${audioFile.id}")
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

    /**
     * Full crossfade flow:
     *  1. Classify BPM pair → MixDecision (strategy, duration, tempo-sync, bass-kill point).
     *  2. Prepare secondary player (or resume pre-buffered track).
     *  3. Clamp seek: if firstBeatMs leaves < 2× fade duration before track end, pull back.
     *  4. Start secondary muted; wait for it to actually begin playing.
     *  5. Equal-power sin/cos volume ramp with optional bass-kill EQ on outgoing track.
     *  6. Finalise player swap; re-assert audio focus on new primary.
     *  7. If swap fails (secondary stopped), restart primary to avoid total silence.
     *  8. Execute any pendingNextTrack queued during the crossfade.
     */
    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        firstBeatMs: Long    = 0L,
        nextBpm: Float       = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primaryRef   = primaryPlayer()
        val secondaryRef = secondaryPlayer()
        if (primaryRef == null || secondaryRef == null) {
            Log.w(TAG, "executeCrossfade: missing players — aborting")
            return
        }
        abortCrossfade = false

        val decision = mixDecisionEngine.computeMixDecision(currentTrackBpm, nextBpm, crossfadeDurationMs)
        Log.i(TAG, decision.djNote)

        _state.update {
            it.copy(isCrossfading = true, crossfadeProgressFraction = 0f,
                currentMixStrategy = decision.strategy)
        }

        var bassKillEq: android.media.audiofx.Equalizer? = null

        try {
            val secondaryBaseVolume = if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume = currentTrackBaseVolume

            // ── Prepare secondary ─────────────────────────────────────────────
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    Log.d(TAG, "executeCrossfade: preparing secondary for id=${nextTrack.id}")
                    secondaryRef.stop(); secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f; secondaryRef.prepare()
                    Log.d(TAG, "executeCrossfade: secondary prepared " +
                            "audioSessionId=${secondaryRef.audioSessionId}")
                } else {
                    Log.d(TAG, "executeCrossfade: using prebuffered id=${nextTrack.id}")
                    prebufferedTrackId = null
                }

                if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                    secondaryProcessor()?.setTimeRatio(decision.stretchRatio)
                    Log.d(TAG, "RubberBand: secondary stretch=${decision.stretchRatio}")
                }
            }

            // ── Wait for READY ────────────────────────────────────────────────
            var waitMs = 0L
            var ready  = false
            while (waitMs < 3000L) {
                ready = withContext(Dispatchers.Main) {
                    secondaryRef.playbackState == Player.STATE_READY &&
                            secondaryRef.currentMediaItem != null
                }
                if (ready) break
                if (waitMs % 500L == 0L)
                    Log.d(TAG, "executeCrossfade: waiting for secondary READY… waited=${waitMs}ms")
                delay(100L); waitMs += 100L
            }
            Log.d(TAG, "executeCrossfade: secondary ready=$ready after ${waitMs}ms " +
                    "(playbackState=${withContext(Dispatchers.Main) { secondaryRef.playbackState }})")

            // ── Seek clamp ────────────────────────────────────────────────────
            // Read the real duration only after STATE_READY so it is known.
            // If firstBeatMs leaves < 2× crossfade duration remaining, pull it back.
            val safeFirstBeatMs: Long = withContext(Dispatchers.Main) {
                val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                Log.w(TAG, "SEEK DIAGNOSTIC: id=${nextTrack.id} duration=${secDuration}ms " +
                        "firstBeatMs=${firstBeatMs}ms remaining=${secDuration - firstBeatMs}ms")
                if (secDuration > 0L && firstBeatMs > 0L) {
                    val minRemaining = decision.effectiveCrossfadeDurationMs * 2
                    val maxSafe      = (secDuration - minRemaining).coerceAtLeast(0L)
                    val safe         = firstBeatMs.coerceAtMost(maxSafe)
                    if (safe != firstBeatMs)
                        Log.w(TAG, "SEEK CLAMP: $firstBeatMs → $safe")
                    safe
                } else firstBeatMs
            }
            if (safeFirstBeatMs > 0L) {
                withContext(Dispatchers.Main) { secondaryRef.seekTo(safeFirstBeatMs) }
            }

            // ── Start secondary muted ─────────────────────────────────────────
            withContext(Dispatchers.Main) {
                try {
                    Log.d(TAG, "executeCrossfade: secondary.play() muted " +
                            "(audioSessionId=${secondaryRef.audioSessionId})")
                    secondaryRef.volume = 0f
                    secondaryRef.play()
                } catch (e: Exception) {
                    Log.e(TAG, "executeCrossfade: secondary.play() failed", e)
                }
            }

            // Confirm secondary started; retry once at 500 ms.
            var playWaitMs = 0L
            var secondaryIsPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            while (!secondaryIsPlaying && playWaitMs < 1000L) {
                delay(100L); playWaitMs += 100L
                secondaryIsPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
                if (!secondaryIsPlaying && playWaitMs == 500L) {
                    withContext(Dispatchers.Main) {
                        try {
                            Log.w(TAG, "executeCrossfade: retrying secondary.play() at 500ms")
                            secondaryRef.play()
                        } catch (e: Exception) {
                            Log.e(TAG, "executeCrossfade: retry failed", e)
                        }
                    }
                }
            }
            Log.d(TAG, "executeCrossfade: secondary.isPlaying=$secondaryIsPlaying " +
                    "after ${playWaitMs}ms")
            if (!ready || !secondaryIsPlaying) {
                Log.w(TAG, "executeCrossfade: secondary not fully ready " +
                        "(ready=$ready isPlaying=$secondaryIsPlaying); proceeding with guarded swap")
            }

            // ── Equal-power ramp + optional bass-kill ─────────────────────────
            val stepDelayMs   = (decision.effectiveCrossfadeDurationMs / FADE_STEPS).coerceAtLeast(16L)
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
                                val eq        = android.media.audiofx.Equalizer(0, sessionId)
                                val bassIndex = mixDecisionEngine.findBassBandIndex(eq)
                                if (bassIndex != null) {
                                    eq.enabled = true
                                    eq.setBandLevel(bassIndex, eq.bandLevelRange[0])
                                    bassKillEq = eq
                                    Log.d(TAG, "Bass kill band=$bassIndex at ${(progress * 100).toInt()}%")
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
                    if (step % 10 == 0) {
                        Log.d(TAG, "executeCrossfade: step=$step progress=${"%.2f".format(progress)} " +
                                "primaryVol=${"%.3f".format(cos(angle) * primaryBaseVolume)} " +
                                "secondaryVol=${"%.3f".format(sin(angle) * secondaryBaseVolume)}")
                    }
                    primaryRef.volume   = cos(angle) * primaryBaseVolume
                    secondaryRef.volume = sin(angle) * secondaryBaseVolume
                }
                _state.update { it.copy(crossfadeProgressFraction = sin(angle)) }
                delay(stepDelayMs)
            }

            // ── Abort recovery ────────────────────────────────────────────────
            if (abortCrossfade) {
                Log.d(TAG, "executeCrossfade: ABORTED — restoring primary")
                secondaryProcessor()?.resetRatio()
                withContext(Dispatchers.Main) {
                    primaryRef.volume = primaryBaseVolume
                    try { secondaryRef.pause(); secondaryRef.volume = 0f }
                    catch (e: Exception) { Log.w(TAG, "abort: pause secondary failed: ${e.message}") }
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── Finalise player swap ──────────────────────────────────────────
            var finalSecondaryIsPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            if (!finalSecondaryIsPlaying) {
                var graceMs = 0L
                while (graceMs < 500L && !finalSecondaryIsPlaying) {
                    delay(50L); graceMs += 50L
                    finalSecondaryIsPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
                }
                Log.d(TAG, "executeCrossfade: grace period finalIsPlaying=$finalSecondaryIsPlaying " +
                        "after ${graceMs}ms")
            }

            var primaryWasPaused = false
            withContext(Dispatchers.Main) {
                try {
                    if (finalSecondaryIsPlaying) {
                        primaryRef.pause()
                        primaryWasPaused = true
                        primaryRef.volume = primaryBaseVolume
                        Log.d(TAG, "executeCrossfade: primary paused (audioSessionId=${primaryRef.audioSessionId})")
                    } else {
                        Log.e(TAG, "executeCrossfade: secondary failed — keeping primary")
                        primaryRef.volume = primaryBaseVolume
                        try { secondaryRef.play() }
                        catch (e: Exception) { Log.e(TAG, "final secondary.play() failed", e) }
                    }
                    if (secondaryRef.isPlaying) {
                        secondaryRef.volume = secondaryBaseVolume
                        Log.d(TAG, "executeCrossfade: secondary at baseVolume=$secondaryBaseVolume")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "executeCrossfade: finalisation error: ${e.message}")
                }
            }

            val swapped = withContext(Dispatchers.Main) {
                if (secondaryRef.isPlaying) { isPrimaryA = !isPrimaryA; true } else false
            }

            if (!swapped) {
                Log.e(TAG, "executeCrossfade: swap failed — primaryWasPaused=$primaryWasPaused")
                if (primaryWasPaused) {
                    withContext(Dispatchers.Main) {
                        try {
                            primaryRef.volume = primaryBaseVolume
                            primaryRef.play()
                            Log.w(TAG, "executeCrossfade: primary restarted after swap failure")
                        } catch (e: Exception) {
                            Log.e(TAG, "executeCrossfade: primary restart failed", e)
                        }
                    }
                }
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // Re-assert audio focus: the new primary (possibly PlayerB) may not have it.
            withContext(Dispatchers.Main) {
                try {
                    primaryPlayer()?.play()
                    Log.d(TAG, "executeCrossfade: audio focus re-asserted on new primary")
                } catch (e: Exception) {
                    Log.w(TAG, "executeCrossfade: audio focus re-assertion failed: ${e.message}")
                }
            }

            lastRequestedTrackId      = null
            prebufferedTrackId        = null
            lastPrebufferRequestedId  = null
            currentTrackMixOutMs      = null
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + decision.effectiveCrossfadeDurationMs

            _state.update {
                it.copy(currentTrack = nextTrack, isPlaying = true,
                    isCrossfading = false, crossfadeProgressFraction = 0f)
            }
            Log.d(TAG, "executeCrossfade: COMPLETE strategy=${decision.strategy.name} " +
                    "newPrimaryIsA=$isPrimaryA")

            // ── Post-crossfade tempo snap ─────────────────────────────────────
            if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                withContext(Dispatchers.Main) {
                    try { primaryProcessor()?.resetRatio(); Log.d(TAG, "Tempo snap-back to 1.0") }
                    catch (e: Exception) { Log.w(TAG, "Tempo snap-back failed: ${e.message}") }
                }
            }

            // ── Execute pending track ─────────────────────────────────────────
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                Log.d(TAG, "executeCrossfade: executing pendingNextTrack id=${pending.audioFile.id}")
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading)
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            try { bassKillEq?.release(); bassKillEq = null }
            catch (e: Exception) { Log.e(TAG, "EQ release failed", e) }
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
                    val mixAt = if (useHalfwayMix) {
                        // Offset halfway by the portion skipped at the start (cue-in point).
                        (duration / 2L) + currentTrackFirstBeatMs
                    } else {
                        maxTrackDurationMs
                    }
                    position >= mixAt && remaining > crossfadeDurationMs
                } else false

                if (inPrebufferZone && !_state.value.isCrossfading
                    && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                        Log.d(TAG, "monitor: prebufferRequest id=$id remaining=$remaining")
                    }
                }

                val shouldTrigger = playing && !_state.value.isCrossfading
                        && !postGuardActive && duration > 0L && (
                        customMixOut ||
                                ((inTriggerZone || isMaxTime) && isOnBeat && (isAtPhrase || mustTrigger))
                        )

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.d(TAG, "monitor: nextTrackRequest id=$id remaining=$remaining " +
                                "isOnBeat=$isOnBeat isAtPhrase=$isAtPhrase customMixOut=$customMixOut")
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
                    it.copy(currentPositionMs = position, currentDurationMs = duration,
                        timeToNextMixMs = timeToNextMixMs)
                }

                delay(if (inTriggerZone || inPrebufferZone || isMaxTime || customMixOut)
                    FAST_POLL_MS else POSITION_POLL_MS)
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
        val realBands = primaryWaveformProcessor()?.getBands()
        return if (realBands != null && realBands.any { it > 0.01f }) {
            List(WAVEFORM_BARS) { i ->
                val srcIdx = (i.toFloat() / WAVEFORM_BARS * realBands.size)
                    .toInt().coerceIn(0, realBands.size - 1)
                val raw = realBands[srcIdx]
                // Treble bands naturally carry less energy. Log-based gain curve
                // compensates so all bars animate visibly.
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
            Log.d(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}).onIsPlayingChanged: " +
                    "isPlaying=$isPlaying isPrimary=$isPrimary")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_IDLE      -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY     -> "READY"
                Player.STATE_ENDED     -> "ENDED"
                else                   -> "UNKNOWN"
            }
            Log.d(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}).onPlaybackStateChanged: $name")
            if (playbackState == Player.STATE_ENDED) {
                Log.w(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}) STATE_ENDED")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}).onPlayerError: ${error.message}", error)
            _state.update { it.copy(error = "Player error: ${error.message}") }
        }
    }
}