package com.engfred.musicplayer.feature_dj_mix.domain.model

/**
 * Holds the user-configurable parameters for the DJ Mix feature.
 *
 * @param bpmTolerance        Maximum BPM delta still considered "compatible" for queue ordering (±5–±20 BPM).
 * @param isRealMixMode       Controls *when* the automatic crossfade fires — not whether it fires.
 *                            Both modes use the full equal-power crossfade with tempo sync and bass kill.
 *
 *                            **true  (Real Mix)** — crossfade triggers 60 seconds before track end.
 *                            The full intro of the next track plays over the outro of the current one,
 *                            giving a true DJ blend. This is the recommended setting for DJ-style mixes.
 *
 *                            **false (Near-End)** — crossfade triggers ~10 seconds before track end
 *                            (crossfadeDurationMs + 2 s). The listener hears the current song nearly
 *                            to completion before it seamlessly blends into the next track. Good for
 *                            when you want to hear every song but still keep playback continuous.
 *
 * @param loopQueue           If true, the playlist resets and loops when exhausted.
 * @param autoSamplerEnabled  If true, the SamplerEngine fires transition sounds automatically
 *                            at crossfade lifecycle events. Only active when [isRealMixMode] = true,
 *                            since the early 60 s overlap gives the sampler room to breathe.
 * @param sampleVolume        Master volume for all sampler playback (0.0 – 1.0). Default 1.0.
 */
data class MixStudioSettings(
    val bpmTolerance: Float         = 5f,
    val isRealMixMode: Boolean      = true,
    val loopQueue: Boolean          = false,
    val autoSamplerEnabled: Boolean = true,
    val sampleVolume: Float         = 1.0f,
)