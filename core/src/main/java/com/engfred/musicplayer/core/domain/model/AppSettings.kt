package com.engfred.musicplayer.core.domain.model

import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType

/**
 * Represents the application's user settings.
 * This is a pure domain model.
 *
 * DJ Mix fields have defaults so existing DataStore serialisation is
 * backwards-compatible — DataStore returns the default for any key not yet written.
 */
data class AppSettings(
    val selectedTheme: AppThemeType = AppThemeType.CLASSIC_LIGHT,
    val selectedPlayerLayout: PlayerLayout,
    val playlistLayoutType: PlaylistLayoutType,
    val playlistSortOption: PlaylistSortOption = PlaylistSortOption.DATE_CREATED_ASC,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val audioPreset: AudioPreset = AudioPreset.NONE,
    val widgetBackgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.STATIC,

    // ── DJ Mix settings ────────────────────────────────────────────────────────
    val crossfadeDurationSec: Int     = 5,
    val bpmTolerance: Float           = 10f,
    val isRealMixMode: Boolean        = false,
    val maxTrackDurationSec: Int      = 146,
    val loopQueue: Boolean            = true,
    val useManualMaxDuration: Boolean = false,

    // ── Sampler settings ───────────────────────────────────────────────────────
    /** Whether the algorithm-driven sampler fires automatically during mixes. */
    val autoSamplerEnabled: Boolean   = true,
    /** Master volume for all sample playback (0.0 – 1.0). */
    val sampleVolume: Float           = 0.75f
)