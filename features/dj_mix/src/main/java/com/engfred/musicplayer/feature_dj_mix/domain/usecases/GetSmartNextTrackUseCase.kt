package com.engfred.musicplayer.feature_dj_mix.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioFile
import javax.inject.Inject
import kotlin.math.abs

/**
 * Selects the best next track from the remaining queue — the way a real DJ would.
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * THE GENIUS DJ ALGORITHM — WHY IT'S DIFFERENT
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Most "smart" playlist algorithms just sort by abs(bpmA - bpmB). That produces
 * technically correct but musically flat sets. A real DJ thinks in dimensions:
 *
 * ── 1. HARMONIC BPM COMPATIBILITY ────────────────────────────────────────────
 *
 * A track at 60 BPM doesn't just "sound slow" next to 120 BPM — it IS 120 BPM
 * in half-time. The crowd hears an energy DROP, not a train wreck. These harmonic
 * relationships are "free" transitions that sound incredible:
 *
 * Ratio 0.5×  : half-time drop   (e.g. 128 BPM → 64 BPM)  — energy valley
 * Ratio 2.0×  : double-time surge (e.g. 70 BPM → 140 BPM)  — crowd goes wild
 * Ratio 0.75× : 3:4 polyrhythm   (e.g. 128 BPM → 96 BPM)  — groove shift
 * Ratio 1.5×  : 2:3 relationship  (e.g. 80 BPM → 120 BPM)  — classic build
 * Ratio 0.667×: 2:3 the other way (e.g. 120 BPM → 80 BPM)  — breakdown
 *
 * Harmonic tracks receive a very large score bonus — they can outrank a "closer"
 * BPM track that would just make the set feel monotonous.
 *
 * ── 2. BPM PROXIMITY SCORING (non-harmonic) ──────────────────────────────────
 *
 * 0–3 BPM delta  → TRANSPARENT   : listeners won't hear the shift at all
 * 3–8 BPM delta  → SMOOTH        : tempo-sync hides it cleanly
 * 8–15 BPM delta → POWER MIX     : needs technique (bass kill, longer fade)
 * > 15 BPM delta → HARD JUMP     : jarring unless it's harmonic
 *
 * We map this to a score that decays linearly, with a minimum floor so nothing
 * is ever completely excluded (queue must not stall).
 *
 * ── 3. ANTI-STAGNATION (BPM variety) ─────────────────────────────────────────
 *
 * A set stuck at 128 BPM for 6 tracks in a row feels like a broken record.
 * If the candidate BPM is within STAGNATION_ZONE_BPM of *most* of the last N
 * played tracks, we apply a gentle penalty — nudging selection toward variety
 * within the compatible zone. This is subtle but makes long sets feel alive.
 *
 * ── 4. GRACEFUL DEGRADATION ──────────────────────────────────────────────────
 *
 * If no BPM data exists for any remaining track, fall back to natural playlist order.
 * The queue never stalls — even the worst-case "hard jump" track is returned rather
 * than blocking playback.
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SCORING FORMULA (per candidate track)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * score = harmonicBonus             // +60 if harmonically compatible
 * + proximityScore          // +100 → 0 → -30 based on effective BPM delta
 * - stagnationPenalty       // -18 if BPM zone is "too repeated" recently
 *
 * The candidate with the highest score is selected.
 */
class GetSmartNextTrackUseCase @Inject constructor() {

    // ── Harmonic ratios ───────────────────────────────────────────────────────

    /**
     * Musically valid BPM speed relationships used by professional DJs.
     * A candidate at bpmA × ratio ≈ bpmB is considered harmonically compatible,
     * even if the raw BPM difference looks large.
     */
    val HARMONIC_RATIOS = floatArrayOf(
        0.5f,   // half-time drop
        0.667f, // 2:3 polyrhythm (slow side)
        0.75f,  // 3:4 (slow side)
        1.0f,   // exact match
        1.333f, // 4:3 (fast side)
        1.5f,   // 3:2 (fast side)
        2.0f    // double-time surge
    )

    /**
     * Within this many BPM of a harmonic ratio target, a track counts as harmonic.
     * 2.5 BPM gives human-style tolerance — two tracks recorded at 127.8 and 63.7
     * should still register as a half-time pair (64 × 2 = 128).
     */
    private val HARMONIC_TOLERANCE_BPM = 2.5f

    // ── Score constants ───────────────────────────────────────────────────────

    /** Large bonus so harmonic matches outrank "closer-BPM-but-boring" candidates. */
    private val HARMONIC_BONUS = 60f

    /** Score when BPM delta is essentially zero. The ceiling for proximity score. */
    private val PERFECT_MATCH_SCORE = 100f

    /**
     * Score decay per BPM of effective delta (after harmonic collapse).
     * At this rate: 8 BPM delta → score of 64; 22 BPM delta → score of 1; 23+ → floor.
     */
    private val BPM_DELTA_PENALTY_PER_BPM = 4.5f

    /**
     * The minimum proximity score so even a hard-jump candidate still has a chance
     * when it is the only option remaining.
     */
    private val PROXIMITY_SCORE_FLOOR = -30f

    /**
     * A candidate is "stagnant" if its BPM is within this range of a recent played BPM.
     * 2 BPM = essentially same zone; tighter than the crossfade engine's BEAT_SNAP.
     */
    private val STAGNATION_ZONE_BPM = 2f

    /** Penalty applied when the candidate BPM zone has been overplayed recently. */
    private val STAGNATION_PENALTY = 18f

    /**
     * Consider this many of the most recently played tracks for anti-stagnation.
     * 3 tracks means: if ≥ 2 of the last 3 tracks shared your BPM zone, you're penalised.
     */
    private val STAGNATION_HISTORY_DEPTH = 3

    // ── Main operator ─────────────────────────────────────────────────────────

    /**
     * Returns the best next track to play, according to the genius DJ scoring algorithm.
     *
     * All parameters after [tolerance] have sensible defaults so existing callers
     * (e.g. [performRebuild] in the ViewModel) don't need to change their call sites
     * unless they want to pass richer context.
     *
     * @param currentBpm          BPM of the track currently playing.
     * @param remainingQueue      Tracks not yet played — must NOT include the current track.
     * @param bpmCache            Map of audioFileId → analysed BPM.
     * @param tolerance           Maximum BPM delta for a "standard" in-tolerance match.
     * Harmonic matches are ALWAYS considered regardless of this value.
     * @param recentBpms          BPMs of the last [STAGNATION_HISTORY_DEPTH] played tracks,
     * oldest-first. Controls the anti-stagnation penalty.
     * Defaults to empty (no history = no stagnation penalty).
     * @return The highest-scoring [AudioFile], or null when [remainingQueue] is empty.
     */
    operator fun invoke(
        currentBpm: Float,
        remainingQueue: List<AudioFile>,
        bpmCache: Map<Long, Float>,
        tolerance: Float,
        recentBpms: List<Float> = emptyList()
    ): AudioFile? {
        if (remainingQueue.isEmpty()) return null

        val withBpm    = remainingQueue.filter { bpmCache.containsKey(it.id) }
        val withoutBpm = remainingQueue.filterNot { bpmCache.containsKey(it.id) }

        // No BPM data for any remaining track → natural order (analysis hasn't finished)
        if (withBpm.isEmpty()) return remainingQueue.first()

        // Recent history window for anti-stagnation check
        val recentHistory = recentBpms.takeLast(STAGNATION_HISTORY_DEPTH)

        // Score every BPM-analysed candidate
        val scored = withBpm.map { track ->
            val candidateBpm = bpmCache[track.id]!!
            val score = scoreCandidate(
                currentBpm      = currentBpm,
                candidateBpm    = candidateBpm,
                recentHistory   = recentHistory
            )
            track to score
        }

        // Return the highest scorer.
        // If somehow all scores are extremely negative (e.g. one track left, no BPM data),
        // fall back to an un-analysed track or the raw first item — queue must not stall.
        return scored.maxByOrNull { it.second }?.first
            ?: withoutBpm.firstOrNull()
            ?: remainingQueue.first()
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    /**
     * Computes the composite DJ-quality score for one candidate track.
     *
     * Score components (see class-level kdoc for the full model):
     * + [HARMONIC_BONUS]          if candidate is harmonically compatible with current
     * + [PERFECT_MATCH_SCORE]..0  based on effective BPM delta (harmonic-collapsed)
     * - [STAGNATION_PENALTY]      if this BPM zone has been overplayed recently
     *
     * @return Composite score — higher is better. Can be negative.
     */
    private fun scoreCandidate(
        currentBpm: Float,
        candidateBpm: Float,
        recentHistory: List<Float>
    ): Float {
        var score = 0f

        // ── Component 1: Harmonic compatibility ────────────────────────────────
        // Check before the proximity score so the harmonic bonus adds to it,
        // not replaces it. A perfect harmonic + close BPM is doubly rewarded.
        val harmonic = isHarmonicallyCompatible(currentBpm, candidateBpm)
        if (harmonic) score += HARMONIC_BONUS

        // ── Component 2: BPM proximity (using harmonic-collapsed effective delta) ─
        // effectiveDelta is the minimum BPM distance to ANY harmonic of currentBpm.
        // This means a half-time track (120→60) collapses to ~0 delta, correctly
        // reflecting that the transition is musically clean.
        val effectiveDelta = minimumHarmonicDelta(currentBpm, candidateBpm)
        val proximityScore = (PERFECT_MATCH_SCORE - effectiveDelta * BPM_DELTA_PENALTY_PER_BPM)
            .coerceAtLeast(PROXIMITY_SCORE_FLOOR)
        score += proximityScore

        // ── Component 3: Anti-stagnation penalty ──────────────────────────────
        // Count how many recent BPMs fall within STAGNATION_ZONE_BPM of this candidate.
        // If the majority of recent history is in the same BPM zone, penalise.
        if (recentHistory.isNotEmpty()) {
            val stagnantCount = recentHistory.count { recentBpm ->
                abs(recentBpm - candidateBpm) <= STAGNATION_ZONE_BPM
            }
            val stagnantThreshold = (recentHistory.size / 2.0f).coerceAtLeast(1f)
            if (stagnantCount >= stagnantThreshold) {
                score -= STAGNATION_PENALTY
            }
        }

        return score
    }

    // ── Harmonic utilities ────────────────────────────────────────────────────

    /**
     * Returns true if [bpmB] is harmonically compatible with [bpmA].
     *
     * Compatibility means: bpmB ≈ bpmA × ratio for some ratio in [HARMONIC_RATIOS],
     * within [HARMONIC_TOLERANCE_BPM].
     *
     * Examples:
     * isHarmonicallyCompatible(120f,  60f) = true   (half-time, ratio 0.5)
     * isHarmonicallyCompatible(128f,  64f) = true   (half-time, ratio 0.5)
     * isHarmonicallyCompatible(120f, 180f) = true   (ratio 1.5)
     * isHarmonicallyCompatible(130f,  87f) = false  (no clean relationship)
     *
     * This function is also called from [CrossfadeEngine.computeMixDecision]
     * and [DjMixViewModel.performRebuild] via the instance — no duplication needed.
     */
    fun isHarmonicallyCompatible(bpmA: Float, bpmB: Float): Boolean {
        if (bpmA <= 0f || bpmB <= 0f) return false
        return HARMONIC_RATIOS.any { ratio ->
            abs(bpmA * ratio - bpmB) <= HARMONIC_TOLERANCE_BPM
        }
    }

    /**
     * Returns the minimum BPM delta from [candidateBpm] to any harmonic ratio of [currentBpm].
     *
     * For direct BPM comparison, this equals abs(currentBpm - candidateBpm).
     * For a half-time track (120→60), this collapses to ≈0 — correctly reflecting
     * the musical reality that these two tracks live in the same rhythmic space.
     *
     * Used by [scoreCandidate] and [CrossfadeEngine.computeMixDecision].
     */
    fun minimumHarmonicDelta(currentBpm: Float, candidateBpm: Float): Float {
        if (currentBpm <= 0f || candidateBpm <= 0f) return abs(currentBpm - candidateBpm)
        return HARMONIC_RATIOS.minOf { ratio -> abs(currentBpm * ratio - candidateBpm) }
    }
}