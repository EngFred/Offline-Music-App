package com.engfred.musicplayer.feature_settings.presentation.viewmodel

import android.content.Context
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode

sealed class SettingsEvent {
    data class UpdateTheme(val theme: AppThemeType) : SettingsEvent()
    data class UpdatePlayerLayout(val layout: PlayerLayout) : SettingsEvent()
    data class UpdatePlaylistLayout(val layout: PlaylistLayoutType) : SettingsEvent()
    data class UpdateAudioPreset(val preset: AudioPreset) : SettingsEvent()
    data class UpdateAudioFileTypeFilter(val filter: AudioFileTypeFilter) : SettingsEvent()
    data class UpdateWidgetBackgroundMode(val mode: WidgetBackgroundMode, val context: Context) : SettingsEvent()
}