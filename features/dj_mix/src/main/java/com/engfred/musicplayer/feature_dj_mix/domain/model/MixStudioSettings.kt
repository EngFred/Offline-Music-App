package com.engfred.musicplayer.feature_dj_mix.domain.model

/**
 * Cue-point offset options exposed to the user, in seconds.
 *
 * 0  = first detected beat, no minimum offset enforced
 * 5  = light intro cushion (live-set style)
 * 15 = default — matches the former hardcoded constant
 * 30 = maximum — for very long intros (podcast segments, YouTube rips)
 *
 * These map directly to [MixStudioSettings.cuePointOffsetSec].
 * The engine phase-advances the incoming track's raw aubio beat to the
 * nearest beat AT OR AFTER this offset, so a value of 5 s could result
 * in a firstBeatMs of e.g. 5.35 s — not exactly 5 s — depending on BPM.
 */
val CUE_POINT_OPTIONS_SEC = listOf(0, 5, 10, 15, 20, 25, 30)

/**
 * Holds the user-configurable parameters for the DJ Mix feature.
 *
 * @param crossfadeDurationSec    Length of the volume crossfade transition (2–12 s).
 * @param bpmTolerance            Maximum BPM delta still considered "compatible" (±5–±20 BPM).
 * @param isRealMixMode           If true, tracks are mixed early based on either the halfway
 *                                point (default) or [maxTrackDurationSec] if [useManualMaxDuration]
 *                                is true.
 * @param maxTrackDurationSec     Maximum time a track plays before an early mix is triggered
 *                                (default: 2 min 26 sec). Only active when [useManualMaxDuration]
 *                                is true.
 * @param loopQueue               If true, the playlist resets and loops when exhausted.
 * @param useManualMaxDuration    If false (default), Real Mix Mode triggers at the track's
 *                                halfway point offset by firstBeatMs.
 *                                If true, [maxTrackDurationSec] is used instead.
 * @param autoSamplerEnabled      If true, the SamplerEngine fires transition sounds automatically
 *                                at crossfade lifecycle events.
 * @param sampleVolume            Master volume for all sampler playback (0.0 – 1.0).
 *                                Default 1.0.
 * @param cuePointOffsetSec       The minimum position (in seconds) at which the incoming track's
 *                                first audible beat is placed. The engine phase-advances the raw
 *                                aubio beat until it clears this window, preserving beat-grid
 *                                alignment. Allowed values: [CUE_POINT_OPTIONS_SEC].
 *
 *                                ── Mix-timing effect ────────────────────────────────────────
 *                                The halfway-mix trigger is:
 *                                    triggerMs = (trackDuration / 2) + guardedFirstBeatMs
 *
 *                                guardedFirstBeatMs ≈ cuePointOffsetSec × 1000 ms, so a larger
 *                                cue point delays the mix trigger by roughly the same amount:
 *
 *                                  cue=0s  → 3-min track triggers at ~1:30
 *                                  cue=20s → 3-min track triggers at ~1:50
 *
 *                                This ensures the outgoing track plays long enough to compensate
 *                                for the unheard intro of the incoming track, giving the listener
 *                                a balanced experience on both sides of the transition.
 */
data class MixStudioSettings(
    val bpmTolerance: Float           = 5f,
    val isRealMixMode: Boolean        = true,
    val maxTrackDurationSec: Int      = 146,
    val loopQueue: Boolean            = false,
    val useManualMaxDuration: Boolean = false,
    val autoSamplerEnabled: Boolean   = true,
    val sampleVolume: Float           = 1.0f,
    val cuePointOffsetSec: Int        = 15,
)