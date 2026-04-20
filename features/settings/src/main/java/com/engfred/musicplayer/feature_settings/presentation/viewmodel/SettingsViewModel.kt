package com.engfred.musicplayer.feature_settings.presentation.viewmodel

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.domain.usecases.CheckForUpdateUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.GetAppSettingsUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.GetAudioFileTypeFilterUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdateAudioFileTypeFilterUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdateAudioPresetUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdateMixOfTheDayFilterByDurationUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdatePlayerLayoutUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdatePlaylistLayoutUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdateThemeUseCase
import com.engfred.musicplayer.feature_settings.domain.usecases.UpdateWidgetBackgroundModeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class SettingsViewModel @Inject constructor(
    getAppSettingsUseCase: GetAppSettingsUseCase,
    private val getAudioFileTypeFilterUseCase: GetAudioFileTypeFilterUseCase,
    private val updateThemeUseCase: UpdateThemeUseCase,
    private val updatePlayerLayoutUseCase: UpdatePlayerLayoutUseCase,
    private val updatePlaylistLayoutUseCase: UpdatePlaylistLayoutUseCase,
    private val updateAudioPresetUseCase: UpdateAudioPresetUseCase,
    private val updateAudioFileTypeFilterUseCase: UpdateAudioFileTypeFilterUseCase,
    private val updateWidgetBackgroundModeUseCase: UpdateWidgetBackgroundModeUseCase,
    private val updateMixOfTheDayFilterByDurationUseCase: UpdateMixOfTheDayFilterByDurationUseCase,
    private val checkForUpdateUseCase: CheckForUpdateUseCase,
    @Named("versionName") private val versionName: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsScreenState())
    val uiState: StateFlow<SettingsScreenState> = _uiState.asStateFlow()

    init {
        getAppSettingsUseCase().onEach { appSettings ->
            _uiState.update {
                it.copy(
                    selectedTheme               = appSettings.selectedTheme,
                    selectedPlayerLayout        = appSettings.selectedPlayerLayout,
                    playlistLayoutType          = appSettings.playlistLayoutType,
                    audioPreset                 = appSettings.audioPreset,
                    widgetBackgroundMode        = appSettings.widgetBackgroundMode,
                    mixOfTheDayFilterByDuration = appSettings.mixOfTheDayFilterByDuration,
                )
            }
        }.launchIn(viewModelScope)

        getAudioFileTypeFilterUseCase().onEach { filter ->
            _uiState.update { it.copy(audioFileTypeFilter = filter) }
        }.launchIn(viewModelScope)

        // Check for updates every time Settings is opened.
        // Now that CheckForUpdateUseCase throws on network/API errors (instead of
        // returning null), we can reliably distinguish failure from "up-to-date",
        // and surface errors in uiState rather than silently dropping them.
        checkForUpdate()
    }

    /**
     * Runs the update check and updates [uiState] accordingly.
     * Called automatically on init; can also be triggered manually (e.g. a
     * "retry" button in the error banner) without recreating the ViewModel.
     */
    fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val info = checkForUpdateUseCase(versionName)
                // null  → API call succeeded, app is genuinely up-to-date
                // info  → API call succeeded, an update is available → show banner
                _uiState.update { it.copy(updateInfo = info) }
                Log.w("SettingsViewModel", "Update available}")
            } catch (e: Exception) {
                // The check itself failed (network error, GitHub API error, etc.).
                // Log it so it shows up in Logcat for debugging, but don't surface
                // it in the error banner — it's not a user-actionable settings error.
                Log.w("SettingsViewModel", "Update check failed: ${e.message}")
                // Leave updateInfo as whatever it was — don't reset it to null,
                // so a previously discovered update banner stays visible.
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is SettingsEvent.UpdateTheme -> {
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updateThemeUseCase(event.theme)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update theme: ${e.localizedMessage}") }
                    }
                }
                is SettingsEvent.UpdatePlayerLayout -> {
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updatePlayerLayoutUseCase(event.layout)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update player layout: ${e.localizedMessage}") }
                    }
                }
                is SettingsEvent.UpdatePlaylistLayout -> {
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updatePlaylistLayoutUseCase(event.layout)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update playlist layout: ${e.localizedMessage}") }
                    }
                }
                is SettingsEvent.UpdateAudioPreset -> {
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updateAudioPresetUseCase(event.preset)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update audio preset: ${e.localizedMessage}") }
                    }
                }
                is SettingsEvent.UpdateAudioFileTypeFilter -> {
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updateAudioFileTypeFilterUseCase(event.filter)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update audio filter: ${e.localizedMessage}") }
                    }
                }
                is SettingsEvent.UpdateWidgetBackgroundMode -> {
                    val appContext = event.context
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updateWidgetBackgroundModeUseCase(event.mode)
                        try {
                            val intent = Intent().apply {
                                component = ComponentName(
                                    appContext.packageName,
                                    "com.engfred.musicplayer.widget.PlayerWidgetProvider"
                                )
                                action    = "com.engfred.musicplayer.ACTION_UPDATE_WIDGET"
                                `package` = appContext.packageName
                            }
                            appContext.sendBroadcast(intent)
                        } catch (bex: Exception) {
                            Log.w("SettingsViewModel", "Failed to notify widget provider: ${bex.message}")
                        }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update widget mode: ${e.localizedMessage}") }
                    }
                }
                is SettingsEvent.UpdateMixOfTheDayFilterByDuration -> {
                    _uiState.update { it.copy(isLoading = true) }
                    try {
                        updateMixOfTheDayFilterByDurationUseCase(event.enabled)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to update Mix of the Day setting: ${e.localizedMessage}") }
                    }
                }
            }
        }
    }
}