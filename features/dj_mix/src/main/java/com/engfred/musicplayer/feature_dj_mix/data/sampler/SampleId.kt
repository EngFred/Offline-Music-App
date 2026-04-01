package com.engfred.musicplayer.feature_dj_mix.data.sampler

/**
 * Identifies every sample the [SamplerEngine] can play.
 *
 * ── TEST BUILD ────────────────────────────────────────────────────────────────
 * Decision space is reduced to HARMONIC + WIDE_TRANSITION only.
 * Samples that were exclusively auto-triggered by TRANSPARENT, SMOOTH, or
 * POWER_MIX (RISER_SWEEP, REWIND_SWEEP, WHITE_NOISE_UP, IMPACT_HIT, STUTTER_HIT)
 * have been promoted to isManual = true so they still appear as tappable pads
 * in the UI and are never lost.
 *
 * Additionally, some of those samples have been redistributed as extra
 * mid-crossfade auto-triggers inside HARMONIC and WIDE_TRANSITION — see
 * [SamplerEngine.midTriggersFor] for the full new auto-trigger map.
 *
 * To revert pad visibility to its original state, restore isManual = false on
 * RISER_SWEEP, REWIND_SWEEP, WHITE_NOISE_UP, IMPACT_HIT, and STUTTER_HIT, and
 * un-comment the [STRATEGY TEST] blocks in SamplerEngine.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ── Auto-trigger map (TEST BUILD — 2 strategies) ─────────────────────────────
 *
 *  Strategy          │ On START       │ Mid 1              │ Mid 2              │ On DROP
 *  ──────────────────┼────────────────┼────────────────────┼────────────────────┼────────────
 *  HARMONIC          │ REWIND_SWEEP   │ WHITE_NOISE_UP(40%)│ IMPACT_HIT  (70%)  │ CROWD_HEY
 *  WIDE_TRANSITION   │ SIREN          │ RISER_SWEEP  (30%) │ STUTTER_HIT (45%)  │ AIR_HORN
 *  ─────────────────────────────────────────────────────────────────────────────────────────
 *  Session start     │ AIR_HORN (once, fired by DjMixService on first play)
 *
 * ── Original auto-trigger map (FULL 5-strategy — for reference) ──────────────
 *
 *  Strategy          │ On START        │ Mid                      │ On DROP
 *  ──────────────────┼─────────────────┼──────────────────────────┼────────────────────
 *  TRANSPARENT       │ DJ_SCRATCH      │ —                        │ — (silence = effect)
 *  SMOOTH            │ RISER_SWEEP     │ STUTTER_HIT   (50%)      │ AIR_HORN
 *  POWER_MIX         │ WHITE_NOISE_UP  │ IMPACT_HIT    (35%)      │ AIR_HORN
 *  HARMONIC          │ REWIND_SWEEP    │ —                        │ CROWD_HEY
 *  WIDE_TRANSITION   │ SIREN           │ STUTTER_HIT   (45%)      │ CROWD_HEY
 *  ─────────────────────────────────────────────────────────────────────────────────────────
 *
 * @param displayName   Human-readable label shown on the pad grid.
 * @param assetPath     Path inside assets/ for downloaded CC0 OGG samples.
 *                      Null means the sound is synthesized at runtime by
 *                      [SynthesizedSampleGenerator] and cached to internal storage.
 * @param isManual      True  → appears as a UI pad the user can tap.
 *                      False → auto-triggered only; no UI pad shown.
 * @param emoji         Visual glyph for the pad button.
 */
enum class SampleId(
    val displayName: String,
    val assetPath: String?,
    val isManual: Boolean,
    val emoji: String
) {

    // ── Downloaded CC0 OGG samples ────────────────────────────────────────────

    AIR_HORN(
        displayName = "Air Horn",
        assetPath   = "samples/manual/air_horn.ogg",
        isManual    = true,
        emoji       = "📯"
    ),
    DJ_SCRATCH(
        displayName = "Scratch",
        assetPath   = "samples/manual/dj_scratch.ogg",
        isManual    = true,
        emoji       = "💿"
    ),
    SIREN(
        displayName = "Siren",
        assetPath   = "samples/manual/siren.ogg",
        isManual    = true,
        emoji       = "🚨"
    ),
    CROWD_HEY(
        displayName = "Crowd Hey",
        assetPath   = "samples/manual/crowd_hey.ogg",
        isManual    = true,
        emoji       = "🙌"
    ),
    FOGHORN(
        displayName = "Foghorn",
        assetPath   = "samples/manual/foghorn.ogg",
        isManual    = true,
        emoji       = "🔔"
    ),

    // ── Synthesized samples (generated on first launch, cached as WAV) ────────
    //
    // [TEST BUILD] RISER_SWEEP, REWIND_SWEEP, WHITE_NOISE_UP, IMPACT_HIT, STUTTER_HIT
    // have been promoted from isManual = false → true so they remain visible as pads
    // now that TRANSPARENT / SMOOTH / POWER_MIX strategies no longer auto-trigger them.
    // They are ALSO still wired as auto-triggers inside HARMONIC and WIDE_TRANSITION —
    // see SamplerEngine.midTriggersFor() and onCrossfadeStarted().
    //
    // To restore original pad visibility: set isManual = false on all five below.

    RISER_SWEEP(
        displayName = "Riser",
        assetPath   = null,
        isManual    = true,  // [TEST BUILD] was false — promoted to pad
        emoji       = "⬆️"
    ),

    /**
     * Downward vinyl-brake sweep: 1 400 Hz → 180 Hz with pitch flutter.
     * The classic DJ signal for harmonic (half-time / double-time) transitions.
     */
    REWIND_SWEEP(
        displayName = "Rewind",
        assetPath   = null,
        isManual    = true,  // [TEST BUILD] was false — promoted to pad
        emoji       = "⏪"
    ),

    WHITE_NOISE_UP(
        displayName = "Noise Up",
        assetPath   = null,
        isManual    = true,  // [TEST BUILD] was false — promoted to pad
        emoji       = "🌊"
    ),

    WHITE_NOISE_DOWN(
        displayName = "Noise Down",
        assetPath   = null,
        isManual    = true,  // [TEST BUILD] was false — promoted to pad (was already pad-less)
        emoji       = "🌊"
    ),

    IMPACT_HIT(
        displayName = "Impact",
        assetPath   = null,
        isManual    = true,  // [TEST BUILD] was false — promoted to pad
        emoji       = "💥"
    ),

    STUTTER_HIT(
        displayName = "Stutter",
        assetPath   = null,
        isManual    = true,  // [TEST BUILD] was false — promoted to pad
        emoji       = "⚡"
    );

    companion object {
        /** All samples that appear as tappable pads in the UI. */
        val manualPads: List<SampleId> = entries.filter { it.isManual }
    }
}