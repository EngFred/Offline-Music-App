package com.engfred.musicplayer.feature_dj_mix.data.sampler

/**
 ── Auto-trigger map (TEST BUILD — 2 strategies) ─────────────────────────────
 *
 *  Strategy          │ On START       │ Mid 1              │ Mid 2              │ On DROP
 *  ──────────────────┼────────────────┼────────────────────┼────────────────────┼────────────
 *  HARMONIC          │ REWIND_SWEEP   │ WHITE_NOISE_UP(40%)│ IMPACT_HIT  (70%)  │ CROWD_HEY
 *  WIDE_TRANSITION   │ SIREN          │ RISER_SWEEP  (30%) │ STUTTER_HIT (45%)  │ AIR_HORN
 *  ─────────────────────────────────────────────────────────────────────────────────────────
 *  Session start     │ AIR_HORN (once, fired by DjMixService on first play)
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

    RISER_SWEEP(
        displayName = "Riser",
        assetPath   = null,
        isManual    = true,
        emoji       = "⬆️"
    ),

    /**
     * Downward vinyl-brake sweep: 1 400 Hz → 180 Hz with pitch flutter.
     * The classic DJ signal for harmonic (half-time / double-time) transitions.
     */
    REWIND_SWEEP(
        displayName = "Rewind",
        assetPath   = null,
        isManual    = true,
        emoji       = "⏪"
    ),

    WHITE_NOISE_UP(
        displayName = "Noise Up",
        assetPath   = null,
        isManual    = true,
        emoji       = "🌊"
    ),

    WHITE_NOISE_DOWN(
        displayName = "Noise Down",
        assetPath   = null,
        isManual    = true,
        emoji       = "🌊"
    ),

    IMPACT_HIT(
        displayName = "Impact",
        assetPath   = null,
        isManual    = true,
        emoji       = "💥"
    ),

    STUTTER_HIT(
        displayName = "Stutter",
        assetPath   = null,
        isManual    = true,
        emoji       = "⚡"
    );

    companion object {
        /** All samples that appear as tappable pads in the UI. */
        val manualPads: List<SampleId> = entries.filter { it.isManual }
    }
}