package com.engfred.musicplayer.core.domain.model

import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType

// ── Added Filter Enum ─────────────────────────────────────────────────────────
enum class DjMixPlaylistFilter {
    ALL, AUTOMATIC, USER
}

data class AppSettings(
    val selectedTheme: AppThemeType = AppThemeType.NEON_DARK,
    val selectedPlayerLayout: PlayerLayout,
    val playlistLayoutType: PlaylistLayoutType,
    val playlistSortOption: PlaylistSortOption = PlaylistSortOption.DATE_CREATED_ASC,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val audioPreset: AudioPreset = AudioPreset.NONE,
    val widgetBackgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.STATIC,

    // ── DJ Mix settings ────────────────────────────────────────────────────────
    val crossfadeDurationSec: Int     = 5,
    val bpmTolerance: Float           = 10f,
    val isRealMixMode: Boolean        = true,
    val maxTrackDurationSec: Int      = 146,
    val loopQueue: Boolean            = false,
    val useManualMaxDuration: Boolean = false,

    // ── Added Filter State ─────────────────────────────────────────────────────
    val djMixPlaylistFilter: DjMixPlaylistFilter = DjMixPlaylistFilter.ALL,

    // ── Sampler settings ───────────────────────────────────────────────────────
    val autoSamplerEnabled: Boolean   = true,
    val sampleVolume: Float           = 1.0f
)