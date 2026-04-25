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

/**
 * Dual-[ExoPlayer] DJ crossfade engine.
 *
 * ── Architecture overview ─────────────────────────────────────────────────────
 * This class is the main orchestrator. Responsibilities deliberately kept OUT of
 * this file to maintain separation of concerns:
 *
 * • Beat-phase math          → [PhaseAlignmentCalculator]  (pure arithmetic, unit-testable)
 * • Android audio focus      → [AudioFocusCoordinator]     (single focus request for both players)
 *
 * ── Dual-player model ─────────────────────────────────────────────────────────
 * playerA and playerB alternate as "primary" (audible) and "secondary" (prebuffered,
 * muted). [isPrimaryA] tracks which is which. After each crossfade completes the
 * roles flip so the next outgoing track is always in the primary slot.
 *
 * ── Audio focus ───────────────────────────────────────────────────────────────
 * BOTH ExoPlayer instances are built with handleAudioFocus = false. [AudioFocusCoordinator]
 * owns the single focus request. When a phone call arrives or another app takes over,
 * BOTH players are paused together, producing clean silence. When focus returns, both
 * players resume if the user did not manually pause in between.
 *
 * ── Play/pause during crossfade ───────────────────────────────────────────────
 * [playPause] coordinates BOTH players when a crossfade is in progress. Previously
 * only the primary player was paused/resumed, leaving the secondary playing silently
 * (or vice-versa). Now both players always move together, regardless of fade state.
 *
 * ── Phase-aligned seek ────────────────────────────────────────────────────────
 * The seek that aligns the incoming track's beat grid now happens AFTER the secondary
 * player is confirmed playing at muted volume. This eliminates the latency bug where
 * phase was calculated when the primary was at position T₀ but the secondary did not
 * start producing audio until T₀ + up to 4 seconds later (ready-wait + play-wait).
 * Because the secondary is already prebuffered and muted, the post-start seek resolves
 * in < 50 ms and is completely inaudible.
 *
 * ── Seek settle (adaptive) ────────────────────────────────────────────────────
 * After seeking the secondary, instead of a fixed 80 ms blind wait, the engine polls
 * the secondary's currentPosition until it reflects the new target (within 150 ms
 * tolerance), or until 200 ms have elapsed. This adapts to device speed: fast devices
 * exit in ~20 ms, slow budget devices wait the full 200 ms cap without cutting off early.
 *
 * ── EQ architecture ───────────────────────────────────────────────────────────
 * Each ExoPlayer has its own [BandEqAudioProcessor] so their audio-thread filter state
 * (delay elements w1/w2) never leaks between pipelines. [applyEqPreset] keeps them
 * in sync whenever the user changes the global EQ preset in Settings.
 *
 * ── Cue-point guard ───────────────────────────────────────────────────────────
 * [BpmAnalyzer] stores RAW beat-0 positions (no minimum-offset guard). The guard is
 * applied here by [applyFirstBeatGuard] using [cuePointOffsetMs] (user-configurable,
 * 0–30 s). Every raw firstBeatMs that enters the engine passes through this guard
 * exactly once. Internal references to [currentTrackFirstBeatMs] always hold the
 * already-guarded value — never call the guard on it again.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * ALL ExoPlayer method calls (play, pause, seekTo, volume, audioSessionId, etc.)
 * MUST execute inside `withContext(Dispatchers.Main)`. Calling them on any other
 * dispatcher throws [IllegalStateException] and crashes the app.
 *
 * ── Phone-call / audio-focus interruption during crossfade ───────────────────
 * Two bugs that existed before and are now resolved:
 *
 * Issue 1 — "Crossfade Ghost" (Step 6):
 * If a phone call arrived mid-fade, [handleFocusLost] paused both ExoPlayers and
 * set _state.value.isPlaying = false, but the equal-power for-loop kept advancing
 * its math through delay() calls with no awareness of the pause. When the call
 * ended 2+ minutes later the fade had long since "finished" in the coroutine —
 * but nothing actually faded audibly, causing an abrupt track jump.
 * A while(!isPlaying) suspension wall at the top of each loop iteration
 * parks the math until [handleFocusGain] flips isPlaying back to true.
 *
 * Issue 2 — "Broken swap" (Step 8):
 * The swap was gated on `secondaryRef.isPlaying`, which is an ExoPlayer runtime
 * state. If the call arrived before the swap, isPlaying was false and the engine
 * fell into the recovery branch — resuming the OLD primary and abandoning the
 * secondary permanently, breaking the queue.
 * The swap is now unconditional (the fade loop completed successfully, so
 * the track hand-off MUST happen). Only the decision to call play() after the
 * swap is conditioned on _state.value.isPlaying, correctly reflecting whether
 * the user/system wants audio at that moment.
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

        private const val CROSSFADE_DURATION_MULT = 1.60f

        /** Normal position-poll interval when not approaching a transition. */
        private const val POSITION_POLL_MS = 300L

        /** Fast-poll interval when inside the trigger window or prebuffer zone. */
        private const val FAST_POLL_MS = 50L

        /** Waveform capture poll rate (~60 fps). */
        private const val WAVEFORM_POLL_MS = 16L

        /** Number of volume steps in the equal-power fade loop. */
        private const val FADE_STEPS = 60

        /**
         * Minimum ms remaining before a re-trigger of an already-started crossfade
         * is suppressed. Prevents back-to-back crossfades from firing in the same window.
         */
        private const val CROSSFADE_GUARD_MS = 200L

        /** Beat-snap window: how close to a beat boundary the trigger must land (ms). */
        private const val BEAT_SNAP_WINDOW_MS = 25L

        /** Number of 4-beat bars that make one "phrase" for phrase-alignment purposes. */
        private const val PHRASE_BARS = 8

        /** Number of beats per bar (standard 4/4 time). */
        private const val BARS_PER_BEAT_MULTIPLE = 4

        /** Number of frequency bands in the real-time waveform display. */
        private const val WAVEFORM_BARS = 32

        /**
         * In Continuous Play mode (!isRealMixMode), start the crossfade this many ms
         * before the track ends (gives the secondary player time to buffer and start).
         */
        private const val CONTINUOUS_TRIGGER_WINDOW_MS = 12_000L

        /**
         * In Continuous Play mode, request prebuffering this many ms before the end.
         * Generous window ensures secondary is STATE_READY well before it is needed.
         */
        private const val CONTINUOUS_PREBUFFER_WINDOW_MS = 30_000L

        /**
         * Safety cap on the phase-advance loop inside [applyFirstBeatGuard].
         * 1 000 iterations at 120 BPM = 500 s — far beyond any real track.
         */
        private const val GUARD_MAX_ITERATIONS = 1_000

        /**
         * How long to wait for the secondary's currentPosition to confirm the post-start
         * seek has settled. Budget devices may need the full 200 ms; fast devices exit in ~20 ms.
         * See the adaptive settle loop in [executeCrossfade] Step 4.
         */
        private const val SEEK_SETTLE_DEADLINE_MS = 200L

        /**
         * Tolerance for the seek-settle check (ms). ExoPlayer seeks local MP3/AAC to the
         * nearest codec sync frame, which can be up to ~150 ms from the requested target.
         * Declaring "settled" when we are within this tolerance prevents a false timeout.
         */
        private const val SEEK_SETTLE_TOLERANCE_MS = 150L

        /**
         * Poll interval (ms) used by the Step-6 suspension wall to re-check whether
         * playback has resumed after a phone-call / audio-focus interruption.
         * 50 ms gives near-instant wake-up without busy-waiting.
         */
        private const val FOCUS_RESUME_POLL_MS = 50L
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COROUTINE SCOPE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Engine-scoped coroutine scope. Recreated on [initialize] after a [release]
     * so that a re-initialized engine starts with a fresh, uncancelled scope.
     */
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ═════════════════════════════════════════════════════════════════════════
    // AUDIO FOCUS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Centralized audio focus manager for both ExoPlayer instances.
     *
     * Initialized lazily so [context] is available at construction time but the
     * AudioManager is not touched until [startPlayback] calls [AudioFocusCoordinator.request].
     *
     * See [AudioFocusCoordinator] for full documentation on why both players use
     * handleAudioFocus = false and this coordinator exists.
     */
    private val audioFocusCoordinator: AudioFocusCoordinator by lazy {
        AudioFocusCoordinator(
            context  = context,
            listener = object : AudioFocusCoordinator.Listener {
                /**
                 * Permanent focus loss (phone call accepted, another music app started).
                 * Pause both players. We flag that we were interrupted so [onFocusGained]
                 * knows to auto-resume.
                 */
                override fun onFocusLost() = handleFocusLost()

                /**
                 * Transient focus loss (incoming call ringing, navigation prompt).
                 * Same handling as permanent loss — both players pause cleanly.
                 */
                override fun onFocusLostTransient() = handleFocusLost()

                /**
                 * Focus has been returned. Resume both players only if we were interrupted
                 * by the system, not if the user manually paused during the interruption.
                 */
                override fun onFocusGained() = handleFocusGain()
            }
        )
    }

    /**
     * True when playback was paused because the system took audio focus (phone call,
     * navigation, etc.) and playback should auto-resume when focus is returned.
     *
     * Set to false when the user manually pauses, so we don't auto-resume on focus gain
     * after a user-initiated pause that happened to coincide with a focus loss.
     */
    @Volatile private var resumeAfterFocusGain: Boolean = false

    // ═════════════════════════════════════════════════════════════════════════
    // PLAYERS & PROCESSORS
    // ═════════════════════════════════════════════════════════════════════════

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var waveformProcessorA: WaveformCaptureAudioProcessor? = null
    private var waveformProcessorB: WaveformCaptureAudioProcessor? = null

    /**
     * Per-player EQ processors. Each player gets its own instance because both run
     * on independent audio threads. Sharing a single [BandEqAudioProcessor] would
     * cause filter-state corruption (the biquad delay elements w1/w2 written by one
     * thread would be immediately clobbered by the other).
     *
     * Kept in sync by [applyEqPreset] so the global EQ preset applies to both players.
     */
    private var eqProcessorA: BandEqAudioProcessor? = null
    private var eqProcessorB: BandEqAudioProcessor? = null

    /** True when playerA is the current primary (audible) player. */
    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()            = if (isPrimaryA) playerA            else playerB
    private fun secondaryPlayer()          = if (isPrimaryA) playerB            else playerA
    private fun primaryWaveformProcessor() = if (isPrimaryA) waveformProcessorA else waveformProcessorB

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC STATE
    // ═════════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow(CrossfadeEngineState())

    /** Observed by the ViewModel and the UI. Emit-rate varies from 16 ms (waveform) to 300 ms (idle). */
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    /**
     * Emits the current track's ID when the engine determines a transition should start.
     * [AutoMixService] collects this and calls [queueNextTrack] with the selected next track.
     */
    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    /**
     * Emits the current track's ID when the engine wants the next track prebuffered.
     * Prebuffering happens in advance so the secondary player is STATE_READY before
     * the crossfade starts, dramatically reducing the ready-wait in [executeCrossfade].
     */
    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // SETTINGS (written by AutoMixService / MixStudioViewModel)
    // ═════════════════════════════════════════════════════════════════════════

    /** Duration of the volume-ramp overlap between outgoing and incoming tracks. */
    var crossfadeDurationMs: Long = 5_000L

    /**
     * When true: Auto-Mix mode. Crossfades are phase-aligned, bass-killed, and
     * triggered at the musical halfway point.
     * When false: Continuous Play mode. No phase seek, no bass kill, crossfade
     * triggers near end of track.
     */
    var isRealMixMode: Boolean = false

    /** Max track play time before a mix is forced (used when useHalfwayMix = false). */
    var maxTrackDurationMs: Long = 120_000L

    /**
     * When true: trigger the mix at (duration / 2) snapped to the nearest beat.
     * When false: trigger at [maxTrackDurationMs].
     */
    @Volatile var useHalfwayMix: Boolean = true

    /**
     * User-configurable cue-point offset (ms). Applied by [applyFirstBeatGuard] to
     * every incoming rawFirstBeatMs. Default 15 s matches the former hardcoded constant
     * that was moved here from BpmAnalyzer at DB version 11.
     * Range: 0–30 000 ms (0 = use raw aubio beat-0, no guard).
     */
    @Volatile var cuePointOffsetMs: Long = 15_000L

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL JOBS & METADATA
    // ═════════════════════════════════════════════════════════════════════════

    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var waveformJob: Job?        = null
    private var prebufferJob: Job?       = null

    /**
     * Guarded firstBeatMs for the CURRENT (primary) track.
     * Always set via [updateCurrentBpmInfo] which applies [applyFirstBeatGuard].
     * Never store a raw (unguarded) value here.
     */
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

    /** True only when both [isInitialized] and NOT [isReleased]. */
    val isActive: Boolean get() = isInitialized && !isReleased

    private val waveformSmoothed = FloatArray(WAVEFORM_BARS) { 0f }

    /**
     * Holds a next-track request that arrived while a crossfade was already in progress.
     * Executed immediately after the current crossfade completes.
     */
    private data class PendingTrack(
        val audioFile: AudioFile,
        val rawFirstBeatMs: Long,
        val bpm: Float,
        val amplitude: Float
    )

    // ═════════════════════════════════════════════════════════════════════════
    // CUE POINT GUARD
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Applies the user's cue-point guard to a raw firstBeatMs from the BPM cache.
     *
     * If [rawFirstBeatMs] is less than [cuePointOffsetMs], it is phase-advanced by
     * whole beat intervals until it clears the offset. This preserves the beat-grid
     * phase exactly (advancing by N intervals = choosing beat N on the same grid),
     * so the phase-alignment math in [PhaseAlignmentCalculator] remains valid.
     *
     * ── Single point of application rule ─────────────────────────────────────
     * Call this ONCE at every boundary where raw external data enters the engine:
     * [updateCurrentBpmInfo], [prebufferTrack], [executeCrossfade].
     * Do NOT call it on [currentTrackFirstBeatMs] — it is already guarded.
     *
     * ── Edge cases ───────────────────────────────────────────────────────────
     * rawFirstBeatMs == 0  → returned as 0 (no beat data, plays from start)
     * bpm == 0f            → guard skipped, raw value returned as-is
     * cuePointOffsetMs == 0 → guard skipped (user disabled minimum cue offset)
     * rawFirstBeatMs >= cuePointOffsetMs → already past the window, returned unchanged
     *
     * @param rawFirstBeatMs  Unguarded beat-0 from [BpmInfo.firstBeatMs].
     * @param bpm             Track BPM used to compute the beat interval.
     * @return Guarded firstBeatMs, always ≥ 0.
     */
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

        Log.d(TAG, "[CUE] Guard: raw=${rawFirstBeatMs}ms → guarded=${adjusted}ms " +
                "(offset=${offsetMs}ms bpm=$bpm interval=${beatIntervalMs}ms advances=$iterations)")
        return adjusted
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AUDIO FOCUS HANDLERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called by [AudioFocusCoordinator] when audio focus is lost (phone call,
     * navigation voice, another music app).
     *
     * Pauses BOTH players — the primary (audible) and the secondary (muted but
     * active during a crossfade). This produces clean silence instead of leaving
     * one track playing through the user's phone call.
     *
     * Sets [resumeAfterFocusGain] so [handleFocusGain] knows to auto-resume.
     * Flips _state.value.isPlaying to false, which the Step-6 suspension wall
     * in [executeCrossfade] observes to park the fade-math coroutine.
     */
    private fun handleFocusLost() {
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary   = primaryPlayer()   ?: return@withContext
                val secondary = secondaryPlayer()
                val wasCrossfading = _state.value.isCrossfading

                // Remember we were playing so we can auto-resume on focus gain.
                resumeAfterFocusGain = primary.isPlaying ||
                        (wasCrossfading && secondary?.isPlaying == true)

                primary.pause()
                // Always pause secondary: safe even when it is idle (no-op on ExoPlayer).
                secondary?.pause()
            }
            // ── KEY: flip isPlaying BEFORE the Step-6 while-loop can observe it ──
            // The fade coroutine reads _state.value.isPlaying at the top of each
            // loop iteration. Setting it false here causes the while-wall to engage
            // on the very next iteration, parking the math cleanly.
            _state.update { it.copy(isPlaying = false) }
            Log.i(TAG, "[FOCUS] Focus lost — both players paused. " +
                    "resumeAfterFocusGain=$resumeAfterFocusGain")
        }
    }

    /**
     * Called by [AudioFocusCoordinator] when audio focus is returned.
     *
     * Resumes both players only when [resumeAfterFocusGain] is true — i.e. the
     * interruption came from the system, not from a manual user pause. If the user
     * manually paused while a phone call was in progress, we respect that and stay paused.
     *
     * Flipping _state.value.isPlaying back to true wakes the Step-6 suspension wall
     * in [executeCrossfade], allowing the fade to continue from where it was parked.
     */
    private fun handleFocusGain() {
        if (!resumeAfterFocusGain) {
            Log.d(TAG, "[FOCUS] Focus gained but user-paused — not resuming")
            return
        }
        resumeAfterFocusGain = false
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary      = primaryPlayer()   ?: return@withContext
                val secondary    = secondaryPlayer()
                val isCrossfading = _state.value.isCrossfading

                primary.play()
                // Only resume secondary if a crossfade was still in progress
                // when focus was lost — it should continue the fade.
                if (isCrossfading) secondary?.play()
            }
            // ── KEY: flip isPlaying AFTER the ExoPlayers are playing again ───────
            // The Step-6 while-wall exits as soon as it sees isPlaying = true and
            // will immediately set volumes on the next iteration. The players must
            // already be running at that point or the volume writes are no-ops.
            _state.update { it.copy(isPlaying = true) }
            Log.i(TAG, "[FOCUS] Focus regained — players resumed")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALIZATION & LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Initializes both ExoPlayer instances and their audio processors.
     *
     * Safe to call multiple times — returns early if already initialized.
     * After a [release], call [initialize] again to bring the engine back to life;
     * all fields are reset to their defaults before rebuilding the players.
     */
    fun initialize() {
        if (isInitialized) {
            _nextTrackRequest.resetReplayCache()
            return
        }

        if (isReleased) {
            // Full reset so a re-initialized engine starts from a clean slate.
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
            currentTrackBpm       = 0f; currentTrackFirstBeatMs  = 0L
            currentTrackBaseVolume = 1.0f; currentTrackAmplitude = 0f
            currentTrackMixOutMs  = null; postCrossfadeGuardUntilMs = 0L
        }

        waveformProcessorA = WaveformCaptureAudioProcessor()
        waveformProcessorB = WaveformCaptureAudioProcessor()
        eqProcessorA       = BandEqAudioProcessor()
        eqProcessorB       = BandEqAudioProcessor()

        // Both players share the same AudioAttributes for consistent routing.
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // handleAudioFocus = false on BOTH: AudioFocusCoordinator manages focus centrally.
        // If both players managed focus independently, player B gaining focus would trigger
        // AUDIOFOCUS_LOSS on player A and pause it mid-crossfade.
        playerA = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = true)
        playerB = buildExoPlayer(attrs, handleAudioFocus = false, isPlayerA = false)

        isInitialized = true
        Log.i(TAG, "[LIFECYCLE] CrossfadeEngine initialized")
    }

    /**
     * Applies [preset] to both ExoPlayer pipelines simultaneously.
     *
     * Called by [AutoMixService] whenever [AppSettings.audioPreset] changes.
     * Mirrors the behaviour of [PlaybackService] so the user's EQ choice applies
     * everywhere with no separate "DJ EQ" setting needed.
     */
    fun applyEqPreset(preset: AudioPreset) {
        eqProcessorA?.setPreset(preset)
        eqProcessorB?.setPreset(preset)
        Log.d(TAG, "[EQ] Preset applied to both DJ players: $preset")
    }

    /**
     * Builds a single ExoPlayer with the waveform capture and EQ processors
     * injected into its audio pipeline.
     *
     * Pipeline order: EQ → WaveformCapture → AudioSink.
     * EQ runs first (signal shaping), then waveform capture reads the
     * already-equalised signal so the display matches what the user hears.
     */
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

    /**
     * Releases both ExoPlayer instances and all associated resources.
     *
     * After this call [isReleased] is true and [isActive] is false. Calling [initialize]
     * again will bring the engine back to life with fresh players.
     */
    fun release() {
        if (isReleased) return
        isReleased = true

        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        waveformJob?.cancel()
        prebufferJob?.cancel()

        // Abandon audio focus before releasing players so the system can
        // immediately hand focus to another app (or the normal player).
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
        Log.i(TAG, "[LIFECYCLE] CrossfadeEngine released")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROLS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Starts playback of [audioFile] from the beginning, resetting all crossfade state.
     *
     * Requests audio focus via [AudioFocusCoordinator] before playing. If focus is
     * denied, playback starts anyway (the coordinator will handle subsequent changes).
     */
    fun startPlayback(audioFile: AudioFile) {
        if (isReleased) return

        Log.i(TAG, "[PLAYBACK] Starting: id=${audioFile.id} title='${audioFile.title}'")

        // Cancel any in-flight crossfade and reset all pending state.
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

        engineScope.launch {
            // Request audio focus before playing so the system can route audio correctly
            // and interrupt other apps (navigation, phone calls already in progress, etc.).
            withContext(Dispatchers.Main) {
                audioFocusCoordinator.request()
            }

            withContext(Dispatchers.Main) {
                // Stop the secondary player and leave it silent.
                playerB?.pause(); playerB?.clearMediaItems()
                playerB?.volume = 0f
                playerB?.playbackParameters = PlaybackParameters.DEFAULT

                // Start the primary player immediately.
                val primary = playerA ?: return@withContext
                primary.stop(); primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.playbackParameters = PlaybackParameters.DEFAULT
                primary.prepare()
                primary.play()
            }

            // Extend the post-start guard so we don't trigger a crossfade immediately
            // after startPlayback (e.g. if the position monitor fires too soon).
            postCrossfadeGuardUntilMs = System.currentTimeMillis() + 5_000L
            _state.update { it.copy(currentTrack = audioFile, isPlaying = true, error = null) }
            startPositionMonitoring()
            startWaveformLoop()
        }
    }

    /**
     * Toggles play/pause for the current playback state.
     *
     * ── Crossfade awareness ───────────────────────────────────────────────────
     * During a crossfade BOTH players are active. This function coordinates them
     * together so the user never hears one track stopping while the other continues.
     *
     * ── Audio focus interaction ───────────────────────────────────────────────
     * When the user manually pauses, [resumeAfterFocusGain] is cleared so that a
     * subsequent audio focus gain (e.g. phone call ending) does NOT auto-resume.
     * The user explicitly paused; their intent should be respected.
     */
    fun playPause() {
        if (isReleased) return
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary       = primaryPlayer()   ?: return@withContext
                val secondary     = secondaryPlayer()
                val isCrossfading = _state.value.isCrossfading

                if (primary.isPlaying) {
                    // ── User is pausing ───────────────────────────────────────
                    // Clear the focus-resume flag: explicit user pause overrides
                    // any pending auto-resume from a system interruption.
                    resumeAfterFocusGain = false
                    primary.pause()
                    // During a crossfade, secondary is also active — pause it too.
                    if (isCrossfading) secondary?.pause()
                } else {
                    // ── User is resuming ──────────────────────────────────────
                    primary.play()
                    // During a crossfade, resume secondary so the fade can continue.
                    if (isCrossfading) secondary?.play()
                }
                _state.update { it.copy(isPlaying = primary.isPlaying) }
            }
        }
    }

    /**
     * Updates the beat-grid metadata for the currently-playing (primary) track.
     *
     * [rawFirstBeatMs] is the unguarded value from [BpmInfo.firstBeatMs]. The
     * cue-point guard is applied once here; every subsequent use of
     * [currentTrackFirstBeatMs] inside the engine is already guarded.
     *
     * @param rawFirstBeatMs Unguarded beat-0 from the BPM cache. Do NOT pre-guard.
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
        currentTrackBaseVolume  = if (amplitude > 0f)
            (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        currentTrackMixOutMs    = mixOutMs
        Log.d(TAG, "[METADATA] BPM=$bpm raw=${rawFirstBeatMs}ms " +
                "guarded=${currentTrackFirstBeatMs}ms offset=${cuePointOffsetMs}ms " +
                "vol=$currentTrackBaseVolume")
    }

    /** Manually triggers a mix transition immediately, ignoring position-based timing. */
    fun triggerMixNow() {
        if (isReleased || _state.value.isCrossfading) return
        val currentId = _state.value.currentTrack?.id ?: return
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.i(TAG, "[MIXER] Manual mix triggered")
    }

    /** Aborts an in-progress crossfade, leaving the primary player at its current volume. */
    fun abortCurrentCrossfade() {
        if (!_state.value.isCrossfading) return
        abortCrossfade = true
        crossfadeJob?.cancel()
        Log.w(TAG, "[MIXER] Crossfade aborted by caller")
    }

    /**
     * Pre-buffers [audioFile] into the secondary ExoPlayer so that when the
     * crossfade starts, the secondary is already in STATE_READY. This reduces
     * the ready-wait in [executeCrossfade] from potentially 3 000 ms to near-zero.
     *
     * @param rawFirstBeatMs Unguarded firstBeatMs. The guard is applied internally.
     */
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
                    // Approximate cue seek during prebuffer. executeCrossfade will
                    // do a precise phase-aligned seek just before the fade begins.
                    secondary.seekTo(guardedFirstBeatMs)
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} " +
                            "raw=${rawFirstBeatMs}ms guarded=${guardedFirstBeatMs}ms")
                } else {
                    Log.d(TAG, "[PREBUFFER] id=${audioFile.id} " +
                            if (!isRealMixMode) "continuous — no seek" else "no cue — no seek")
                }
            }
            prebufferedTrackId       = audioFile.id
            isPrebufferingInProgress = false
        }
    }

    /**
     * Queues [audioFile] as the next track to crossfade into.
     *
     * If a crossfade is already in progress, the request is stored in [pendingNextTrack]
     * and executed immediately after the current fade completes.
     *
     * @param rawFirstBeatMs Unguarded firstBeatMs. Do NOT pre-guard.
     */
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
     * Executes a full crossfade from the current primary track to [nextTrack].
     *
     * ── Step sequence ────────────────────────────────────────────────────────
     * 1. Prepare secondary (load media, reset parameters).
     * 2. Wait for STATE_READY (secondary is prebuffered so this is usually <100 ms).
     * 3. Start secondary playing at muted volume=0 (gets the decoder running).
     * 4. Phase-aligned seek — sampled NOW after all async waits (core beat-sync logic).
     * Uses [PhaseAlignmentCalculator.calculate] for the math.
     * Adaptive settle: polls secondaryRef.currentPosition until it reflects the new
     * seek target (within [SEEK_SETTLE_TOLERANCE_MS]), capped at [SEEK_SETTLE_DEADLINE_MS].
     * 5. Immediate bass kill on outgoing track.
     * 6. Equal-power volume ramp (cos/sin, [FADE_STEPS] steps).
     * ── PHONE-CALL HANDLING (Issue 1) ──────────────────────────────────────────
     * A while(!isPlaying) suspension wall at the TOP of each iteration parks
     * the volume-math coroutine while focus is absent. The ExoPlayers are
     * paused by [handleFocusLost]; the math loop goes to sleep here. When
     * [handleFocusGain] flips isPlaying back to true both players resume and
     * the loop wakes, continuing the fade from exactly where it paused.
     * 7. Abort check.
     * 8. Swap players (isPrimaryA flips) — ROLES ARE EXCHANGED **BEFORE** the
     * outgoing track is stopped, preventing its listener from incorrectly
     * updating the global `isPlaying` state.
     * ── PHONE-CALL HANDLING (Issue 2) ──────────────────────────────────────────
     * The swap is now UNCONDITIONAL — if the fade loop completed successfully,
     * the track hand-off must happen regardless of whether audio is currently
     * paused. Previously the swap was gated on secondaryRef.isPlaying which
     * is false during a phone call, causing the engine to resume the wrong
     * (old) primary and abandon the secondary permanently.
     * Only the follow-up play() call is conditioned on _state.value.isPlaying,
     * which correctly reflects whether the system/user wants audio at that moment.
     * 9. Reset state using the **actual** playing status of the new primary and
     * handle any pending next-track request.
     *
     * ── Why the seek happens after play() ────────────────────────────────────
     * Current order: play() → wait until playing → seekTo(position calculated NOW) → settle → fade.
     * The phase is calculated at the last possible moment. The secondary is already
     * buffered so the seek resolves in < 50 ms and is completely inaudible at volume=0.
     *
     * @param rawFirstBeatMs Unguarded firstBeatMs from [BpmInfo]. Do NOT pre-guard.
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

        val effectiveCrossfadeDurationMs = (crossfadeDurationMs * CROSSFADE_DURATION_MULT)
            .toLong()
            .coerceIn(2_000L, 14_000L)
        Log.i(TAG, "[MIXER] crossfade: ${crossfadeDurationMs}ms → effective=${effectiveCrossfadeDurationMs}ms")

        _state.update {
            it.copy(isCrossfading = true, crossfadeProgressFraction = 0f)
        }

        var bassKillEq: android.media.audiofx.Equalizer? = null
        try {
            val secondaryBaseVolume = if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume = currentTrackBaseVolume

            // ── 1. Prepare Secondary ──────────────────────────────────────────
            val alreadyPrebuffered = prebufferedTrackId == nextTrack.id
            withContext(Dispatchers.Main) {
                if (!alreadyPrebuffered) {
                    secondaryRef.stop(); secondaryRef.clearMediaItems()
                    secondaryRef.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondaryRef.volume = 0f
                    secondaryRef.prepare()
                } else {
                    prebufferedTrackId = null
                }
                secondaryRef.playbackParameters = PlaybackParameters.DEFAULT
            }

            // ── 2. Wait for STATE_READY ───────────────────────────────────────
            // If the track was prebuffered this wait is typically <100 ms.
            // Without prebuffering it can take up to 3 000 ms for local files.
            var waitMs = 0L
            var ready  = false
            while (waitMs < 3_000L) {
                ready = withContext(Dispatchers.Main) {
                    secondaryRef.playbackState == Player.STATE_READY &&
                            secondaryRef.currentMediaItem != null
                }
                if (ready) break
                delay(100L)
                waitMs += 100L
            }
            Log.i(TAG, "[TIMING] ready_wait=${waitMs}ms ready=$ready prebuffered=$alreadyPrebuffered")

            // ── 3. Start Secondary MUTED ──────────────────────────────────────
            // Start playing at volume=0 FIRST. Getting the decoder running means
            // the subsequent phase-seek (Step 4) resolves against buffered data
            // rather than triggering a new buffer fill, making it near-instantaneous.
            // This is the prerequisite for the late-sampling phase logic in Step 4.
            withContext(Dispatchers.Main) {
                try {
                    secondaryRef.volume = 0f
                    secondaryRef.play()
                } catch (e: Exception) {
                    Log.e(TAG, "[MIXER] Secondary play() failed", e)
                }
            }

            var playWaitMs       = 0L
            var secondaryPlaying = false
            while (!secondaryPlaying && playWaitMs < 1_000L) {
                delay(50L)
                playWaitMs      += 50L
                secondaryPlaying = withContext(Dispatchers.Main) { secondaryRef.isPlaying }
            }
            Log.i(TAG, "[TIMING] play_wait=${playWaitMs}ms playing=$secondaryPlaying")

            // ── 4. PHASE-ALIGNED SEEK (Real Mix mode only) ────────────────────
            //
            // KEY: primaryRef.currentPosition is sampled HERE — after all async
            // waits have completed. In the old code this was sampled before the waits,
            // meaning it could be 1–4 seconds stale by the time audio came out of the
            // secondary. That stale offset was the root cause of beat misalignment on
            // same-BPM tracks.
            //
            // Because secondary is muted (volume=0) and already buffered, the seekTo()
            // call is inaudible and resolves in < 50 ms on most devices.
            if (isRealMixMode) {
                val guardedIncomingFirstBeat = applyFirstBeatGuard(rawFirstBeatMs, nextBpm)

                val finalSeekMs = withContext(Dispatchers.Main) {
                    val secDuration = secondaryRef.duration.takeIf { it != C.TIME_UNSET } ?: 0L

                    // Delegate all phase arithmetic to PhaseAlignmentCalculator.
                    // It returns the bare cue point if any required value is missing.
                    PhaseAlignmentCalculator.calculate(
                        primaryCurrentPositionMs     = primaryRef.currentPosition,
                        outgoingGuardedFirstBeatMs   = currentTrackFirstBeatMs,
                        outgoingBpm                  = currentTrackBpm,
                        incomingGuardedFirstBeatMs   = guardedIncomingFirstBeat,
                        incomingBpm                  = nextBpm,
                        incomingDurationMs           = secDuration,
                        minRemainingMs               = effectiveCrossfadeDurationMs * 2
                    )
                }

                if (finalSeekMs > 0L) {
                    withContext(Dispatchers.Main) { secondaryRef.seekTo(finalSeekMs) }

                    // ── Adaptive settle ──────────────────────────────────────
                    // Instead of a fixed 80 ms blind wait, poll until the secondary's
                    // reported position reflects the new seek target (within SEEK_SETTLE_TOLERANCE_MS),
                    // confirming the decoder has flushed and resumed. Caps at
                    // SEEK_SETTLE_DEADLINE_MS to protect slow budget devices.
                    val seekDeadline = System.currentTimeMillis() + SEEK_SETTLE_DEADLINE_MS
                    while (System.currentTimeMillis() < seekDeadline) {
                        val secondaryPos = withContext(Dispatchers.Main) { secondaryRef.currentPosition }
                        val delta = abs(secondaryPos - finalSeekMs)
                        if (delta < SEEK_SETTLE_TOLERANCE_MS) {
                            Log.d(TAG, "[SEEK] Settled: pos=${secondaryPos}ms " +
                                    "target=${finalSeekMs}ms delta=${delta}ms")
                            break
                        }
                        delay(20L)
                    }
                }
            } else {
                Log.d(TAG, "[MIXER] Continuous Play mode — skipping phase seek")
            }

            // ── 5. IMMEDIATE BASS KILL on outgoing track ────────────────────
            // Kills the outgoing track's bass to minimum the moment the incoming
            // track starts coming in, before the first volume fade step. This
            // prevents two bass lines from playing simultaneously (which sounds
            // muddy and overloaded), since the incoming track starts at full spectrum.
            withContext(Dispatchers.Main) {
                try {
                    val sessionId = primaryRef.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        val eq = android.media.audiofx.Equalizer(0, sessionId)
                        val bassIndex = findBassBandIndex(eq)
                        eq.enabled = true
                        eq.setBandLevel(bassIndex, eq.bandLevelRange[0])
                        bassKillEq = eq
                        Log.d(TAG, "[MIXER] Immediate bass kill applied")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[MIXER] Bass kill failed: ${e.message}")
                }
            }

            // ── 6. Equal-Power Volume Ramp ────────────────────────────────────
            // cos(angle) on outgoing + sin(angle) on incoming = constant total power
            // across the crossfade, preventing the "dip" that linear fades produce.
            //
            // ── PHONE-CALL / AUDIO-FOCUS (Issue 1) ─────────────────────────
            // The while(!isPlaying) wall at the top of every iteration suspends the
            // volume-math coroutine when [handleFocusLost] pauses both ExoPlayers.
            // Without this, the for-loop kept running through delay() calls with no
            // audio output, then "completed" while the phone call was still in
            // progress — leaving the track abruptly jumping instead of fading.
            //
            // Flow when a phone call arrives mid-crossfade:
            //   1. handleFocusLost()  → pauses both players, sets isPlaying = false
            //   2. This while-wall    → parks the coroutine at 50 ms sleeps
            //   3. handleFocusGain()  → resumes both players, sets isPlaying = true
            //   4. while exits        → fade continues from exactly where it paused
            val primaryStartVolume = withContext(Dispatchers.Main) { primaryRef.volume }
            val stepDelayMs = (effectiveCrossfadeDurationMs / FADE_STEPS)
                .coerceAtLeast(16L)

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive || abortCrossfade) break

                // ── Suspension wall: park math while focus is absent ──────────
                // Exits as soon as handleFocusGain() flips isPlaying back to true,
                // or immediately if playback was never interrupted.
                while (!_state.value.isPlaying && engineScope.isActive && !abortCrossfade) {
                    delay(FOCUS_RESUME_POLL_MS)
                }
                // Re-check abort after waking — a long call could have been
                // followed by the user pressing stop before focus returned.
                if (abortCrossfade) break
                // ─────────────────────────────────────────────────────────────

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
                    try { secondaryRef.pause(); secondaryRef.volume = 0f } catch (_: Exception) {}
                }
                abortCrossfade = false
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
                return
            }

            // ── 8. Swap Players ───────────────────────────────────────────────
            // The swap MUST happen before stopping the outgoing track, otherwise
            // its listener would still consider it the primary and incorrectly set
            // the global `isPlaying` to false.
            //
            // ── PHONE-CALL / FOCUS (Issue 2) ────────────────────────────────
            // The swap is ALWAYS performed. The track hand‑off is a logical operation
            // and must happen regardless of focus state. Only the subsequent play()
            // call is conditioned on the desired playing state.
            //
            // ── LISTENER STATE ────────────────────────────────────────────
            // Because isPrimaryA is flipped BEFORE pausing the old primary, the
            // listener on that player now sees it as secondary and will NOT update
            // the global isPlaying flag. The global state is then explicitly synced
            // with the new primary's actual state.
            withContext(Dispatchers.Main) {
                // Set incoming track's target volume and flip roles immediately.
                if (_state.value.isPlaying) {
                    secondaryRef.volume = secondaryBaseVolume
                }
                isPrimaryA = !isPrimaryA   // ⬅️ ROLES EXCHANGED BEFORE STOPPING OLD

                // Now stop the old outgoing track – it is already secondary,
                // so its listener cannot corrupt the global state.
                try {
                    primaryRef.pause()   // primaryRef still points to the old primary
                    primaryRef.volume = primaryBaseVolume
                    primaryRef.playbackParameters = PlaybackParameters.DEFAULT
                } catch (_: Exception) {}

                // Ensure the new primary is playing if it should be.
                if (_state.value.isPlaying) {
                    primaryPlayer()?.play()
                }
            }

            // ── 9. Reset State ────────────────────────────────────────────────
            // Read the actual playing status from the (now current) primary player
            // so that the UI always reflects the true playback state.
            val actualPlaying = withContext(Dispatchers.Main) {
                primaryPlayer()?.isPlaying ?: false
            }

            lastRequestedTrackId      = null
            prebufferedTrackId        = null
            lastPrebufferRequestedId  = null
            currentTrackMixOutMs      = null
            postCrossfadeGuardUntilMs =
                System.currentTimeMillis() + effectiveCrossfadeDurationMs

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = actualPlaying,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }
            Log.i(TAG, "[MIXER] Swap complete → '${nextTrack.title}' playing=$actualPlaying")

            // Execute any transition that was queued while we were fading.
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.rawFirstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            // Guarantee isCrossfading is cleared even if the coroutine was cancelled.
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try { bassKillEq?.release(); bassKillEq = null } catch (_: Exception) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Continuously monitors the primary player's position and:
     * • Emits [_prebufferRequest] when the next track should start buffering.
     * • Emits [_nextTrackRequest] when the crossfade should begin.
     *
     * ── Beat-snapped halfway trigger ─────────────────────────────────────────
     * The [mixAtMs] trigger is snapped to the nearest beat boundary before the
     * halfway point. This ensures the crossfade always fires ON a beat, not at an
     * arbitrary millisecond that could land mid-beat and make the transition feel rushed.
     *
     * Old formula: mixAtMs = (duration / 2) + firstBeat   ← arbitrary ms
     * New formula: mixAtMs = firstBeat + (beatsToHalf × beatLength)  ← beat boundary
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

                // ── Beat alignment check ──────────────────────────────────────
                // Prevents the trigger from firing mid-beat (which would make the
                // transition feel rushed or off-grid).
                val isOnBeat = if (beatLengthMs > 0L && duration > 0L) {
                    val phase = (position - firstBeat).coerceAtLeast(0L) % beatLengthMs
                    phase <= BEAT_SNAP_WINDOW_MS || phase >= beatLengthMs - BEAT_SNAP_WINDOW_MS
                } else true

                // ── Phrase alignment check ────────────────────────────────────
                // In Auto-Mix mode, prefer triggering at a musical phrase boundary
                // (every 8 bars) for a more natural-sounding transition.
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

                // ── Beat-snapped halfway trigger ──────────────────────────────
                // Count how many full beat intervals fit between firstBeat and the
                // halfway point, then snap to the last full interval. This guarantees
                // the trigger lands exactly on a beat boundary.
                val mixAtMs: Long? = if (isRealMixMode && duration > 0L) {
                    if (useHalfwayMix) {
                        if (beatLengthMs > 0L && firstBeat > 0L) {
                            val halfMs      = duration / 2L
                            val beatsToHalf = ((halfMs - firstBeat).coerceAtLeast(0L) / beatLengthMs)
                            firstBeat + (beatsToHalf * beatLengthMs)
                        } else {
                            // No beat data available — fall back to the original formula.
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

                // ── Prebuffer request ─────────────────────────────────────────
                if ((inPrebufferZone || approachingMaxTime) && !_state.value.isCrossfading
                    && prebufferedTrackId == null && !isPrebufferingInProgress
                ) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = id
                        _prebufferRequest.tryEmit(id)
                    }
                }

                // ── Mix trigger ───────────────────────────────────────────────
                val beatAligned  = !isRealMixMode || (isOnBeat && (isAtPhrase || mustTrigger))
                val shouldTrigger = playing && !_state.value.isCrossfading && !postGuardActive
                        && duration > 0L &&
                        (customMixOut || ((inTriggerZone || isMaxTime) && beatAligned))

                if (shouldTrigger) {
                    val id = _state.value.currentTrack?.id
                    if (id != null && id != lastRequestedTrackId) {
                        lastRequestedTrackId = id
                        _nextTrackRequest.tryEmit(id)
                        Log.i(TAG, "[MIXER] Auto-mix triggered: remaining=${remaining}ms")
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

    /** Starts the 60 fps waveform capture loop from the primary player's audio processor. */
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
                val srcIdx    = (i.toFloat() / WAVEFORM_BARS * realBands.size).toInt()
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

    /**
     * Returns a gently animated placeholder waveform when no real audio data is available
     * (e.g. before the first track starts or when BPM data has not arrived yet).
     */
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

    /**
     * Creates a [Player.Listener] for [playerA] or [playerB].
     *
     * Only the PRIMARY player's events update [_state]. The secondary player's events
     * are ignored because the secondary is either silent (prebuffering) or mid-fade
     * and its state is managed directly by [executeCrossfade].
     */
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
                Log.e(TAG, "[PLAYBACK] Fatal error on primary: ${error.message} " +
                        "(code=${error.errorCode})", error)
                _state.update { it.copy(error = "Player error: ${error.message}") }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    private fun findBassBandIndex(eq: android.media.audiofx.Equalizer): Short {
        val bandCount = eq.numberOfBands.toInt()
        if (bandCount == 0) return 0
        var lowestUpperMhz = Int.MAX_VALUE
        var bestBand = 0
        for (i in 0 until bandCount) {
            val upper = eq.getBandFreqRange(i.toShort())[1]
            if (upper < lowestUpperMhz) { lowestUpperMhz = upper; bestBand = i }
        }
        return bestBand.toShort()
    }

    private fun Float.fmt() = String.format("%.1f", this)
}