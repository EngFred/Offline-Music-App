package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.domain.model.DjMixPlaylistFilter
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MixStudioLauncherViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MixStudioLauncherUiState())
    val uiState: StateFlow<MixStudioLauncherUiState> = _uiState.asStateFlow()

    init {
        observeData()
    }

    private fun observeData() {
        // Combine playlists and settings so the UI reacts to filter changes immediately
        combine(
            playlistRepository.getPlaylists(),
            settingsRepository.getAppSettings()
        ) { allPlaylists, settings ->
            // Require strictly more than 2 tracks
            val mixable = allPlaylists.filter { it.songs.size > 2 }

            MixStudioLauncherUiState(
                isLoading = false,
                automaticPlaylists = mixable.filter { it.isAutomatic },
                userPlaylists = mixable.filter { !it.isAutomatic },
                currentFilter = settings.djMixPlaylistFilter,
                error = null
            )
        }
            .catch { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = throwable.localizedMessage ?: "Failed to load playlists."
                    )
                }
            }
            .onEach { state ->
                _uiState.value = state
            }
            .launchIn(viewModelScope)
    }

    // ── Function for the UI to call when a filter chip is tapped ──
    fun setFilter(filter: DjMixPlaylistFilter) {
        viewModelScope.launch {
            settingsRepository.updateDjMixPlaylistFilter(filter)
        }
    }
}