package com.engfred.musicplayer.feature_settings.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.UpdateInfo
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType

data class SettingsScreenState(
    val selectedTheme: AppThemeType = AppThemeType.NEON_DARK,
    val selectedPlayerLayout: PlayerLayout = PlayerLayout.ETHEREAL_FLOW,
    val playlistLayoutType: PlaylistLayoutType = PlaylistLayoutType.LIST,
    val audioPreset: AudioPreset = AudioPreset.NONE,
    val widgetBackgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.STATIC,
    val audioFileTypeFilter: AudioFileTypeFilter = AudioFileTypeFilter.ALL,
    val mixOfTheDayFilterByDuration: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val updateInfo: UpdateInfo? = null,
)