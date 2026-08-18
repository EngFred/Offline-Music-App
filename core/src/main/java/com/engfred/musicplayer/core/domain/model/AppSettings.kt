package com.engfred.musicplayer.core.domain.model

import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType

data class AppSettings(
    val selectedTheme: AppThemeType = AppThemeType.NEON_DARK,
    val selectedPlayerLayout: PlayerLayout,
    val playlistLayoutType: PlaylistLayoutType,
    val playlistSortOption: PlaylistSortOption = PlaylistSortOption.DATE_CREATED_ASC,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val audioPreset: AudioPreset = AudioPreset.NONE,
    val widgetBackgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.STATIC,
    val customPlayerBackgroundUri: String? = null,
)