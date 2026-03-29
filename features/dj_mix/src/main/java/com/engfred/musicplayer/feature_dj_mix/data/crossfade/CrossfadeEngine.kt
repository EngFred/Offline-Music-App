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
 * Split into three files for maintainability:
 * - [CrossfadeModels]              — data types (CrossfadeEngineState, MixStrategy, MixDecision)
 * - [MixDecisionEngine]            — pure BPM classification + bass-kill EQ logic
 * - [DrcSuppressingMediaCodecAdapterFactory] — three-layer AAC DRC suppression
 * - [CrossfadeEngine]              — this file: player lifecycle, crossfade, position monitoring
 *
 * Robustness notes:
 * - startPlayback() always resets to PlayerA (audio focus holder) and clears stale
 * crossfade jobs, prebuffer state, and the nextTrackRequest replay cache to prevent
 * a spurious immediate crossfade on fresh session start.
 * - A 5-second postCrossfadeGuard is applied after startPlayback so the position
 * monitor cannot trigger an auto-crossfade before the track has a chance to play.
 * - executeCrossfade() clamps the incoming seek point so the incoming track cannot
 * end mid-fade, re-asserts audio focus after a player swap, and recovers the
 * primary if the secondary swap fails.
 *
 * BT/headphone cold-start stall fix:
 * When BT or wired headphones are connected, the very first AudioTrack created for
 * PlayerA races against the BT HAL codec negotiation. The AudioTrack enters a frozen
 * state: isPlaying=true but getPlaybackHeadPosition()=0 forever. This is a one-time
 * cold-start issue — once PlayerA establishes the BT session, all subsequent
 * AudioTracks (including PlayerB and any restart) initialise instantly into the warm
 * session.
 *
 * Fix (stallRecoveryCount):
 * On STALL_CONFIRMED the position monitor performs ONE same-track restart instead of
 * immediately crossfading away. By the time the stall is confirmed (~4 s in), the BT
 * session is provably warm (READY+isPlaying already fired). The fresh AudioTrack from
 * the restart joins the warm session — identical to why PlayerB never stalls. If the
 * restart itself stalls (exotic device), it escalates to crossfade as before.
 *
 * AAC DRC headphone/Bluetooth AudioSink reconfigure fix:
 * Both ExoPlayers are built with [DrcSuppressingMediaCodecAdapterFactory] which
 * intercepts AAC DRC parameter renegotiation at three layers, preventing the
 * mid-playback AudioSink reconfigure + AudioTrack recreation that can also cause
 * position to freeze on headphone/Bluetooth output with certain AAC tracks.
 * (Layer 1C confirmed working in logs: "✅ Suppressed DRC-only format change!")
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mixDecisionEngine: MixDecisionEngine
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        private const val POSITION_POLL_MS          = 300L
        private const val FAST_POLL_MS              = 50L
        private const val WAVEFORM_POLL_MS          = 16L
        private const val FADE_STEPS                = 60
        private const val CROSSFADE_GUARD_MS        = 200L
        private const val BEAT_SNAP_WINDOW_MS       = 25L
        private const val PHRASE_BARS               = 8
        private const val BARS_PER_BEAT_MULTIPLE    = 4
        private const val WAVEFORM_BARS             = 32

        // ── Stall detector constants ──────────────────────────────────────────
        // After a track starts, how long we wait before treating position=0 as a stall.
        // Must be longer than normal startup latency (typically <1s) but short enough
        // for the user not to notice silence. 2.5s is safe across all devices tested.
        private const val STALL_STARTUP_GRACE_MS    = 2_500L

        // How long position must be continuously stuck at 0 (after the grace period)
        // before we declare a stall and trigger recovery.
        private const val STALL_CONFIRM_MS          = 1_500L
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

    private fun primaryPlayer()            = if (isPrimaryA) playerA            else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB            else playerA
    private fun primaryProcessor()         = if (isPrimaryA) processorA         else processorB
    private fun secondaryProcessor()       = if (isPrimaryA) processorB         else processorA
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

    @Volatile private var currentTrackBpm: Float          = 0f
    @Volatile private var currentTrackFirstBeatMs: Long   = 0L
    @Volatile private var currentTrackBaseVolume: Float   = 1.0f
    @Volatile private var currentTrackAmplitude: Float    = 0f
    @Volatile private var currentWaveformEnvelope: FloatArray = FloatArray(0)
    @Volatile private var currentTrackMixOutMs: Long?     = null
    @Volatile private var postCrossfadeGuardUntilMs: Long = 0L

    // ── Stall detector state ──────────────────────────────────────────────────
    // lastPlaybackStartMs: set each time a track actually starts playing
    //   (startPlayback, stall recovery restart, or after a successful crossfade swap).
    //   The position monitor uses this to compute the startup grace period.
    //
    // stallRecoveryCount: how many same-track restart attempts have been made for the
    //   current stall event. Capped at 1. On the first confirmed stall the engine
    //   restarts the same track (BT session is warm by then). If the restart itself
    //   stalls, stallRecoveryCount == 1 and the engine escalates to crossfade.
    //   Reset to 0 on startPlayback() and after every successful crossfade swap so
    //   each new track gets exactly one restart attempt if needed.
    @Volatile private var lastPlaybackStartMs: Long = 0L
    @Volatile private var stallRecoveryCount: Int   = 0

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
            lastPlaybackStartMs = 0L
            stallRecoveryCount  = 0
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
        Log.d(TAG, "initialize: ready — RubberBand=${processorA != null}, " +
                "DrcSuppression=ACTIVE on both players")
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
                // Inject drcFactory directly into MediaCodecAudioRenderer.
                // Do NOT call super — that would add a second, unfactored renderer.
                out.add(
                    MediaCodecAudioRenderer(
                        context,
                        drcFactory,          // ← DRC suppression wired here
                        mediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        audioSink            // uses our custom AudioSink with processors
                    )
                )
            }
        }

        Log.d(TAG, "buildExoPlayer(${if (isPlayerA) "A" else "B"}): " +
                "handleAudioFocus=$handleAudioFocus DrcSuppression=ACTIVE")

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

        // ── Record when playback actually starts for stall detection ──────────
        // Set immediately (not inside the coroutine) so the stall grace period
        // begins counting from this moment, not from when the coroutine is scheduled.
        lastPlaybackStartMs = System.currentTimeMillis()
        stallRecoveryCount  = 0
        Log.d(TAG, "startPlayback: id=${audioFile.id} uri=${audioFile.uri}")

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

        // Guard the position monitor for 5 s so it cannot fire an auto-crossfade before
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
                Log.d(TAG, "startPlayback: configuring PlayerA for id=${audioFile.id} " +
                        "audioSessionId=${primary.audioSessionId}")
                primary.stop()
                primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.prepare()
                primary.play()
                Log.d(TAG, "startPlayback: play() called — playbackState=${primary.playbackState} " +
                        "isPlaying=${primary.isPlaying} audioSessionId=${primary.audioSessionId}")
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
                "amplitude=$amplitude mixOutMs=$mixOutMs baseVolume=$currentTrackBaseVolume")
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
                Log.d(TAG, "prebufferTrack: preparing id=${audioFile.id} " +
                        "audioSessionId=${secondary.audioSessionId}")
                secondary.stop(); secondary.clearMediaItems()
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f; secondary.prepare()
                // Seek is applied here speculatively; executeCrossfade will clamp it safely.
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                Log.d(TAG, "prebufferTrack: prepared. audioSessionId=${secondary.audioSessionId}")
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
            Log.d(TAG, "queueNextTrack: queued pendingNextTrack id=${audioFile.id} " +
                    "(crossfade already in progress)")
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
     * 1. Classify BPM pair → MixDecision (strategy, duration, tempo-sync, bass-kill point).
     * 2. Prepare secondary player (or resume pre-buffered track).
     * 3. Clamp seek: if firstBeatMs leaves < 2× fade duration before track end, pull back.
     * 4. Start secondary muted; wait for it to actually begin playing.
     * 5. Equal-power sin/cos volume ramp with optional bass-kill EQ on outgoing track.
     * 6. Finalise player swap; re-assert audio focus on new primary.
     * 7. If swap fails (secondary stopped), restart primary to avoid total silence.
     * 8. Execute any pendingNextTrack queued during the crossfade.
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
                    Log.d(TAG, "executeCrossfade: preparing secondary for id=${nextTrack.id} " +
                            "audioSessionId=${secondaryRef.audioSessionId}")
                    secondaryRef.stop(); secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f; secondaryRef.prepare()
                } else {
                    Log.d(TAG, "executeCrossfade: using prebuffered id=${nextTrack.id} " +
                            "audioSessionId=${secondaryRef.audioSessionId}")
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
                    "playbackState=${withContext(Dispatchers.Main) { secondaryRef.playbackState }}")

            // ── Seek clamp ────────────────────────────────────────────────────
            val safeFirstBeatMs: Long = withContext(Dispatchers.Main) {
                val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                Log.d(TAG, "SEEK DIAGNOSTIC: id=${nextTrack.id} duration=${secDuration}ms " +
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
                            "audioSessionId=${secondaryRef.audioSessionId}")
                    secondaryRef.volume = 0f
                    secondaryRef.play()
                } catch (e: Exception) {
                    Log.e(TAG, "executeCrossfade: secondary.play() failed", e)
                }
            }

            // Confirm secondary started; retry once at 500ms.
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
                        "(ready=$ready isPlaying=$secondaryIsPlaying) — proceeding with guarded swap")
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
                                    Log.d(TAG, "Bass kill: band=$bassIndex " +
                                            "at ${(progress * 100).toInt()}% sessionId=$sessionId")
                                } else {
                                    eq.release()
                                    Log.w(TAG, "Bass kill: no suitable bass band found — skipped")
                                }
                            } else {
                                Log.w(TAG, "Bass kill: AUDIO_SESSION_ID_UNSET — skipped")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Bass kill EQ failed (non-fatal): ${e.message}")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (step % 10 == 0) {
                        Log.d(TAG, "executeCrossfade: step=$step/$FADE_STEPS " +
                                "progress=${"%.2f".format(progress)} " +
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
                Log.w(TAG, "executeCrossfade: ABORTED — restoring primary volume")
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
                        Log.d(TAG, "executeCrossfade: primary paused " +
                                "audioSessionId=${primaryRef.audioSessionId}")
                    } else {
                        Log.e(TAG, "executeCrossfade: secondary failed — keeping primary active")
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
                Log.e(TAG, "executeCrossfade: swap FAILED — primaryWasPaused=$primaryWasPaused")
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
                    Log.d(TAG, "executeCrossfade: audio focus re-asserted on new primary " +
                            "isPrimaryA=$isPrimaryA audioSessionId=${primaryPlayer()?.audioSessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "executeCrossfade: audio focus re-assertion failed: ${e.message}")
                }
            }

            // ── Reset stall detector for the new primary ──────────────────────
            // The new primary just started playing after the crossfade swap.
            // Give it a fresh grace period and a fresh recovery budget.
            lastPlaybackStartMs = System.currentTimeMillis()
            stallRecoveryCount  = 0
            Log.d(TAG, "executeCrossfade: stall detector reset for new primary " +
                    "isPrimaryA=$isPrimaryA")

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
                    "newPrimaryIsA=$isPrimaryA nextTrack='${nextTrack.title}'")

            // ── Post-crossfade tempo snap ─────────────────────────────────────
            if (decision.shouldTempoSync && decision.stretchRatio != 1.0) {
                withContext(Dispatchers.Main) {
                    try {
                        primaryProcessor()?.resetRatio()
                        Log.d(TAG, "Tempo snap-back to 1.0 on new primary")
                    } catch (e: Exception) {
                        Log.w(TAG, "Tempo snap-back failed: ${e.message}")
                    }
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

    /**
     * BT/headphone stall recovery: loads the SAME track onto the secondary player
     * and swaps it in as the new primary.
     *
     * The frozen AudioTrack belongs to the primary player instance. ExoPlayer reuses
     * the AudioTrack across stop/prepare cycles on the same player, so restarting on
     * the primary always hits the same frozen instance.
     *
     * The secondary player has a completely separate AudioTrack. By the time the stall
     * is confirmed (~4s in), the BT HAL has already completed its cold-start codec
     * negotiation (the primary received READY+isPlaying before freezing). The secondary's
     * fresh AudioTrack joins the now-warm session — no negotiation delay, no freeze.
     *
     * This is exactly why "Mix Now" always works: PlayerB's AudioTrack is always fresh.
     */
    private suspend fun performSameTrackRecoveryOnSecondary(track: AudioFile) {
        val frozenPrimary = primaryPlayer()
        val freshSecondary = secondaryPlayer()
        if (frozenPrimary == null || freshSecondary == null) {
            Log.e(TAG, "sameTrackRecovery: players null — escalating to crossfade")
            _nextTrackRequest.tryEmit(track.id)
            return
        }
        _state.update { it.copy(isCrossfading = true, crossfadeProgressFraction = 0f) }

        // ── Step 1: silence the frozen primary, load same track on secondary ─────
        withContext(Dispatchers.Main) {
            try { frozenPrimary.pause() } catch (e: Exception) { /* frozen — may throw */ }
            frozenPrimary.volume = 0f
            freshSecondary.stop()
            freshSecondary.clearMediaItems()
            freshSecondary.setMediaItem(MediaItem.fromUri(track.uri))
            freshSecondary.volume = 0f
            freshSecondary.prepare()
            Log.d(TAG, "sameTrackRecovery: loading id=${track.id} on secondary " +
                    "audioSessionId=${freshSecondary.audioSessionId} " +
                    "(frozen primary audioSessionId=${frozenPrimary.audioSessionId})")
        }

        // ── Step 2: wait for secondary READY (up to 5s) ──────────────────────────
        var waitMs = 0L
        var ready = false
        while (waitMs < 5_000L) {
            ready = withContext(Dispatchers.Main) {
                freshSecondary.playbackState == Player.STATE_READY &&
                        freshSecondary.currentMediaItem != null
            }
            if (ready) break
            delay(100L); waitMs += 100L
        }

        if (!ready) {
            Log.e(TAG, "sameTrackRecovery: secondary not ready after 5s — escalating to crossfade")
            withContext(Dispatchers.Main) {
                frozenPrimary.volume = currentTrackBaseVolume
                try { frozenPrimary.play() } catch (e: Exception) { /* ignore */ }
            }
            _state.update { it.copy(isCrossfading = false) }
            // Fall through to crossfade as last resort
            if (track.id != lastRequestedTrackId) {
                lastRequestedTrackId = track.id
                _nextTrackRequest.tryEmit(track.id)
            }
            return
        }

        // ── Step 3: start secondary (still muted) ────────────────────────────────
        withContext(Dispatchers.Main) {
            freshSecondary.volume = 0f
            try { freshSecondary.play() } catch (e: Exception) {
                Log.e(TAG, "sameTrackRecovery: secondary.play() failed: ${e.message}")
            }
        }

        // Wait up to 2s for isPlaying=true
        var playWaitMs = 0L
        var secondaryPlaying = false
        while (playWaitMs < 2_000L) {
            secondaryPlaying = withContext(Dispatchers.Main) { freshSecondary.isPlaying }
            if (secondaryPlaying) break
            delay(50L); playWaitMs += 50L
        }

        if (!secondaryPlaying) {
            Log.e(TAG, "sameTrackRecovery: secondary won't start after ${playWaitMs}ms " +
                    "— escalating to crossfade")
            withContext(Dispatchers.Main) {
                frozenPrimary.volume = currentTrackBaseVolume
                try { frozenPrimary.play() } catch (e: Exception) { /* ignore */ }
            }
            _state.update { it.copy(isCrossfading = false) }
            if (track.id != lastRequestedTrackId) {
                lastRequestedTrackId = track.id
                _nextTrackRequest.tryEmit(track.id)
            }
            return
        }

        Log.i(TAG, "sameTrackRecovery: secondary isPlaying=true after ${playWaitMs}ms " +
                "audioSessionId=${freshSecondary.audioSessionId} — performing swap")

        // ── Step 4: quick 300ms fade-in on secondary, fade-out frozen primary ────
        val fadeSteps = 6
        for (step in 1..fadeSteps) {
            val progress = step.toFloat() / fadeSteps
            withContext(Dispatchers.Main) {
                freshSecondary.volume = progress * currentTrackBaseVolume
                frozenPrimary.volume  = (1f - progress) * currentTrackBaseVolume
            }
            delay(50L)
        }

        // ── Step 5: commit swap ───────────────────────────────────────────────────
        withContext(Dispatchers.Main) {
            isPrimaryA = !isPrimaryA   // secondary is now primary
            try { frozenPrimary.stop() } catch (e: Exception) { /* ignore */ }
            frozenPrimary.volume = 0f
            // Re-request audio focus on the new primary
            try {
                primaryPlayer()?.play()
                Log.d(TAG, "sameTrackRecovery: ✅ COMPLETE — id=${track.id} now on " +
                        "${if (isPrimaryA) "PlayerA" else "PlayerB"} " +
                        "audioSessionId=${primaryPlayer()?.audioSessionId}")
            } catch (e: Exception) {
                Log.w(TAG, "sameTrackRecovery: audio focus re-assertion failed: ${e.message}")
            }
        }

        // ── Bookkeeping: same track, fresh grace period ───────────────────────────
        lastPlaybackStartMs       = System.currentTimeMillis()
        postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
        lastRequestedTrackId      = null
        prebufferedTrackId        = null
        // currentTrack intentionally unchanged — same song, same queue position
        _state.update {
            it.copy(
                isPlaying                 = true,
                isCrossfading             = false,
                crossfadeProgressFraction = 0f
                // currentTrack stays the same
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═════════════════════════════════════════════════════════════════════════

    private fun startPositionMonitoring() {
        positionMonitorJob?.cancel()
        positionMonitorJob = engineScope.launch {

            // ── Stall detector local state ────────────────────────────────────
            // stallSinceMs: timestamp when we first observed playing=true + position=0
            //               after the startup grace period. Reset to 0 when position
            //               advances or conditions clear.
            var stallSinceMs = 0L

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
                        (duration / 2L) + currentTrackFirstBeatMs
                    } else {
                        maxTrackDurationMs
                    }
                    position >= mixAt && remaining > crossfadeDurationMs
                } else false

                // ── Pre-buffer request ────────────────────────────────────────
                if (inPrebufferZone && !_state.value.isCrossfading
                    && prebufferedTrackId == null && !isPrebufferingInProgress) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                        Log.d(TAG, "monitor: prebufferRequest id=$id remaining=$remaining")
                    }
                }

                // ── Normal crossfade trigger ──────────────────────────────────
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
                        Log.d(TAG, "monitor: nextTrackRequest id=$id remaining=${remaining}ms " +
                                "inTriggerZone=$inTriggerZone isMaxTime=$isMaxTime " +
                                "isOnBeat=$isOnBeat isAtPhrase=$isAtPhrase " +
                                "customMixOut=$customMixOut mustTrigger=$mustTrigger")
                    }
                }

                // ── Stall detector ────────────────────────────────────────────
                //
                // Detects the BT/headphone cold-start AudioTrack HAL freeze:
                //   isPlaying=true but getPlaybackHeadPosition()=0 indefinitely.
                //
                // This is a one-time cold-start problem. By the time STALL_CONFIRMED
                // fires (~4s in), the BT session is provably warm — READY+isPlaying
                // already fired before the stall candidate was even logged. A fresh
                // stop/prepare/play cycle creates a new AudioTrack that joins the
                // already-established BT session cleanly, identical to why PlayerB
                // never stalls.
                //
                // Recovery flow:
                //   1st confirmed stall → restart same track (stallRecoveryCount = 0→1)
                //                         fresh grace period set so monitor won't
                //                         immediately re-trigger on the restarted track
                //   2nd confirmed stall → stallRecoveryCount == 1 → escalate to crossfade
                //
                // Conditions checked:
                //  • playing=true          — the player thinks it's running
                //  • position == 0         — but AudioTrack is frozen (HAL init race)
                //  • duration > 0          — track is loaded (not just between tracks)
                //  • not crossfading       — don't interrupt an active crossfade
                //  • startup grace passed  — exclude normal slow-start on spinning storage
                //  • sustained for 1500ms  — exclude transient false reads at poll boundaries
                val startupGracePassed = System.currentTimeMillis() - lastPlaybackStartMs > STALL_STARTUP_GRACE_MS
                val stallCandidate = playing
                        && position == 0L
                        && duration > 0L
                        && !_state.value.isCrossfading
                        && startupGracePassed

                if (stallCandidate) {
                    if (stallSinceMs == 0L) {
                        stallSinceMs = System.currentTimeMillis()
                        Log.w(TAG, "monitor: STALL CANDIDATE detected — position=0 with " +
                                "playing=true after grace. duration=${duration}ms. " +
                                "stallRecoveryCount=$stallRecoveryCount. " +
                                "Waiting ${STALL_CONFIRM_MS}ms to confirm...")
                    }
                } else {
                    // Position advanced or conditions cleared — reset the stall timer.
                    if (stallSinceMs > 0L && position > 0L) {
                        Log.d(TAG, "monitor: stall candidate cleared — position advanced to ${position}ms")
                    }
                    stallSinceMs = 0L
                }

                val stallConfirmed = stallCandidate
                        && stallSinceMs > 0L
                        && System.currentTimeMillis() - stallSinceMs > STALL_CONFIRM_MS

                if (stallConfirmed) {
                    stallSinceMs = 0L  // Reset so we don't re-fire immediately.
                    val stalledTrack = _state.value.currentTrack

                    if (stalledTrack != null && stallRecoveryCount < 1) {
                        stallRecoveryCount++
                        // Fresh grace period so the monitor doesn't immediately re-fire
                        // while the secondary is buffering.
                        lastPlaybackStartMs = System.currentTimeMillis()

                        Log.w(TAG, "monitor: STALL CONFIRMED — BT/headphone HAL freeze on " +
                                "audioSessionId=${primaryPlayer()?.audioSessionId}. " +
                                "Loading same track onto SECONDARY player (different AudioTrack). " +
                                "The frozen AudioTrack is per-instance — secondary is unaffected. " +
                                "attempt=$stallRecoveryCount id=${stalledTrack.id}")

                        // We launch a coroutine here so the monitor loop can keep running
                        // (and its stallSinceMs stays reset) while we wait for READY.
                        crossfadeJob?.cancel()
                        crossfadeJob = engineScope.launch {
                            performSameTrackRecoveryOnSecondary(stalledTrack)
                        }

                    } else {
                        // ── Escalate: crossfade away ──────────────────────────
                        // Either the restart itself stalled (stallRecoveryCount == 1)
                        // or currentTrack is null. Move on to the next track.
                        val id = stalledTrack?.id
                        if (id != null && id != lastRequestedTrackId) {
                            lastRequestedTrackId = id
                            Log.e(TAG, "monitor: ❌ STALL recovery exhausted " +
                                    "(stallRecoveryCount=$stallRecoveryCount) — " +
                                    "crossfading away from id=$id. " +
                                    "Device BT HAL may not release the frozen AudioTrack.")
                            _nextTrackRequest.tryEmit(id)
                        } else if (stalledTrack == null) {
                            Log.e(TAG, "monitor: STALL CONFIRMED but currentTrack is null — " +
                                    "cannot recover")
                        } else {
                            Log.w(TAG, "monitor: STALL CONFIRMED but lastRequestedTrackId already " +
                                    "set for id=$id — recovery already in flight")
                        }
                    }
                }

                // ── State update ──────────────────────────────────────────────
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

                delay(
                    if (inTriggerZone || inPrebufferZone || isMaxTime || customMixOut || stallCandidate)
                        FAST_POLL_MS
                    else
                        POSITION_POLL_MS
                )
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
                    "isPlaying=$isPlaying isPrimary=$isPrimary isPrimaryA=$isPrimaryA")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_IDLE      -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY     -> "READY"
                Player.STATE_ENDED     -> "ENDED"
                else                   -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}).onPlaybackStateChanged: $name " +
                    "isPrimaryA=$isPrimaryA")
            if (playbackState == Player.STATE_ENDED) {
                Log.w(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}) STATE_ENDED reached. " +
                        "If this is the primary player, the track finished naturally.")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isPrimary = (isPlayerA && isPrimaryA) || (!isPlayerA && !isPrimaryA)
            Log.e(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}).onPlayerError: " +
                    "${error.message} errorCode=${error.errorCode} isPrimary=$isPrimary", error)
            if (isPrimary) {
                // errorCode=1003 is ExoPlayer's StuckPlayerException — fired when
                // getPlaybackHeadPosition() makes no progress for 10 seconds.
                // This is the exact BT/headphone HAL freeze our stall detector handles
                // at ~4s (well before ExoPlayer's 10s timeout). By the time this
                // callback fires, the position monitor has already issued a restart
                // or crossfade. Surfacing it as a UI error is misleading and causes
                // the user to see an error state on a track that is already recovering.
                if (error.errorCode == 1003) {
                    Log.w(TAG, "PlayerListener(${if (isPlayerA) "A" else "B"}): " +
                            "StuckPlayerException (errorCode=1003) suppressed — " +
                            "stall recovery was already initiated by position monitor " +
                            "${System.currentTimeMillis() - lastPlaybackStartMs}ms ago. " +
                            "stallRecoveryCount=$stallRecoveryCount")
                    return
                }
                _state.update { it.copy(error = "Player error: ${error.message}") }
            }
        }
    }
}