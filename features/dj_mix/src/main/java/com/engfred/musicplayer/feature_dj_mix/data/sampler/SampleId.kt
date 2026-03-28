package com.engfred.musicplayer.feature_dj_mix.data.sampler

/**
 * Identifies every sample the [SamplerEngine] can play.
 *
 * MANUAL samples are exposed as tappable pads in the DJ screen UI.
 * AUTO-only samples are triggered exclusively by crossfade lifecycle hooks — they
 * have no pad button but can still be called via [SamplerEngine.triggerSample].
 *
 * @param displayName   Human-readable label shown on the pad grid.
 * @param assetPath     Path inside assets/ for downloaded CC0 samples.
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

    // ── Downloaded CC0 samples ────────────────────────────────────────────────

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

    RISER_SWEEP(
        displayName = "Riser",
        assetPath   = null,
        isManual    = false,
        emoji       = "⬆️"
    ),
    WHITE_NOISE_UP(
        displayName = "Noise Up",
        assetPath   = null,
        isManual    = false,
        emoji       = "🌊"
    ),
    WHITE_NOISE_DOWN(
        displayName = "Noise Down",
        assetPath   = null,
        isManual    = false,
        emoji       = "🌊"
    ),
    IMPACT_HIT(
        displayName = "Impact",
        assetPath   = null,
        isManual    = false,
        emoji       = "💥"
    ),
    STUTTER_HIT(
        displayName = "Stutter",
        assetPath   = null,
        isManual    = false,
        emoji       = "⚡"
    );

    companion object {
        /** All samples that appear as tappable pads in the UI. */
        val manualPads: List<SampleId> = entries.filter { it.isManual }
    }
}