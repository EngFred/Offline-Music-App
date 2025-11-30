package com.engfred.musicplayer.feature_settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.engfred.musicplayer.core.domain.model.AppSettings
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.FilterOption
import com.engfred.musicplayer.core.domain.model.LastPlaybackState
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.PlaylistSortOption
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    companion object {
        private val SELECTED_THEME = stringPreferencesKey("selected_theme")
        private val SELECTED_PLAYER_LAYOUT = stringPreferencesKey("selected_player_layout")
        private val PLAYLIST_LAYOUT_TYPE = stringPreferencesKey("playlist_layout_type")
        private val PLAYLIST_SORT_OPTION = stringPreferencesKey("playlist_sort_option")
        private val SELECTED_FILTER_OPTION = stringPreferencesKey("selected_filter_option")
        private val REPEAT_MODE = stringPreferencesKey("repeat_mode")
        private val SELECTED_AUDIO_PRESET = stringPreferencesKey("selected_audio_preset")
        private val SELECT_WIDGET_BACKGROUND_MODE = stringPreferencesKey("widget_background_mode")
        private val AUDIO_FILE_TYPE_FILTER = stringPreferencesKey("audio_file_type_filter")

        private val LAST_PLAYED_AUDIO_ID = longPreferencesKey("last_played_audio_id")
        private val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        private val LAST_QUEUE_IDS = stringPreferencesKey("last_queue_ids")

        private val LAST_SCAN_TIMESTAMP = longPreferencesKey("last_scan_timestamp")
    }

    override fun getAppSettings(): Flow<AppSettings> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                val selectedTheme = AppThemeType.valueOf(
                    preferences[SELECTED_THEME] ?: AppThemeType.CLASSIC_BLUE.name
                )
                val selectedPlayerLayout = PlayerLayout.valueOf(
                    preferences[SELECTED_PLAYER_LAYOUT] ?: PlayerLayout.ETHEREAL_FLOW.name
                )
                val playlistLayoutType = PlaylistLayoutType.valueOf(
                    preferences[PLAYLIST_LAYOUT_TYPE] ?: PlaylistLayoutType.LIST.name
                )
                val repeatMode = RepeatMode.valueOf(
                    preferences[REPEAT_MODE] ?: RepeatMode.OFF.name
                )
                val selectedAudioPreset = AudioPreset.valueOf(
                    preferences[SELECTED_AUDIO_PRESET] ?: AudioPreset.NONE.name
                )

                val playlistSortOption = try {
                    PlaylistSortOption.valueOf(
                        preferences[PLAYLIST_SORT_OPTION] ?: PlaylistSortOption.DATE_CREATED_ASC.name
                    )
                } catch(_: Exception) { PlaylistSortOption.DATE_CREATED_ASC }

                val widgetMode = preferences[SELECT_WIDGET_BACKGROUND_MODE]?.let {
                    try {
                        WidgetBackgroundMode.valueOf(it)
                    } catch (_: Exception) { WidgetBackgroundMode.STATIC }
                } ?: WidgetBackgroundMode.STATIC

                AppSettings(
                    selectedTheme = selectedTheme,
                    selectedPlayerLayout = selectedPlayerLayout,
                    playlistLayoutType = playlistLayoutType,
                    playlistSortOption = playlistSortOption,
                    repeatMode = repeatMode,
                    audioPreset = selectedAudioPreset,
                    widgetBackgroundMode = widgetMode
                )
            }
    }

    override fun getFilterOption(): Flow<FilterOption> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                FilterOption.valueOf(
                    preferences[SELECTED_FILTER_OPTION] ?: FilterOption.DATE_ADDED_DESC.name
                )
            }
    }
    override fun getAudioFileTypeFilter(): Flow<AudioFileTypeFilter> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                try {
                    AudioFileTypeFilter.valueOf(
                        preferences[AUDIO_FILE_TYPE_FILTER] ?: AudioFileTypeFilter.ALL.name
                    )
                } catch (_: Exception) {
                    AudioFileTypeFilter.ALL  // Default fallback
                }
            }
    }

    override fun getLastPlaybackState(): Flow<LastPlaybackState> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                val queueIds = preferences[LAST_QUEUE_IDS]?.takeIf { it.isNotBlank() }?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
                LastPlaybackState(
                    audioId = preferences[LAST_PLAYED_AUDIO_ID],
                    positionMs = preferences[LAST_POSITION_MS] ?: 0L,
                    queueIds = queueIds
                )
            }
    }

    override suspend fun saveLastPlaybackState(state: LastPlaybackState) {
        dataStore.edit { preferences ->
            if (state.audioId != null) {
                preferences[LAST_PLAYED_AUDIO_ID] = state.audioId!!
                preferences[LAST_POSITION_MS] = state.positionMs
            } else {
                preferences.remove(LAST_PLAYED_AUDIO_ID)
                preferences.remove(LAST_POSITION_MS)
            }
            val queueStr = state.queueIds?.joinToString(",")
            if (queueStr != null && queueStr.isNotEmpty()) {
                preferences[LAST_QUEUE_IDS] = queueStr
            } else {
                preferences.remove(LAST_QUEUE_IDS)
            }
        }
    }

    override suspend fun updateTheme(theme: AppThemeType) {
        dataStore.edit { preferences ->
            preferences[SELECTED_THEME] = theme.name
        }
    }

    override suspend fun updatePlayerLayout(layout: PlayerLayout) {
        dataStore.edit { preferences ->
            preferences[SELECTED_PLAYER_LAYOUT] = layout.name
        }
    }

    override suspend fun updatePlaylistLayout(layout: PlaylistLayoutType) {
        dataStore.edit { preferences ->
            preferences[PLAYLIST_LAYOUT_TYPE] = layout.name
        }
    }

    override suspend fun updatePlaylistSortOption(sortOption: PlaylistSortOption) {
        dataStore.edit { preferences ->
            preferences[PLAYLIST_SORT_OPTION] = sortOption.name
        }
    }

    override suspend fun updateFilterOption(filterOption: FilterOption) {
        dataStore.edit { preferences ->
            preferences[SELECTED_FILTER_OPTION] = filterOption.name
        }
    }

    override suspend fun updateRepeatMode(repeatMode: RepeatMode) {
        dataStore.edit { preferences ->
            preferences[REPEAT_MODE] = repeatMode.name
        }
    }

    override suspend fun updateAudioPreset(preset: AudioPreset) {
        dataStore.edit { preferences ->
            preferences[SELECTED_AUDIO_PRESET] = preset.name
        }
    }

    override suspend fun updateAudioFileTypeFilter(filter: AudioFileTypeFilter) {
        dataStore.edit { preferences ->
            preferences[AUDIO_FILE_TYPE_FILTER] = filter.name
        }
    }

    override suspend fun updateWidgetBackgroundMode(mode: WidgetBackgroundMode) {
        dataStore.edit { preferences ->
            preferences[SELECT_WIDGET_BACKGROUND_MODE] = mode.name
        }
    }

    override suspend fun getLastScanTimestamp(): Long {
        return dataStore.data.first()[LAST_SCAN_TIMESTAMP] ?: 0L
    }

    override suspend fun updateLastScanTimestamp(timestamp: Long) {
        dataStore.edit { it[LAST_SCAN_TIMESTAMP] = timestamp }
    }
}