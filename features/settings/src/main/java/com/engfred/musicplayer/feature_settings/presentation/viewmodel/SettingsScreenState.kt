package com.engfred.musicplayer.feature_settings.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.domain.model.FilterOption

data class SettingsScreenState(
    val selectedTheme: AppThemeType = AppThemeType.CLASSIC_BLUE,
    val selectedPlayerLayout: PlayerLayout = PlayerLayout.ETHEREAL_FLOW,
    val playlistLayoutType: PlaylistLayoutType = PlaylistLayoutType.LIST,
    val audioPreset: AudioPreset = AudioPreset.NONE,
    val selectedFilterOption: FilterOption = FilterOption.DATE_ADDED_DESC,
    val widgetBackgroundMode: WidgetBackgroundMode = WidgetBackgroundMode.STATIC,
    val isLoading: Boolean = false,
    val error: String? = null,
    val audioFileTypeFilter: AudioFileTypeFilter = AudioFileTypeFilter.ALL
)
