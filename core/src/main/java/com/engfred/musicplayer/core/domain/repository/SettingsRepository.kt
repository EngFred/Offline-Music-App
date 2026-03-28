package com.engfred.musicplayer.core.domain.repository

import com.engfred.musicplayer.core.domain.model.AppSettings
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.DjMixPlaylistFilter
import com.engfred.musicplayer.core.domain.model.FilterOption
import com.engfred.musicplayer.core.domain.model.LastPlaybackState
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.PlaylistSortOption
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getAppSettings(): Flow<AppSettings>
    suspend fun updateTheme(theme: AppThemeType)
    suspend fun updatePlayerLayout(layout: PlayerLayout)
    suspend fun updatePlaylistLayout(layout: PlaylistLayoutType)
    suspend fun updatePlaylistSortOption(sortOption: PlaylistSortOption)
    suspend fun updateFilterOption(filterOption: FilterOption)
    fun getFilterOption(): Flow<FilterOption>
    fun getAudioFileTypeFilter(): Flow<AudioFileTypeFilter>
    suspend fun updateAudioFileTypeFilter(filter: AudioFileTypeFilter)
    suspend fun updateRepeatMode(repeatMode: RepeatMode)
    suspend fun updateAudioPreset(preset: AudioPreset)
    fun getLastPlaybackState(): Flow<LastPlaybackState>
    suspend fun saveLastPlaybackState(state: LastPlaybackState)
    suspend fun updateWidgetBackgroundMode(mode: WidgetBackgroundMode)
    suspend fun getLastScanTimestamp(): Long
    suspend fun updateLastScanTimestamp(timestamp: Long)

    // ── DJ Mix ────────────────────────────────────────────────────────────
    suspend fun updateDjCrossfadeDuration(seconds: Int)
    suspend fun updateDjBpmTolerance(tolerance: Float)
    suspend fun updateDjRealMixMode(enabled: Boolean)
    suspend fun updateDjMaxTrackDuration(seconds: Int)
    suspend fun updateDjLoopQueue(enabled: Boolean)

    /**
     * Persists the toggle between "mix at halfway" (default) and
     * "mix after manual max duration".
     */
    suspend fun updateDjManualMaxDuration(enabled: Boolean)

    // ── Mix of the Day ────────────────────────────────────────────────────────
    suspend fun getLastMixOfTheDayTimestamp(): Long
    suspend fun updateLastMixOfTheDayTimestamp(timestamp: Long)

    suspend fun updateDjAutoSampler(enabled: Boolean)
    suspend fun updateDjSampleVolume(volume: Float)

    suspend fun updateDjMixPlaylistFilter(filter: DjMixPlaylistFilter)
}