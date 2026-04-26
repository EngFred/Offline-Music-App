package com.engfred.musicplayer.feature_dj_mix.data.sampler

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngineState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-latency DJ sampler engine built on Android's [SoundPool].
 *
 * ── Trigger map ───────────────────────────────────────────────────────────────
 *
 * Point                  │ Pool          │ When
 * ───────────────────────┼───────────────┼────────────────────────────────────
 * SESSION START          │ AIR_HORN      │ First track begins playing
 * TRACK MILESTONE 1      │ MID_1_POOL    │ 33 % through track (not crossfading)
 * TRACK MILESTONE 2      │ MID_2_POOL    │ 66 % through track (not crossfading)
 * PRE-CROSSFADE WARNING  │ START_POOL    │ timeToNextMixMs ≤ 15 s (not crossfading)
 * POST-CROSSFADE DROP    │ DROP_POOL     │ Crossfade completes, track changed
 *
 * ── Design contract ──────────────────────────────────────────────────────────
 * NO sample fires while [CrossfadeEngineState.isCrossfading] is true.
 * The pre-crossfade warning fires *before* the blend starts (using the engine's
 * timeToNextMixMs countdown), so the START sample acts as a genuine "heads-up"
 * rather than a simultaneous collision with the audio fade.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@UnstableApi
@Singleton
class SamplerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val crossfadeEngine: CrossfadeEngine,
    private val synthesizer: SynthesizedSampleGenerator
) {

    companion object {
        private const val TAG         = "SamplerEngine"
        private const val MAX_STREAMS = 6

        // ── Track milestone fractions ─────────────────────────────────────────
        // Fire at 33 % and 66 % of the track's total duration.
        // Both are checked only while NOT crossfading, so they can never
        // collide with an active blend regardless of track length.
        private const val MILESTONE_1 = 0.33f
        private const val MILESTONE_2 = 0.66f

        // ── Pre-crossfade warning window ──────────────────────────────────────
        // Fires the START sample when timeToNextMixMs drops to this value.
        // 15 s gives a comfortable lead-in: the sample finishes before the
        // crossfade engine actually starts fading volumes.
        private const val PRE_CROSSFADE_WARNING_MS = 15_000L

        // ── Session-start poll ────────────────────────────────────────────────
        private const val SESSION_HORN_POLL_MS    = 20L
        private const val SESSION_HORN_TIMEOUT_MS = 600L

        // ── Sample pools ──────────────────────────────────────────────────────
        // DJ_SCRATCH is intentionally excluded from every pool.
        private val START_POOL = listOf(
            SampleId.SIREN,
            SampleId.REWIND_SWEEP,
            SampleId.FOGHORN
        )
        private val MID_1_POOL = listOf(
            SampleId.RISER_SWEEP,
            SampleId.WHITE_NOISE_UP,
            SampleId.WHITE_NOISE_DOWN
        )
        private val MID_2_POOL = listOf(
            SampleId.IMPACT_HIT,
            SampleId.STUTTER_HIT
        )
        private val DROP_POOL = listOf(
            SampleId.AIR_HORN,
            SampleId.CROWD_HEY
        )
    }

    // ── SoundPool state ───────────────────────────────────────────────────────

    private var soundPool: SoundPool? = null

    /** Maps SampleId → SoundPool sound ID returned by SoundPool.load(). */
    private val soundIds     = mutableMapOf<SampleId, Int>()

    /** Tracks which SoundPool sound IDs have fully decoded and are ready to play. */
    private val loadedSounds = mutableSetOf<Int>()

    // ── Settings ──────────────────────────────────────────────────────────────

    /** When false the engine is silent on all auto-triggers; manual pads still work. */
    var isAutoSamplerEnabled: Boolean = true

    /** Volume applied to every sample playback (0.0 – 1.0). */
    var sampleVolume: Float = 0.80f

    // ── Internal ──────────────────────────────────────────────────────────────

    private var samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null

    @Volatile private var isInitialized = false
    @Volatile private var isReleased    = false

    /**
     * Which track-position milestones have already fired for the current track.
     * Cleared whenever [CrossfadeEngineState.currentTrack] changes so milestones
     * reset cleanly after every crossfade.
     *
     * Accessed only from the sequential [observerJob] coroutine.
     */
    private val firedMilestones = mutableSetOf<Float>()

    /**
     * True once the pre-crossfade warning sample has fired for the current track.
     * Prevents the warning from re-firing if timeToNextMixMs bounces around the
     * threshold (e.g., brief pause/resume near the trigger point).
     *
     * Reset together with [firedMilestones] on every track change.
     */
    private var preWarningFired = false

    val isReady: Boolean get() = isInitialized && !isReleased

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALISATION / RELEASE
    // ═════════════════════════════════════════════════════════════════════════

    fun initialize() {
        if (isInitialized) return

        if (isReleased) {
            samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            isReleased   = false
            soundIds.clear()
            loadedSounds.clear()
            firedMilestones.clear()
            preWarningFired = false
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttrs)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, soundId, status ->
                    if (status == 0) {
                        loadedSounds.add(soundId)
                        val name = soundIds.entries.firstOrNull { it.value == soundId }?.key?.name
                        Log.d(TAG, "Loaded: $name (soundId=$soundId)")
                    } else {
                        Log.e(TAG, "Load failed: soundId=$soundId status=$status")
                    }
                }
            }

        loadAllSamples()
        startAutoTriggerObserver()
        isInitialized = true
        Log.d(TAG, "initialize: SamplerEngine ready")
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        observerJob?.cancel()
        samplerScope.cancel()
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loadedSounds.clear()
        isInitialized = false
        Log.d(TAG, "release: SamplerEngine released")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SAMPLE LOADING
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadAllSamples() {
        val pool = soundPool ?: return

        // ── Asset-based samples ───────────────────────────────────────────────
        SampleId.entries.forEach { sampleId ->
            val path = sampleId.assetPath ?: return@forEach
            try {
                val afd     = context.assets.openFd(path)
                val soundId = pool.load(afd, 1)
                afd.close()
                if (soundId > 0) {
                    soundIds[sampleId] = soundId
                    Log.d(TAG, "Queued asset load: ${sampleId.name} → soundId=$soundId")
                } else {
                    Log.e(TAG, "SoundPool.load returned 0 for ${sampleId.name} — check asset path: $path")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Asset load failed for ${sampleId.name}: ${e.message}")
            }
        }

        // ── Synthesized samples ───────────────────────────────────────────────
        samplerScope.launch(Dispatchers.IO) {
            SampleId.entries
                .filter { it.assetPath == null }
                .forEach { sampleId ->
                    try {
                        val file    = synthesizer.getOrGenerate(sampleId)
                        val soundId = pool.load(file.absolutePath, 1)
                        if (soundId > 0) {
                            soundIds[sampleId] = soundId
                            Log.d(TAG, "Queued synthesized load: ${sampleId.name} → soundId=$soundId")
                        } else {
                            Log.e(TAG, "SoundPool.load returned 0 for synthesized ${sampleId.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Synthesized load failed for ${sampleId.name}: ${e.message}")
                    }
                }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Plays [sampleId] immediately at [sampleVolume].
     * Safe to call from any thread — SoundPool.play() is thread-safe.
     */
    fun triggerSample(sampleId: SampleId): Int {
        if (isReleased) return 0
        val pool    = soundPool ?: return 0
        val soundId = soundIds[sampleId] ?: run {
            Log.w(TAG, "triggerSample: ${sampleId.name} not loaded yet — skipping")
            return 0
        }
        if (soundId !in loadedSounds) {
            Log.w(TAG, "triggerSample: ${sampleId.name} still decoding — skipping")
            return 0
        }
        val vol      = sampleVolume.coerceIn(0f, 1f)
        val streamId = pool.play(soundId, vol, vol, 1, 0, 1.0f)
        Log.d(TAG, "triggerSample: ${sampleId.name} streamId=$streamId vol=$vol")
        return streamId
    }

    fun stopSample(streamId: Int) {
        if (isReleased || streamId <= 0) return
        soundPool?.stop(streamId)
        Log.d(TAG, "stopSample: streamId=$streamId")
    }

    fun stopAllSamples() {
        if (isReleased) return
        soundPool?.autoPause()
        Log.d(TAG, "stopAllSamples: autoPause called")
    }

    /**
     * Called once per DJ session when the first track begins playing.
     * See inline comments for the async load race-condition handling.
     */
    fun onSessionStarted() {
        if (isReleased || !isAutoSamplerEnabled) return

        val soundId = soundIds[SampleId.AIR_HORN]
        if (soundId != null && soundId in loadedSounds) {
            triggerSample(SampleId.AIR_HORN)
            Log.d(TAG, "onSessionStarted: AIR_HORN fired immediately")
            return
        }

        Log.d(TAG, "onSessionStarted: AIR_HORN not decoded yet — waiting (slow path)")
        samplerScope.launch {
            val startMs = System.currentTimeMillis()
            val sid     = soundIds[SampleId.AIR_HORN] ?: run {
                Log.w(TAG, "onSessionStarted: AIR_HORN has no soundId")
                return@launch
            }
            while (!isReleased) {
                if (sid in loadedSounds) {
                    triggerSample(SampleId.AIR_HORN)
                    Log.d(TAG, "onSessionStarted: AIR_HORN fired after " +
                            "${System.currentTimeMillis() - startMs}ms wait")
                    return@launch
                }
                if (System.currentTimeMillis() - startMs > SESSION_HORN_TIMEOUT_MS) {
                    Log.w(TAG, "onSessionStarted: AIR_HORN timed out — skipping")
                    return@launch
                }
                delay(SESSION_HORN_POLL_MS)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AUTO-TRIGGER OBSERVER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Observes [CrossfadeEngine.state] and fires samples at four lifecycle points,
     * none of which overlap with an active crossfade:
     *
     * 1. **TRACK CHANGE** — reset per-track state ([firedMilestones], [preWarningFired]).
     *
     * 2. **TRACK MILESTONES** — fire MID_1_POOL at 33 % and MID_2_POOL at 66 % of
     *    track duration. Guard: `!isCrossfading`. These are pure playback moments —
     *    the crowd energy boost happens during the body of the track, not the blend.
     *
     * 3. **PRE-CROSSFADE WARNING** — fire START_POOL when [CrossfadeEngineState.timeToNextMixMs]
     *    drops to ≤ [PRE_CROSSFADE_WARNING_MS] and is still `> 0` (not yet triggered).
     *    Guard: `!isCrossfading`. At 15 s the sample plays through and finishes well
     *    before volume fading begins, acting as a genuine DJ "incoming mix" signal.
     *
     * 4. **POST-CROSSFADE DROP** — fire DROP_POOL when isCrossfading flips true → false
     *    AND the currentTrack has changed. This is the "new track has landed" moment.
     *
     * The [firedMilestones] and [preWarningFired] flags are cleared on every track
     * change so the cycle resets correctly regardless of how the transition happened
     * (manual skip, auto-mix, or session restart).
     */
    private fun startAutoTriggerObserver() {
        observerJob?.cancel()
        observerJob = samplerScope.launch {
            var prev: CrossfadeEngineState? = null

            crossfadeEngine.state.collect { current ->
                val previous = prev
                prev = current

                if (!isAutoSamplerEnabled) return@collect

                // ── 1. Track changed — reset per-track sample state ───────────
                val trackChanged = previous?.currentTrack?.id != current.currentTrack?.id
                if (trackChanged) {
                    firedMilestones.clear()
                    preWarningFired = false
                    Log.d(TAG, "Track changed → milestones and pre-warning reset")
                }

                // Skip all further checks while a crossfade is active.
                // This is the single enforcement point: nothing below this line
                // can ever fire during a blend.
                if (current.isCrossfading) return@collect

                // ── 2. Track-position milestones ──────────────────────────────
                // Only meaningful once we have a real duration.
                val duration = current.currentDurationMs
                val position = current.currentPositionMs
                if (current.isPlaying && duration > 0L) {
                    val progress     = position.toFloat() / duration
                    val prevProgress = if (previous != null && previous.currentDurationMs > 0L)
                        previous.currentPositionMs.toFloat() / previous.currentDurationMs
                    else 0f

                    if (MILESTONE_1 !in firedMilestones
                        && prevProgress < MILESTONE_1
                        && progress   >= MILESTONE_1
                    ) {
                        firedMilestones.add(MILESTONE_1)
                        val sample = MID_1_POOL.random()
                        triggerSample(sample)
                        Log.d(TAG, "Auto: milestone 33% → ${sample.name}")
                    }

                    if (MILESTONE_2 !in firedMilestones
                        && prevProgress < MILESTONE_2
                        && progress   >= MILESTONE_2
                    ) {
                        firedMilestones.add(MILESTONE_2)
                        val sample = MID_2_POOL.random()
                        triggerSample(sample)
                        Log.d(TAG, "Auto: milestone 66% → ${sample.name}")
                    }
                }

                // ── 3. Pre-crossfade warning ───────────────────────────────────
                // timeToNextMixMs is non-null and > 0 when the engine is counting
                // down toward the auto-mix trigger but has not yet fired it.
                // We fire once when it crosses below PRE_CROSSFADE_WARNING_MS.
                if (!preWarningFired) {
                    val ttm = current.timeToNextMixMs
                    if (ttm != null && ttm in 1L..PRE_CROSSFADE_WARNING_MS) {
                        preWarningFired = true
                        val sample = START_POOL.random()
                        triggerSample(sample)
                        Log.d(TAG, "Auto: pre-crossfade warning (${ttm}ms to mix) → ${sample.name}")
                    }
                }

                // ── 4. Post-crossfade drop ─────────────────────────────────────
                // Fires exactly once per completed crossfade, when the incoming
                // track has fully taken over as primary.
                if (previous?.isCrossfading == true && trackChanged) {
                    val sample = DROP_POOL.random()
                    triggerSample(sample)
                    Log.d(TAG, "Auto: drop completed → ${sample.name}")
                }
            }
        }
    }
}