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
 *    the incoming track so its next beat lands at the same time?"
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
     * Calculates the seek position for the incoming track so its beat-grid phase
     * matches the outgoing track's current beat-grid phase.
     *
     * ── Algorithm ────────────────────────────────────────────────────────────
     * 1. Measure how far the outgoing track is through its current beat cycle
     *    ([primaryPhaseMs] = ms elapsed since the last beat boundary).
     * 2. Express that as a 0–1 fraction of one outgoing beat.
     * 3. Multiply by the incoming beat length to get the equivalent offset in
     *    the incoming track's grid.
     * 4. Add [incomingGuardedFirstBeatMs] (the incoming track's cue point) to
     *    translate to an absolute seek position.
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
     * @param outgoingGuardedFirstBeatMs    Guarded beat-0 of the outgoing track (ms from start).
     * @param outgoingBpm                   BPM of the outgoing track. Must be > 0.
     * @param incomingGuardedFirstBeatMs    Guarded beat-0 of the incoming track (ms from start).
     * @param incomingBpm                   BPM of the incoming track. Must be > 0.
     * @param incomingDurationMs            Total duration of the incoming track in ms.
     * @param minRemainingMs                Safety margin: seek target must leave at least this
     *                                      many ms remaining. Typically effectiveCrossfadeDurationMs × 2.
     * @return Phase-aligned seek position in ms, or [incomingGuardedFirstBeatMs] if any required
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

        val outgoingBeatLenMs = 60_000.0 / outgoingBpm
        val incomingBeatLenMs = 60_000.0 / incomingBpm

        // ── 1. Phase of outgoing track within its current beat ─────────────
        val elapsed      = (primaryCurrentPositionMs - outgoingGuardedFirstBeatMs).coerceAtLeast(0L)
        val rawPhase     = elapsed % outgoingBeatLenMs.toLong()
        // Ensure positive after modulo (Kotlin % can produce negative Long values)
        val primaryPhaseMs = (rawPhase + outgoingBeatLenMs.toLong()) % outgoingBeatLenMs.toLong()

        // ── 2. Fractional position within the beat (0.0 = start, 1.0 = next beat) ──
        val phaseFraction = primaryPhaseMs.toDouble() / outgoingBeatLenMs

        // ── 3. Same fraction mapped onto the incoming beat length ──────────
        val sourcePhaseMs = (phaseFraction * incomingBeatLenMs).toLong()

        // ── 4 & 5. Absolute seek target, clamped to safe range ─────────────
        val raw     = incomingGuardedFirstBeatMs + sourcePhaseMs
        val maxSafe = (incomingDurationMs - minRemainingMs).coerceAtLeast(0L)
        val target  = raw.coerceAtMost(maxSafe).coerceAtLeast(0L)

        Log.d(TAG,
            "[CALC] outPos=${primaryCurrentPositionMs}ms " +
                    "outPhase=${primaryPhaseMs}ms " +
                    "fraction=${"%.4f".format(phaseFraction)} " +
                    "inCue=${incomingGuardedFirstBeatMs}ms " +
                    "srcPhase=${sourcePhaseMs}ms " +
                    "→ seekTarget=${target}ms"
        )
        return target
    }
}