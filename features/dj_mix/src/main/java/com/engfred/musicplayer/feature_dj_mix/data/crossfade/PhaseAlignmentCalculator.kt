package com.engfred.musicplayer.feature_dj_mix.data.crossfade

import android.util.Log

/**
 * Pure, stateless calculator for beat-phase-aligned seek positions.
 *
 * ── Responsibility ────────────────────────────────────────────────────────────
 * When two tracks overlap during a crossfade, their kick drums and downbeats
 * must land at the same wall-clock moment. If they don't, the listener hears a
 * "flam" — two rapid hits that sound like a mistake, not a mix.
 *
 * This class answers one question:
 *   "Given that the outgoing track is currently at position X, where do I seek
 *    the incoming track so its next downbeat lands at the same time?"
 *
 * ── Why bar-level (4-beat measure) alignment ──────────────────────────────────
 * Single-beat alignment keeps both tracks on-beat but does NOT guarantee they
 * are on the SAME beat within the bar. Beat 3 of the outgoing track aligned to
 * beat 1 of the incoming track is technically rhythmic but musically wrong —
 * the kick/snare pattern lands in the wrong relative positions and the listener
 * hears it as a half-bar offset.
 *
 * By operating on a 4-beat measure cycle instead of a single beat, fraction 0.0
 * always maps beat 1 → beat 1, fraction 0.5 maps beat 3 → beat 3, and so on.
 * This is the same approach used in Pioneer's CDJ SYNC mode and Ableton Link.
 *
 * PREREQUISITE: [outgoingGuardedFirstBeatMs] and [incomingGuardedFirstBeatMs]
 * must both sit on beat 1 of a bar (a true downbeat). This is guaranteed by
 * CrossfadeEngine.applyFirstBeatGuard(), which bar-aligns the guarded cue point
 * before passing it here. If the cue points are not bar-aligned, the fraction
 * mapping is still correct within its own grid but downbeat locking is lost.
 *
 * ── Why it is its own file ────────────────────────────────────────────────────
 * This is pure arithmetic — no Android APIs, no coroutines, no mutable state.
 * Isolating it here means:
 *   • It is unit-testable with plain JUnit, no Robolectric or instrumentation.
 *   • [CrossfadeEngine] stays focused on player lifecycle and coroutine work.
 *   • Future features (e.g. waveform alignment preview) can reuse this logic
 *     without pulling in the full engine.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * All functions are pure with no shared state. Safe to call from any thread.
 */
internal object PhaseAlignmentCalculator {

    private const val TAG = "PhaseAlignCalc"

    /**
     * Calculates the seek position for the incoming track so its bar-grid phase
     * matches the outgoing track's current bar-grid phase, ensuring downbeat-to-
     * downbeat alignment across the crossfade.
     *
     * ── Algorithm ────────────────────────────────────────────────────────────
     * 1. Measure how far the outgoing track is through its current 4-beat BAR
     *    ([primaryPhaseMs] = ms elapsed since the last downbeat boundary).
     * 2. Express that as a 0–1 fraction of one outgoing bar.
     * 3. Multiply by the incoming bar length to get the equivalent offset in
     *    the incoming track's bar grid.
     * 4. Add [incomingGuardedFirstBeatMs] (the incoming track's downbeat cue point)
     *    to translate to an absolute seek position.
     * 5. Clamp so the seek never lands within [minRemainingMs] of the end
     *    of the incoming track (the fade needs room to complete).
     *
     * ── Timing requirement ────────────────────────────────────────────────────
     * [primaryCurrentPositionMs] MUST be sampled at the last possible moment
     * before the seek is issued. Any gap between sampling and seeking introduces
     * a phase error equal to that gap. In [CrossfadeEngine], this value is read
     * AFTER the secondary player is confirmed playing (at muted volume), so the
     * sampling-to-seeking delay is < 50 ms.
     *
     * @param primaryCurrentPositionMs      Current playhead of the outgoing track (ms). Sample NOW.
     * @param outgoingGuardedFirstBeatMs    Bar-aligned beat-1 cue of the outgoing track (ms from start).
     * @param outgoingBpm                   BPM of the outgoing track. Must be > 0.
     * @param incomingGuardedFirstBeatMs    Bar-aligned beat-1 cue of the incoming track (ms from start).
     * @param incomingBpm                   BPM of the incoming track. Must be > 0.
     * @param incomingDurationMs            Total duration of the incoming track in ms.
     * @param minRemainingMs                Safety margin: seek target must leave at least this
     *                                      many ms remaining. Typically effectiveCrossfadeDurationMs × 2.
     * @return Bar-phase-aligned seek position in ms, or [incomingGuardedFirstBeatMs] if any required
     *         input is invalid (safe fallback — the track still enters at its cue point).
     */
    fun calculate(
        primaryCurrentPositionMs: Long,
        outgoingGuardedFirstBeatMs: Long,
        outgoingBpm: Float,
        incomingGuardedFirstBeatMs: Long,
        incomingBpm: Float,
        incomingDurationMs: Long,
        minRemainingMs: Long
    ): Long {
        // ── Guard: insufficient data → fall back to the bare cue point ────────
        if (outgoingBpm <= 0f || incomingBpm <= 0f ||
            incomingDurationMs <= 0L || incomingGuardedFirstBeatMs <= 0L
        ) {
            Log.d(TAG, "[CALC] Insufficient data — fallback cue: ${incomingGuardedFirstBeatMs}ms")
            return incomingGuardedFirstBeatMs
        }

        // ── Bar length = 4 beats ───────────────────────────────────────────────
        // Operating on a full 4-beat measure ensures beat 1 maps to beat 1,
        // beat 3 maps to beat 3, etc. Single-beat operation would align within
        // a beat but not within the bar, causing a half-bar-offset sound.
        val outgoingBarLenMs = (60_000.0 / outgoingBpm) * 4
        val incomingBarLenMs = (60_000.0 / incomingBpm) * 4

        // ── 1. Phase of outgoing track within its current BAR ─────────────────
        val elapsed        = (primaryCurrentPositionMs - outgoingGuardedFirstBeatMs).coerceAtLeast(0L)
        val rawPhase       = elapsed % outgoingBarLenMs.toLong()
        // Ensure positive after modulo (Kotlin % can produce negative Long values)
        val primaryPhaseMs = (rawPhase + outgoingBarLenMs.toLong()) % outgoingBarLenMs.toLong()

        // ── 2. Fractional position within the BAR (0.0 = beat 1, 0.5 = beat 3) ──
        val phaseFraction = primaryPhaseMs.toDouble() / outgoingBarLenMs

        // ── 3. Same fraction mapped onto the incoming BAR length ──────────────
        val sourcePhaseMs = (phaseFraction * incomingBarLenMs).toLong()

        // ── 4 & 5. Absolute seek target, clamped to safe range ─────────────
        val raw     = incomingGuardedFirstBeatMs + sourcePhaseMs
        val maxSafe = (incomingDurationMs - minRemainingMs).coerceAtLeast(0L)
        val target  = raw.coerceAtMost(maxSafe).coerceAtLeast(0L)

        Log.d(TAG,
            "[CALC] outPos=${primaryCurrentPositionMs}ms " +
                    "outBarPhase=${primaryPhaseMs}ms " +
                    "fraction=${"%.4f".format(phaseFraction)} " +
                    "inCue=${incomingGuardedFirstBeatMs}ms " +
                    "srcPhase=${sourcePhaseMs}ms " +
                    "→ seekTarget=${target}ms"
        )
        return target
    }
}