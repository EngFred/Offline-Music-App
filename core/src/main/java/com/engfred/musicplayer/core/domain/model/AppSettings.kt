package com.engfred.musicplayer.core.domain.model

import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType

/**
 * Represents the application's user settings.
 * This is a pure domain model.
 *
 * DJ Mix fields ([crossfadeDurationSec], [bpmTolerance]) have defaults so existing
 * DataStore serialisation is backwards-compatible — proto/preferences DataStore
 * returns the default value for any key not yet written.
 */
data class AppSettings(
    val selectedTheme: AppThemeType = AppThemeType.CLASSIC_LIGHT,
    val selectedPlayerLayout: PlayerLayout,
    val playlistLayoutType: PlaylistLayoutType,
    val playlistSortOption: PlaylistSortOption = PlaylistSortOption.DATE_CREATED_ASC,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val audioPreset: AudioPreset = AudioPreset.NONE,
    val widgetBackgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.STATIC,
    // ── DJ Mix settings ────────────────────────────────────────────────────
    /** Length of the crossfade transition in seconds. Range: 2–12. */
    val crossfadeDurationSec: Int = 5,
    /** Maximum BPM difference still considered "compatible" for smart queue ordering. Range: 5–20. */
    val bpmTolerance: Float = 10f
)