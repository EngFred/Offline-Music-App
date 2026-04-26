package com.engfred.musicplayer.core.domain.model

import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType

// ── DJ Mix playlist filter ────────────────────────────────────────────────────
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

    // ── Mix of the Day ─────────────────────────────────────────────────────────
    /**
     * When true, only tracks whose duration is ≤ [MIX_OF_THE_DAY_MAX_DURATION_MS]
     * are eligible for the daily mix. Disabled by default so the mix draws
     * from the full library until the user opts in.
     */
    val mixOfTheDayFilterByDuration: Boolean = false,

    // ── DJ Mix settings ────────────────────────────────────────────────────────
    val isRealMixMode: Boolean        = true,
    val loopQueue: Boolean            = false,
    val isDualDeckMode: Boolean       = false,

    // ── Added Filter State ─────────────────────────────────────────────────────
    val djMixPlaylistFilter: DjMixPlaylistFilter = DjMixPlaylistFilter.ALL,

    // ── Sampler settings ───────────────────────────────────────────────────────
    val autoSamplerEnabled: Boolean   = true,
    val sampleVolume: Float           = 1.0f,

    val customPlayerBackgroundUri: String? = null,
) {
    companion object {
        /** 5 minutes in milliseconds — the hard cap applied when [mixOfTheDayFilterByDuration] is true. */
        const val MIX_OF_THE_DAY_MAX_DURATION_MS: Long = 5 * 60 * 1_000L
    }
}