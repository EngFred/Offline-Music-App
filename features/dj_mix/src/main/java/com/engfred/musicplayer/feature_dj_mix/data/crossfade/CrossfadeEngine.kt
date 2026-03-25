package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

/**
 * Observable state of the crossfade engine.
 *
 * [waveform] is generated purely from the beat grid (BPM + firstBeatMs + amplitude)
 * at ~60fps by a dedicated waveform loop — no android.media.audiofx.Visualizer,
 * no RECORD_AUDIO permission required.
 *
 * [currentMixStrategy] exposes the strategy chosen for the most-recently-started
 * crossfade so the UI can display an appropriate label ("Harmonic Drop", "Power Mix", etc.)
 */
data class CrossfadeEngineState(
    val currentTrack: AudioFile? = null,
    val isPlaying: Boolean = false,
    val isCrossfading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val crossfadeProgressFraction: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val currentMixStrategy: MixStrategy = MixStrategy.SMOOTH,
    val error: String? = null
)

// ═════════════════════════════════════════════════════════════════════════════
// MIX STRATEGY — the DJ's decision tree
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Classifies the mix technique to use, based on the BPM relationship between the
 * outgoing and incoming tracks.
 *
 * This is the "genius" layer: the same crossfade code does not get applied to every
 * transition. Each strategy has different crossfade duration, tempo sync behaviour,
 * EQ treatment, and volume curve.
 */
enum class MixStrategy {
    /**
     * BPM delta ≤ 3 BPM.
     *
     * Silky smooth. The crowd won't hear any BPM shift at all — it's transparent.
     * No tempo adjustment is needed or desired. A shorter crossfade duration actually
     * sounds cleaner here because there's nothing to hide.
     *
     * Technique: straight equal-power crossfade at natural speed, shorter duration.
     * EQ treatment: none needed (outgoing and incoming are nearly identical in energy).
     */
    TRANSPARENT,

    /**
     * BPM delta 3–8 BPM (non-harmonic).
     *
     * The standard club technique. The difference is noticeable but recoverable with
     * tempo-sync. ExoPlayer's PlaybackParameters brings the incoming track's speed
     * close to the outgoing BPM, then we ease it back to natural after the crossfade.
     *
     * Technique: tempo-sync + equal-power crossfade + graceful tempo ease-back.
     * EQ treatment: light bass kill in the final 20% of the outgoing track.
     */
    SMOOTH,

    /**
     * BPM delta 8–15 BPM (non-harmonic).
     *
     * The gap is large enough that even tempo-sync leaves an audible discontinuity.
     * Real DJs "hide" this by mixing through a breakdown or filter point — kill the
     * outgoing bass early, then let the incoming track breathe before the full crossfade.
     *
     * The crossfade duration is extended automatically (×1.4) to give both tracks time
     * to "settle" into the new tempo. Tempo-sync is still applied but less aggressively.
     *
     * Technique: early bass kill → tempo-sync → extended crossfade → tempo ease-back.
     * EQ treatment: bass kill applied from 35% into the crossfade (earlier than normal).
     */
    POWER_MIX,

    /**
     * Harmonically compatible BPMs (half-time, double-time, 3:2, or 4:3 ratio).
     *
     * The raw BPM delta may look alarming (120 → 60 = 60 BPM difference) but these
     * transitions are among the most powerful in DJing. The crowd hears an energy shift,
     * not a mistake. No tempo-sync is needed — the harmonic lock does the work.
     *
     * The crossfade can be shorter because the transition is inherently clean.
     * The incoming track should ideally be cued to its first beat so the rhythmic
     * "grid" snaps together across the half/double tempo boundary.
     *
     * Technique: short equal-power crossfade at natural speed (no tempo adjustment).
     * EQ treatment: bass kill on outgoing in the back half of the crossfade, letting
     * the incoming bass establish the new rhythmic feel before the hard swap.
     */
    HARMONIC,

    /**
     * BPM delta > 15 BPM (non-harmonic).
     *
     * This is a hard transition. No amount of tempo-sync sounds clean at this range —
     * the speed adjustment itself becomes jarring. Real DJs use the "energy drop"
     * technique: bring the outgoing track to near-silence during a breakdown, then
     * slam in the incoming track at full energy. The transition IS the moment.
     *
     * Tempo sync is deliberately NOT applied. The extended crossfade duration (×1.6)
     * and aggressive bass kill create a deliberate valley between the two tracks,
     * framing the BPM jump as an intentional creative choice.
     *
     * Technique: no tempo-sync + aggressive bass kill + very long crossfade.
     * EQ treatment: bass kill applied from 25% into the crossfade (very early).
     */
    WIDE_TRANSITION
}

/**
 * The engine's computed decision for a specific outgoing→incoming BPM pair.
 *
 * Computed by [CrossfadeEngine.computeMixDecision] at the start of every crossfade.
 * Logged in full so developers and DJs can see exactly what the algorithm chose and why.
 *
 * @param outgoingBpm               BPM of the track being faded out.
 * @param incomingBpm               BPM of the track being faded in.
 * @param rawBpmDelta               Absolute BPM difference (no harmonic adjustment).
 * @param effectiveBpmDelta         Minimum delta across all harmonic ratios.
 *                                  This collapses to near-zero for half-time/double-time pairs.
 * @param strategy                  Which [MixStrategy] was chosen.
 * @param isHarmonic                Whether a harmonic relationship was detected.
 * @param effectiveCrossfadeDurationMs Adjusted crossfade length for this strategy.
 * @param shouldTempoSync           Whether ExoPlayer's PlaybackParameters will be used.
 * @param speedFactor               The PlaybackParameters speed to apply (1.0 if no sync).
 * @param bassKillThresholdFraction The fraction into the crossfade at which to kill the
 *                                  outgoing track's bass. 0.0 = immediately; 1.0 = never.
 * @param djNote                    Human-readable explanation of the decision, logged at INFO.
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
    val speedFactor: Float,
    val bassKillThresholdFraction: Float,
    val djNote: String
)

/**
 * Manages two [ExoPlayer] instances for seamless BPM-aware DJ crossfades.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * GENIUS ADDITIONS IN THIS VERSION
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ── A. MIX STRATEGY ENGINE ([computeMixDecision]) ─────────────────────────
 *
 * Before any crossfade begins, the engine classifies the BPM pair into one of
 * five [MixStrategy] types. Each strategy applies a different combination of:
 *
 *   • Crossfade duration    (shorter for transparent/harmonic; longer for wide)
 *   • Tempo sync            (applied for SMOOTH/POWER_MIX; skipped for HARMONIC/WIDE)
 *   • Bass kill timing      (earlier for larger BPM gaps — hides the discontinuity)
 *   • Volume curve          (always equal-power sin/cos, but the effective duration
 *                            differs per strategy so the perceptual pace changes)
 *
 * ── B. HARMONIC BPM DETECTION ─────────────────────────────────────────────
 *
 * Delegates to [GetSmartNextTrackUseCase.isHarmonicallyCompatible] to keep the
 * harmonic ratio logic in one canonical place (DRY). Half-time / double-time /
 * 3:2 relationships are identified and handled as [MixStrategy.HARMONIC].
 *
 * ── C. ADAPTIVE BASS KILL TIMING ─────────────────────────────────────────
 *
 * The bass kill EQ is applied at a point in the crossfade determined by strategy:
 *   TRANSPARENT   → 70% through (barely used; the mix is already clean)
 *   SMOOTH        → 50% through (classic DJ move)
 *   POWER_MIX     → 35% through (early kill gives the incoming track space)
 *   HARMONIC      → 55% through (let the new bass establish first)
 *   WIDE_TRANS    → 25% through (aggressive valley — the jump IS the moment)
 *
 * ── D. TEMPO EASE-BACK (restored and improved) ────────────────────────────
 *
 * After the crossfade, speed eases back to 1.0× over 4 s so there is no abrupt
 * pitch snap. Now only triggered when tempo sync was actually applied (i.e.,
 * SMOOTH and POWER_MIX strategies) — not for HARMONIC or WIDE_TRANSITION.
 *
 * ── E. DEDICATED 60fps WAVEFORM LOOP ──────────────────────────────────────
 *
 * [startWaveformLoop] runs at 16 ms (~60 fps). Beat-grid envelope drives
 * kick/snare animation without RECORD_AUDIO permission.
 *
 * ── F. PRE-BUFFER & FAST POLL (unchanged) ─────────────────────────────────
 *
 * Eliminates silence gaps by silently readying the next track. Polls at 50 ms
 * in the trigger window for sub-beat precision.
 */
@UnstableApi
@Singleton
class CrossfadeEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val smartNextTrack: GetSmartNextTrackUseCase
) {

    companion object {
        private const val TAG = "CrossfadeEngine"

        // Polling intervals
        private const val POSITION_POLL_MS     = 300L
        private const val FAST_POLL_MS         = 50L
        private const val WAVEFORM_POLL_MS     = 16L   // ~60fps

        // Crossfade animation steps
        private const val FADE_STEPS           = 60

        // Safety guard — don't re-trigger within this many ms of the last trigger
        private const val CROSSFADE_GUARD_MS   = 200L

        // Beat-snap window for the position monitor's beat-alignment check
        private const val BEAT_SNAP_WINDOW_MS  = POSITION_POLL_MS / 2

        // Tempo adjustment limits — beyond these, PlaybackParameters sounds unnatural
        private const val MAX_SPEED_RATIO      = 1.33f
        private const val MIN_SPEED_RATIO      = 0.75f

        // Tempo ease-back after crossfade (returns incoming track to natural speed)
        private const val TEMPO_EASE_STEPS       = 40
        private const val TEMPO_EASE_DURATION_MS = 4_000L

        // Beat / phrase grid constants
        private const val PHRASE_BARS          = 8
        private const val BARS_PER_BEAT_MULTIPLE = 4

        // Waveform visualizer bar count
        private const val WAVEFORM_BARS        = 32

        // ── Crossfade duration multipliers per strategy ───────────────────────
        // Applied to the user's configured crossfadeDurationMs.
        // Coerced to sane absolute min/max to handle extreme user settings.

        private const val TRANSPARENT_DURATION_MULT   = 0.70f // shorter — nothing to hide
        private const val SMOOTH_DURATION_MULT         = 1.00f // user setting as-is
        private const val POWER_MIX_DURATION_MULT      = 1.40f // needs more breathing room
        private const val HARMONIC_DURATION_MULT       = 0.80f // clean so can be shorter
        private const val WIDE_TRANSITION_DURATION_MULT = 1.60f // long valley technique

        private const val MIN_CROSSFADE_MS = 2_000L
        private const val MAX_CROSSFADE_MS = 14_000L

        // ── Bass kill thresholds (fraction into crossfade) ────────────────────
        // Lower = earlier kill. Helps mask tempo discontinuities for large BPM jumps.

        private const val BASS_KILL_TRANSPARENT   = 0.70f // barely touch it
        private const val BASS_KILL_SMOOTH         = 0.50f // classic halfway point
        private const val BASS_KILL_POWER_MIX      = 0.35f // early — give incoming space
        private const val BASS_KILL_HARMONIC       = 0.55f // let new bass settle first
        private const val BASS_KILL_WIDE_TRANSITION = 0.25f // aggressive valley technique
    }

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Dual players ──────────────────────────────────────────────────────────
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    @Volatile private var isPrimaryA = true

    private fun primaryPlayer()   = if (isPrimaryA) playerA else playerB
    private fun secondaryPlayer() = if (isPrimaryA) playerB else playerA

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(CrossfadeEngineState())
    val state: StateFlow<CrossfadeEngineState> = _state.asStateFlow()

    private val _nextTrackRequest = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val nextTrackRequest: SharedFlow<Long> = _nextTrackRequest.asSharedFlow()

    private val _prebufferRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val prebufferRequest: SharedFlow<Long> = _prebufferRequest.asSharedFlow()

    // ── Settings ───────────────────────────────────────────────────────────────
    var crossfadeDurationMs: Long   = 5_000L
    var isRealMixMode: Boolean      = false
    var maxTrackDurationMs: Long    = 120_000L
    @Volatile var useHalfwayMix: Boolean = true

    // ── Internal jobs ─────────────────────────────────────────────────────────
    private var positionMonitorJob: Job? = null
    private var crossfadeJob: Job?       = null
    private var tempoEaseJob: Job?       = null
    private var waveformJob: Job?        = null
    private var prebufferJob: Job?       = null

    // ── Beat-aligned state ────────────────────────────────────────────────────
    @Volatile private var currentTrackBpm: Float        = 0f
    @Volatile private var currentTrackFirstBeatMs: Long = 0L
    @Volatile private var currentTrackBaseVolume: Float = 1.0f
    @Volatile private var currentTrackAmplitude: Float  = 0f

    // ── Pre-buffer state ──────────────────────────────────────────────────────
    @Volatile private var prebufferedTrackId: Long?      = null
    @Volatile private var isPrebufferingInProgress       = false
    private var lastPrebufferRequestedId: Long?          = null

    // ── Misc state ─────────────────────────────────────────────────────────────
    private var lastRequestedTrackId: Long?              = null
    @Volatile private var pendingNextTrack: PendingTrack? = null
    @Volatile private var isReleased                     = false
    private var isInitialized                            = false

    val isActive: Boolean get() = isInitialized && !isReleased

    /** Per-bar smoothed amplitudes for the beat-grid waveform. */
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
            isReleased               = false
            isPrimaryA               = true
            lastRequestedTrackId     = null
            lastPrebufferRequestedId = null
            pendingNextTrack         = null
            prebufferedTrackId       = null
            isPrebufferingInProgress = false
            playerA                  = null
            playerB                  = null
            _state.value             = CrossfadeEngineState()
            waveformSmoothed.fill(0f)
            _nextTrackRequest.resetReplayCache()
            currentTrackBpm          = 0f
            currentTrackFirstBeatMs  = 0L
            currentTrackBaseVolume   = 1.0f
            currentTrackAmplitude    = 0f
            Log.d(TAG, "initialize: Engine reset after previous release.")
        }

        engineScope.launch {
            withContext(Dispatchers.Main) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()

                playerA = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, true)
                    setHandleAudioBecomingNoisy(true)
                    skipSilenceEnabled = true
                    addListener(createPlayerListener(isPlayerA = true))
                }

                playerB = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(attrs, false)
                    setHandleAudioBecomingNoisy(false)
                    skipSilenceEnabled = true
                    addListener(createPlayerListener(isPlayerA = false))
                }

                isInitialized = true
                Log.d(TAG, "initialize: Both players ready.")
            }
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
        prebufferedTrackId       = null
        lastPrebufferRequestedId = null
        isPrebufferingInProgress = false
        waveformSmoothed.fill(0f)

        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                primary.stop()
                primary.clearMediaItems()
                primary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                primary.volume = 1f
                primary.prepare()
                primary.play()
                lastRequestedTrackId = null
            }

            _state.update { it.copy(currentTrack = audioFile, isPlaying = true, error = null) }
            startPositionMonitoring()
            startWaveformLoop()
            Log.d(TAG, "startPlayback: '${audioFile.title}'")
        }
    }

    fun playPause() {
        if (isReleased) return
        engineScope.launch {
            withContext(Dispatchers.Main) {
                val primary = primaryPlayer() ?: return@withContext
                if (primary.isPlaying) primary.pause() else primary.play()
                _state.update { it.copy(isPlaying = primary.isPlaying) }
            }
        }
    }

    fun updateCurrentBpmInfo(bpm: Float, firstBeatMs: Long, amplitude: Float = 0f) {
        currentTrackBpm         = bpm
        currentTrackFirstBeatMs = firstBeatMs
        currentTrackAmplitude   = amplitude
        currentTrackBaseVolume  = if (amplitude > 0f) (0.15f / amplitude).coerceIn(0.2f, 1.0f) else 1.0f
        Log.d(TAG, "updateCurrentBpmInfo: BPM=$bpm firstBeat=${firstBeatMs}ms Vol=$currentTrackBaseVolume")
    }

    fun triggerMixNow() {
        if (isReleased) return
        if (_state.value.isCrossfading) {
            Log.d(TAG, "triggerMixNow: ignored — crossfade already in progress.")
            return
        }
        val currentId = _state.value.currentTrack?.id ?: run {
            Log.d(TAG, "triggerMixNow: ignored — no current track.")
            return
        }
        lastRequestedTrackId = null
        _nextTrackRequest.tryEmit(currentId)
        Log.d(TAG, "triggerMixNow: nextTrackRequest emitted for trackId=$currentId")
    }

    fun prebufferTrack(audioFile: AudioFile, firstBeatMs: Long, bpm: Float, amplitude: Float) {
        if (isReleased || _state.value.isCrossfading) return
        if (isPrebufferingInProgress || prebufferedTrackId == audioFile.id) return
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
                secondary.setMediaItem(MediaItem.fromUri(audioFile.uri))
                secondary.volume = 0f
                secondary.prepare()
                if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
            }

            prebufferedTrackId       = audioFile.id
            isPrebufferingInProgress = false
            Log.d(TAG, "prebufferTrack: ready '${audioFile.title}' id=${audioFile.id}")
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
            Log.d(TAG, "queueNextTrack: crossfade in progress — stored as pending.")
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
        Log.d(TAG, "release: Stopping all jobs and releasing players.")

        positionMonitorJob?.cancel()
        crossfadeJob?.cancel()
        tempoEaseJob?.cancel()
        waveformJob?.cancel()
        prebufferJob?.cancel()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerA?.stop(); playerA?.release(); playerA = null
                playerB?.stop(); playerB?.release(); playerB = null
            } catch (e: Exception) {
                Log.e(TAG, "release: Error releasing players", e)
            } finally {
                isInitialized = false
                _state.update { it.copy(waveform = emptyList()) }
                Log.d(TAG, "release: ExoPlayers destroyed.")
            }
        }

        engineScope.cancel()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MIX DECISION ENGINE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Analyses the BPM relationship between the outgoing and incoming tracks and
     * returns a [MixDecision] that drives every aspect of [executeCrossfade].
     *
     * This is the "genius DJ brain" — called once at the start of each crossfade.
     *
     * Decision process:
     *   1. Compute raw absolute BPM delta.
     *   2. Compute effective delta (minimum across all harmonic ratios).
     *   3. Check harmonic compatibility via [GetSmartNextTrackUseCase.isHarmonicallyCompatible].
     *   4. Classify into a [MixStrategy] based on raw delta and harmonic flag.
     *   5. Derive all downstream parameters (duration, tempo sync, bass kill timing).
     *   6. Build a [djNote] that explains the decision in plain English for log output.
     *
     * @param outgoingBpm     BPM of the track being faded out.
     * @param incomingBpm     BPM of the track being faded in.
     * @param userCrossfadeDurationMs The user's preferred crossfade duration (a baseline
     *                         that gets scaled by the strategy multiplier).
     * @return A fully-populated [MixDecision] ready for use in [executeCrossfade].
     */
    private fun computeMixDecision(
        outgoingBpm: Float,
        incomingBpm: Float,
        userCrossfadeDurationMs: Long
    ): MixDecision {
        val rawDelta     = abs(outgoingBpm - incomingBpm)
        val effectiveDelta = smartNextTrack.minimumHarmonicDelta(outgoingBpm, incomingBpm)
        val isHarmonic   = smartNextTrack.isHarmonicallyCompatible(outgoingBpm, incomingBpm)

        // ── Strategy classification ────────────────────────────────────────────
        // Harmonic detection takes priority: a 120→60 BPM pair (rawDelta=60) is NOT
        // a WIDE_TRANSITION — it's a beautiful harmonic drop. Check harmonic first.
        val strategy = when {
            isHarmonic && rawDelta > 3f -> MixStrategy.HARMONIC
            rawDelta <= 3f              -> MixStrategy.TRANSPARENT
            rawDelta <= 8f              -> MixStrategy.SMOOTH
            rawDelta <= 15f             -> MixStrategy.POWER_MIX
            else                        -> MixStrategy.WIDE_TRANSITION
        }

        // ── Adaptive crossfade duration ────────────────────────────────────────
        val durationMultiplier = when (strategy) {
            MixStrategy.TRANSPARENT    -> TRANSPARENT_DURATION_MULT
            MixStrategy.SMOOTH         -> SMOOTH_DURATION_MULT
            MixStrategy.POWER_MIX      -> POWER_MIX_DURATION_MULT
            MixStrategy.HARMONIC       -> HARMONIC_DURATION_MULT
            MixStrategy.WIDE_TRANSITION -> WIDE_TRANSITION_DURATION_MULT
        }
        val effectiveDurationMs = (userCrossfadeDurationMs * durationMultiplier)
            .toLong()
            .coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        // ── Tempo sync decision ────────────────────────────────────────────────
        // HARMONIC: no sync — the relationship IS the mix; sync would fight it.
        // WIDE_TRANSITION: no sync — too far apart; the speed adjustment would sound wrong.
        // TRANSPARENT: optional, but with ≤3 BPM delta the effect is imperceptible.
        // SMOOTH / POWER_MIX: yes — the classic DJ tempo-matching technique.
        val shouldTempoSync = strategy == MixStrategy.SMOOTH || strategy == MixStrategy.POWER_MIX

        val speedFactor = if (shouldTempoSync && outgoingBpm > 0f && incomingBpm > 0f) {
            (outgoingBpm / incomingBpm).coerceIn(MIN_SPEED_RATIO, MAX_SPEED_RATIO)
        } else 1.0f

        // ── Bass kill threshold ────────────────────────────────────────────────
        // Determines at what fraction of the crossfade the outgoing track's bass
        // gets killed via EQ. Earlier = more aggressive masking of the BPM gap.
        val bassKillThreshold = when (strategy) {
            MixStrategy.TRANSPARENT    -> BASS_KILL_TRANSPARENT
            MixStrategy.SMOOTH         -> BASS_KILL_SMOOTH
            MixStrategy.POWER_MIX      -> BASS_KILL_POWER_MIX
            MixStrategy.HARMONIC       -> BASS_KILL_HARMONIC
            MixStrategy.WIDE_TRANSITION -> BASS_KILL_WIDE_TRANSITION
        }

        // ── Human-readable DJ note for log output ─────────────────────────────
        val djNote = buildString {
            append("[DJ DECISION] ${strategy.name}: ")
            append("${outgoingBpm.fmt()} → ${incomingBpm.fmt()} BPM")
            append(" | rawΔ=${rawDelta.fmt()} effectiveΔ=${effectiveDelta.fmt()}")
            if (isHarmonic) append(" | ★ HARMONIC")
            append(" | fade=${effectiveDurationMs}ms")
            if (shouldTempoSync) append(" | tempo-sync ×${speedFactor.fmt3()}")
            else                  append(" | NO tempo-sync")
            append(" | bass kill at ${(bassKillThreshold * 100).toInt()}%")
            append("\n         ↳ ")
            when (strategy) {
                MixStrategy.TRANSPARENT    -> append("Silky smooth — nothing to hide. Short straight crossfade.")
                MixStrategy.SMOOTH         -> append("Standard club technique. Tempo-sync + ease-back.")
                MixStrategy.POWER_MIX      -> append("Early bass kill gives the incoming track space to breathe.")
                MixStrategy.HARMONIC       -> append("Half/double time — the harmonic lock does the work. No sync needed.")
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
            speedFactor                  = speedFactor,
            bassKillThresholdFraction    = bassKillThreshold,
            djNote                       = djNote
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CROSSFADE EXECUTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Performs the full crossfade from the current primary player to [nextTrack].
     *
     * Flow:
     *   1. [computeMixDecision] → classify BPM relationship → derive all parameters.
     *   2. Prepare secondary player (or use pre-buffered track if available).
     *   3. Apply bass kill EQ to outgoing track at the threshold determined by the strategy.
     *   4. Run the equal-power sin/cos volume ramp over [MixDecision.effectiveCrossfadeDurationMs].
     *   5. Finalise the player swap.
     *   6. If tempo-sync was used, launch [easeTempoBackToNormal] to avoid abrupt snap.
     *   7. Execute any [pendingNextTrack] queued during the crossfade.
     */
    private suspend fun executeCrossfade(
        nextTrack: AudioFile,
        firstBeatMs: Long    = 0L,
        nextBpm: Float       = 0f,
        nextAmplitude: Float = 0f
    ) {
        val primary   = primaryPlayer()   ?: return
        val secondary = secondaryPlayer() ?: return

        // ── Step 1: Compute mix decision ──────────────────────────────────────
        val decision = computeMixDecision(
            outgoingBpm           = currentTrackBpm,
            incomingBpm           = nextBpm,
            userCrossfadeDurationMs = crossfadeDurationMs
        )
        Log.i(TAG, decision.djNote)

        _state.update {
            it.copy(
                isCrossfading        = true,
                crossfadeProgressFraction = 0f,
                currentMixStrategy   = decision.strategy
            )
        }

        tempoEaseJob?.cancel() // Cancel any ongoing ease from a previous rapid mix

        var bassKillEq: android.media.audiofx.Equalizer? = null

        try {
            // ── Step 2: Auto-gain volumes ──────────────────────────────────────
            val secondaryBaseVolume = if (nextAmplitude > 0f)
                (0.15f / nextAmplitude).coerceIn(0.2f, 1.0f) else 1.0f
            val primaryBaseVolume   = currentTrackBaseVolume

            // ── Step 3: Prepare secondary player ──────────────────────────────
            val isAlreadyPrebuffered = prebufferedTrackId == nextTrack.id
            if (!isAlreadyPrebuffered) {
                withContext(Dispatchers.Main) {
                    secondary.stop()
                    secondary.clearMediaItems()
                    secondary.setMediaItem(MediaItem.fromUri(nextTrack.uri))
                    secondary.volume = 0f

                    // Apply tempo-sync playback speed if the strategy requires it
                    if (decision.shouldTempoSync && decision.speedFactor != 1.0f) {
                        secondary.setPlaybackParameters(
                            PlaybackParameters(decision.speedFactor, 1.0f)
                        )
                    }

                    secondary.prepare()
                    if (firstBeatMs > 0L) secondary.seekTo(firstBeatMs)
                    secondary.play()
                }

                var waitMs = 0L
                while (waitMs < 2_000L) {
                    val ready = withContext(Dispatchers.Main) {
                        secondary.playbackState == Player.STATE_READY || secondary.isPlaying
                    }
                    if (ready) break
                    delay(100L); waitMs += 100L
                }
                Log.d(TAG, "executeCrossfade: prepared from scratch (waited ${waitMs}ms)")

            } else {
                withContext(Dispatchers.Main) {
                    if (decision.shouldTempoSync && decision.speedFactor != 1.0f) {
                        secondary.setPlaybackParameters(
                            PlaybackParameters(decision.speedFactor, 1.0f)
                        )
                    }
                    secondary.play()
                }
                prebufferedTrackId = null
                Log.d(TAG, "executeCrossfade: using pre-buffered track — skipped buffer wait")
            }

            // ── Step 4: Equal-power volume ramp with strategy-aware bass kill ──
            val effectiveDuration = decision.effectiveCrossfadeDurationMs
            val stepDelayMs       = (effectiveDuration / FADE_STEPS).coerceAtLeast(16L)
            var bassKillApplied   = false

            for (step in 1..FADE_STEPS) {
                if (!engineScope.isActive) break

                val progress = step.toFloat() / FADE_STEPS
                val angle    = progress * (PI.toFloat() / 2f)
                val toGain   = sin(angle)
                val fromGain = cos(angle)

                // Apply bass kill at the strategy-defined threshold fraction
                if (!bassKillApplied && progress >= decision.bassKillThresholdFraction) {
                    bassKillApplied = true
                    withContext(Dispatchers.Main) {
                        try {
                            val sessionId = primary.audioSessionId
                            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                                bassKillEq = android.media.audiofx.Equalizer(0, sessionId).apply {
                                    enabled = true
                                    if (numberOfBands > 0) setBandLevel(0, bandLevelRange[0])
                                }
                                Log.d(TAG, "Bass kill applied at ${(progress * 100).toInt()}% " +
                                        "(strategy: ${decision.strategy.name})")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Bass kill EQ failed (non-fatal): ${e.message}")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    primary.volume   = fromGain * primaryBaseVolume
                    secondary.volume = toGain   * secondaryBaseVolume
                }

                _state.update { it.copy(crossfadeProgressFraction = toGain) }
                delay(stepDelayMs)
            }

            // ── Step 5: Finalise player swap ───────────────────────────────────
            withContext(Dispatchers.Main) {
                primary.pause()
                primary.volume = 1f
                primary.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
                secondary.volume = secondaryBaseVolume
            }

            isPrimaryA               = !isPrimaryA
            lastRequestedTrackId     = null
            prebufferedTrackId       = null
            lastPrebufferRequestedId = null

            _state.update {
                it.copy(
                    currentTrack              = nextTrack,
                    isPlaying                 = true,
                    isCrossfading             = false,
                    crossfadeProgressFraction = 0f
                )
            }

            Log.d(TAG, "executeCrossfade: COMPLETE. Strategy=${decision.strategy.name} PrimaryA=$isPrimaryA")

            // ── Step 6: Post-crossfade tempo ease-back ────────────────────────
            // Only needed for strategies that applied tempo-sync. HARMONIC and
            // WIDE_TRANSITION don't sync, so there's nothing to ease back.
            if (decision.shouldTempoSync && decision.speedFactor != 1.0f) {
                tempoEaseJob = engineScope.launch {
                    easeTempoBackToNormal(decision.speedFactor)
                }
            }

            // ── Step 7: Execute pending track if one was queued mid-crossfade ──
            pendingNextTrack?.let { pending ->
                pendingNextTrack = null
                executeCrossfade(pending.audioFile, pending.firstBeatMs, pending.bpm, pending.amplitude)
            }

        } finally {
            if (_state.value.isCrossfading) {
                _state.update { it.copy(isCrossfading = false, crossfadeProgressFraction = 0f) }
            }
            try { bassKillEq?.release(); bassKillEq = null } catch (e: Exception) {
                Log.e(TAG, "Bass kill EQ release failed", e)
            }
        }
    }

    // ── Tempo ease-back ───────────────────────────────────────────────────────

    /**
     * Smoothly returns the new primary player's playback speed from [fromSpeed] back
     * to 1.0× over [TEMPO_EASE_DURATION_MS] milliseconds.
     *
     * This prevents the abrupt pitch/rhythm snap that would occur if PlaybackParameters
     * were reset instantly after the crossfade completes.
     *
     * Only called for [MixStrategy.SMOOTH] and [MixStrategy.POWER_MIX] transitions —
     * strategies that actually applied tempo-sync. Other strategies leave the player
     * at natural speed throughout, so no ease-back is needed.
     */
    private suspend fun easeTempoBackToNormal(fromSpeed: Float) {
        val stepDelayMs = TEMPO_EASE_DURATION_MS / TEMPO_EASE_STEPS
        for (step in 1..TEMPO_EASE_STEPS) {
            if (!engineScope.isActive) break
            val speed = fromSpeed + (1.0f - fromSpeed) * step.toFloat() / TEMPO_EASE_STEPS
            withContext(Dispatchers.Main) {
                primaryPlayer()?.setPlaybackParameters(PlaybackParameters(speed, 1.0f))
            }
            delay(stepDelayMs)
        }
        withContext(Dispatchers.Main) {
            primaryPlayer()?.setPlaybackParameters(PlaybackParameters(1.0f, 1.0f))
        }
        Log.d(TAG, "Tempo ease-back complete (${fromSpeed.fmt3()} → 1.0)")
    }

    // ── Position monitoring ───────────────────────────────────────────────────

    /**
     * Monitors playback position at [POSITION_POLL_MS] intervals, accelerating to
     * [FAST_POLL_MS] when in the trigger or pre-buffer zone.
     *
     * Emits [_prebufferRequest] when remaining time crosses the pre-buffer zone
     * (crossfadeDuration × 3) so [DjMixService] can silently load the next track.
     *
     * Emits [_nextTrackRequest] when remaining time crosses the crossfade trigger
     * window AND the current position is beat-aligned AND phrase-boundary conditions
     * are met (or the "must trigger now" safety valve fires).
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

                val isOnBeatBoundary = if (beatLengthMs > 0L && duration > 0L) {
                    val phase = (position - firstBeat).coerceAtLeast(0L) % beatLengthMs
                    phase <= BEAT_SNAP_WINDOW_MS || phase >= (beatLengthMs - BEAT_SNAP_WINDOW_MS)
                } else true

                val barLengthMs    = beatLengthMs * BARS_PER_BEAT_MULTIPLE
                val phraseLengthMs = barLengthMs  * PHRASE_BARS
                val isAtPhraseBoundary = when {
                    phraseLengthMs <= 0L || firstBeat <= 0L -> true
                    else -> {
                        val elapsed       = (position - firstBeat).coerceAtLeast(0L)
                        val phaseInPhrase = elapsed % phraseLengthMs
                        val lastBarStart  = phraseLengthMs - barLengthMs
                        phaseInPhrase >= lastBarStart
                    }
                }

                val mustTriggerNow  = beatLengthMs > 0L && remaining <= crossfadeDurationMs + beatLengthMs
                val triggerWindowMs = crossfadeDurationMs + beatLengthMs
                val prebufferZoneMs = crossfadeDurationMs * 3 + beatLengthMs
                val inTriggerZone   = duration > 0L && remaining in CROSSFADE_GUARD_MS..triggerWindowMs
                val inPrebufferZone = duration > 0L && remaining in triggerWindowMs..prebufferZoneMs

                val isMaxTimeReached = if (isRealMixMode && duration > 0L) {
                    val mixTriggerMs = if (useHalfwayMix) duration / 2L else maxTrackDurationMs
                    position >= mixTriggerMs && remaining > crossfadeDurationMs
                } else false

                // Pre-buffer: silently load next track before the crossfade fires
                if (inPrebufferZone
                    && !_state.value.isCrossfading
                    && prebufferedTrackId == null
                    && !isPrebufferingInProgress) {
                    val currentId = _state.value.currentTrack?.id
                    if (currentId != null && currentId != lastPrebufferRequestedId) {
                        lastPrebufferRequestedId = currentId
                        _prebufferRequest.tryEmit(currentId)
                        Log.d(TAG, "prebufferRequest emitted [remaining=${remaining}ms]")
                    }
                }

                // Crossfade trigger: beat-aligned, phrase-aware
                val shouldTrigger = playing
                        && !_state.value.isCrossfading
                        && duration > 0L
                        && (inTriggerZone || isMaxTimeReached)
                        && isOnBeatBoundary
                        && (isAtPhraseBoundary || mustTriggerNow)

                if (shouldTrigger) {
                    val currentId = _state.value.currentTrack?.id
                    if (currentId != null && currentId != lastRequestedTrackId) {
                        lastRequestedTrackId = currentId
                        Log.d(TAG, "nextTrackRequest emitted [remaining=${remaining}ms " +
                                "beat=$isOnBeatBoundary phrase=$isAtPhraseBoundary forced=$mustTriggerNow]")
                        _nextTrackRequest.tryEmit(currentId)
                    }
                }

                _state.update {
                    it.copy(
                        currentPositionMs = position,
                        currentDurationMs = duration
                    )
                }

                val pollDelayMs = if (inTriggerZone || inPrebufferZone || isMaxTimeReached)
                    FAST_POLL_MS else POSITION_POLL_MS
                delay(pollDelayMs)
            }
        }
    }

    // ── 60fps waveform loop ───────────────────────────────────────────────────

    private fun startWaveformLoop() {
        waveformJob?.cancel()
        waveformJob = engineScope.launch {
            while (isActive) {
                val bpm       = currentTrackBpm
                val firstBeat = currentTrackFirstBeatMs
                val amplitude = currentTrackAmplitude
                val duration  = _state.value.currentDurationMs

                if (bpm > 0f && duration > 0L) {
                    val position = withContext(Dispatchers.Main) {
                        primaryPlayer()?.currentPosition ?: 0L
                    }
                    val waveform = generateBeatWaveform(position, bpm, firstBeat, amplitude)
                    _state.update { it.copy(waveform = waveform) }
                }

                delay(WAVEFORM_POLL_MS)
            }
        }
    }

    // ── Beat-grid waveform generation ─────────────────────────────────────────

    private fun generateBeatWaveform(
        positionMs: Long,
        bpm: Float,
        firstBeatMs: Long,
        amplitude: Float
    ): List<Float> {
        if (bpm <= 0f) return emptyList()

        val beatLengthMs  = 60_000.0 / bpm
        val elapsed       = (positionMs - firstBeatMs).toDouble().coerceAtLeast(0.0)
        val phaseInBeat   = (elapsed % beatLengthMs) / beatLengthMs

        val kickEnvelope  = maxOf(0.0, 1.0 - phaseInBeat * 2.5).toFloat()
        val snarePhase    = if (phaseInBeat > 0.5) phaseInBeat - 0.5 else 1.0
        val snareEnvelope = (maxOf(0.0, 1.0 - snarePhase * 3.0) * 0.65).toFloat()
        val beatEnvelope  = maxOf(kickEnvelope, snareEnvelope)

        val scaledAmp = (amplitude * 4.5f).coerceIn(0.18f, 0.95f)

        for (i in 0 until WAVEFORM_BARS) {
            val staticBase   = (Math.sin(i * 2.3999632 + 1.0) * 0.22 + 0.78).toFloat()
            val freqNorm     = i.toFloat() / WAVEFORM_BARS
            val kickResponse = 1f - freqNorm * 0.65f
            val steadyContrib = freqNorm * 0.35f
            val dynamic      = beatEnvelope * kickResponse + steadyContrib
            val rawValue     = (scaledAmp * staticBase * dynamic).coerceIn(0f, 1f)
            waveformSmoothed[i] = waveformSmoothed[i] * 0.65f + rawValue * 0.35f
        }

        return waveformSmoothed.toList()
    }

    // ── Formatting helpers (local, no import needed) ───────────────────────────

    private fun Float.fmt()  = String.format("%.1f", this)
    private fun Float.fmt3() = String.format("%.3f", this)
}