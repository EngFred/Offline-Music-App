package com.engfred.musicplayer.feature_settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
        private val SELECTED_THEME                = stringPreferencesKey("selected_theme")
        private val SELECTED_PLAYER_LAYOUT        = stringPreferencesKey("selected_player_layout")
        private val PLAYLIST_LAYOUT_TYPE          = stringPreferencesKey("playlist_layout_type")
        private val PLAYLIST_SORT_OPTION          = stringPreferencesKey("playlist_sort_option")
        private val SELECTED_FILTER_OPTION        = stringPreferencesKey("selected_filter_option")
        private val REPEAT_MODE                   = stringPreferencesKey("repeat_mode")
        private val SELECTED_AUDIO_PRESET         = stringPreferencesKey("selected_audio_preset")
        private val SELECT_WIDGET_BACKGROUND_MODE = stringPreferencesKey("widget_background_mode")
        private val AUDIO_FILE_TYPE_FILTER        = stringPreferencesKey("audio_file_type_filter")

        private val LAST_PLAYED_AUDIO_ID          = longPreferencesKey("last_played_audio_id")
        private val LAST_POSITION_MS              = longPreferencesKey("last_position_ms")
        private val LAST_QUEUE_IDS                = stringPreferencesKey("last_queue_ids")

        private val LAST_SCAN_TIMESTAMP           = longPreferencesKey("last_scan_timestamp")

        // ── DJ Mix ────────────────────────────────────────────────────────────
        // Write paths and keys for the DJ Mix settings module
        private val CROSSFADE_DURATION_SEC = intPreferencesKey("dj_crossfade_duration_sec")
        private val BPM_TOLERANCE          = floatPreferencesKey("dj_bpm_tolerance")
        private val DJ_REAL_MIX_MODE       = booleanPreferencesKey("dj_real_mix_mode")
        private val DJ_MAX_TRACK_DUR_SEC   = intPreferencesKey("dj_max_track_dur_sec")
        private val DJ_LOOP_QUEUE          = booleanPreferencesKey("dj_loop_queue")
        private val DJ_MANUAL_MAX_DURATION = booleanPreferencesKey("dj_manual_max_duration")
        private val DJ_AUTO_SAMPLER        = booleanPreferencesKey("dj_auto_sampler")
        private val DJ_SAMPLE_VOLUME       = floatPreferencesKey("dj_sample_volume")

        private val LAST_MIX_OF_THE_DAY_TIMESTAMP = longPreferencesKey("last_mix_of_the_day_timestamp")

        private val DJ_MIX_PLAYLIST_FILTER = stringPreferencesKey("dj_mix_playlist_filter")
    }

    override fun getAppSettings(): Flow<AppSettings> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .map { preferences ->
                val selectedTheme = AppThemeType.valueOf(
                    preferences[SELECTED_THEME] ?: AppThemeType.NEON_DARK.name
                )
                val selectedPlayerLayout = PlayerLayout.valueOf(
                    preferences[SELECTED_PLAYER_LAYOUT] ?: PlayerLayout.ETHEREAL_FLOW.name
                )
                val playlistLayoutType = PlaylistLayoutType.valueOf(
                    preferences[PLAYLIST_LAYOUT_TYPE] ?: PlaylistLayoutType.LIST.name
                )
                val djMixFilter = try {
                    DjMixPlaylistFilter.valueOf(
                        preferences[DJ_MIX_PLAYLIST_FILTER] ?: DjMixPlaylistFilter.ALL.name
                    )
                } catch (_: Exception) { DjMixPlaylistFilter.ALL }
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
                } catch (_: Exception) { PlaylistSortOption.DATE_CREATED_ASC }

                val widgetMode = preferences[SELECT_WIDGET_BACKGROUND_MODE]?.let {
                    try { WidgetBackgroundMode.valueOf(it) } catch (_: Exception) { WidgetBackgroundMode.STATIC }
                } ?: WidgetBackgroundMode.STATIC

                AppSettings(
                    selectedTheme        = selectedTheme,
                    selectedPlayerLayout = selectedPlayerLayout,
                    playlistLayoutType   = playlistLayoutType,
                    playlistSortOption   = playlistSortOption,
                    repeatMode           = repeatMode,
                    audioPreset          = selectedAudioPreset,
                    widgetBackgroundMode = widgetMode,
                    djMixPlaylistFilter  = djMixFilter,
                    crossfadeDurationSec = preferences[CROSSFADE_DURATION_SEC] ?: 5,
                    bpmTolerance         = preferences[BPM_TOLERANCE] ?: 10f,
                    isRealMixMode        = preferences[DJ_REAL_MIX_MODE] ?: true,
                    maxTrackDurationSec  = preferences[DJ_MAX_TRACK_DUR_SEC] ?: 146,
                    loopQueue            = preferences[DJ_LOOP_QUEUE] ?: true,
                    useManualMaxDuration = preferences[DJ_MANUAL_MAX_DURATION] ?: false,
                    autoSamplerEnabled   = preferences[DJ_AUTO_SAMPLER] ?: true,
                    sampleVolume         = preferences[DJ_SAMPLE_VOLUME] ?: 1f
                )
            }
    }

    override fun getFilterOption(): Flow<FilterOption> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
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
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .map { preferences ->
                try {
                    AudioFileTypeFilter.valueOf(
                        preferences[AUDIO_FILE_TYPE_FILTER] ?: AudioFileTypeFilter.ALL.name
                    )
                } catch (_: Exception) { AudioFileTypeFilter.ALL }
            }
    }

    override fun getLastPlaybackState(): Flow<LastPlaybackState> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .map { preferences ->
                val queueIds = preferences[LAST_QUEUE_IDS]
                    ?.takeIf { it.isNotBlank() }
                    ?.split(",")
                    ?.mapNotNull { it.trim().toLongOrNull() }
                LastPlaybackState(
                    audioId    = preferences[LAST_PLAYED_AUDIO_ID],
                    positionMs = preferences[LAST_POSITION_MS] ?: 0L,
                    queueIds   = queueIds
                )
            }
    }

    override suspend fun saveLastPlaybackState(state: LastPlaybackState) {
        dataStore.edit { preferences ->
            if (state.audioId != null) {
                preferences[LAST_PLAYED_AUDIO_ID] = state.audioId!!
                preferences[LAST_POSITION_MS]     = state.positionMs
            } else {
                preferences.remove(LAST_PLAYED_AUDIO_ID)
                preferences.remove(LAST_POSITION_MS)
            }
            val queueStr = state.queueIds?.joinToString(",")
            if (!queueStr.isNullOrEmpty()) preferences[LAST_QUEUE_IDS] = queueStr
            else preferences.remove(LAST_QUEUE_IDS)
        }
    }

    override suspend fun updateTheme(theme: AppThemeType) {
        dataStore.edit { it[SELECTED_THEME] = theme.name }
    }

    override suspend fun updatePlayerLayout(layout: PlayerLayout) {
        dataStore.edit { it[SELECTED_PLAYER_LAYOUT] = layout.name }
    }

    override suspend fun updatePlaylistLayout(layout: PlaylistLayoutType) {
        dataStore.edit { it[PLAYLIST_LAYOUT_TYPE] = layout.name }
    }

    override suspend fun updatePlaylistSortOption(sortOption: PlaylistSortOption) {
        dataStore.edit { it[PLAYLIST_SORT_OPTION] = sortOption.name }
    }

    override suspend fun updateFilterOption(filterOption: FilterOption) {
        dataStore.edit { it[SELECTED_FILTER_OPTION] = filterOption.name }
    }

    override suspend fun updateRepeatMode(repeatMode: RepeatMode) {
        dataStore.edit { it[REPEAT_MODE] = repeatMode.name }
    }

    override suspend fun updateAudioPreset(preset: AudioPreset) {
        dataStore.edit { it[SELECTED_AUDIO_PRESET] = preset.name }
    }

    override suspend fun updateAudioFileTypeFilter(filter: AudioFileTypeFilter) {
        dataStore.edit { it[AUDIO_FILE_TYPE_FILTER] = filter.name }
    }

    override suspend fun updateWidgetBackgroundMode(mode: WidgetBackgroundMode) {
        dataStore.edit { it[SELECT_WIDGET_BACKGROUND_MODE] = mode.name }
    }

    override suspend fun getLastScanTimestamp(): Long {
        return dataStore.data.first()[LAST_SCAN_TIMESTAMP] ?: 0L
    }

    override suspend fun updateLastScanTimestamp(timestamp: Long) {
        dataStore.edit { it[LAST_SCAN_TIMESTAMP] = timestamp }
    }

    // ── DJ Mix Settings Updates ───────────────────────────────────────────────

    override suspend fun updateDjCrossfadeDuration(seconds: Int) {
        dataStore.edit { it[CROSSFADE_DURATION_SEC] = seconds }
    }

    override suspend fun updateDjBpmTolerance(tolerance: Float) {
        dataStore.edit { it[BPM_TOLERANCE] = tolerance }
    }

    override suspend fun updateDjRealMixMode(enabled: Boolean) {
        dataStore.edit { it[DJ_REAL_MIX_MODE] = enabled }
    }

    override suspend fun updateDjMaxTrackDuration(seconds: Int) {
        dataStore.edit { it[DJ_MAX_TRACK_DUR_SEC] = seconds }
    }

    override suspend fun updateDjLoopQueue(enabled: Boolean) {
        dataStore.edit { it[DJ_LOOP_QUEUE] = enabled }
    }

    /**
     * Persists the toggle between "mix at halfway" (default) and
     * "mix after manual max duration".
     */
    override suspend fun updateDjManualMaxDuration(enabled: Boolean) {
        dataStore.edit { it[DJ_MANUAL_MAX_DURATION] = enabled }
    }

    override suspend fun updateDjAutoSampler(enabled: Boolean) {
        dataStore.edit { it[DJ_AUTO_SAMPLER] = enabled }
    }

    override suspend fun updateDjSampleVolume(volume: Float) {
        dataStore.edit { it[DJ_SAMPLE_VOLUME] = volume }
    }

    override suspend fun getLastMixOfTheDayTimestamp(): Long {
        return dataStore.data.first()[LAST_MIX_OF_THE_DAY_TIMESTAMP] ?: 0L
    }

    override suspend fun updateLastMixOfTheDayTimestamp(timestamp: Long) {
        dataStore.edit { it[LAST_MIX_OF_THE_DAY_TIMESTAMP] = timestamp }
    }

    override suspend fun updateDjMixPlaylistFilter(filter: DjMixPlaylistFilter) {
        dataStore.edit { it[DJ_MIX_PLAYLIST_FILTER] = filter.name }
    }
}