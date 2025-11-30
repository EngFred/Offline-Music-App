package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.list

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the playlist screen, handling UI state and business logic.
 * It interacts with the Playlist, Playback, and Settings repositories.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    playbackController: PlaybackController,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val TAG = "PlaylistViewModel"

    // UI state for the screen
    private val _uiState = MutableStateFlow(PlaylistScreenState())
    val uiState: StateFlow<PlaylistScreenState> = _uiState.asStateFlow()

    // One-time UI events (e.g., Toast messages)
    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    init {
        // Ensure the "Favorites" playlist exists on app startup
        viewModelScope.launch {
            ensureFavoritesPlaylistExists()
        }

        // Initial loading of playlists
        loadPlaylists()

        // Observe changes to the current playback state
        playbackController.getPlaybackState().onEach { state ->
            _uiState.update { currentState ->
                currentState.copy(currentPlaybackAudioFile = state.currentAudioFile)
            }
        }.launchIn(viewModelScope)

        // Load the saved playlist layout from settings
        viewModelScope.launch(Dispatchers.IO) {
            try {
               settingsRepository.getAppSettings().collect {
                    _uiState.update { currentState ->
                        currentState.copy(currentLayout = it.playlistLayoutType)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load playlist layout from settings: ${e.message}", e)
                _uiState.update { currentState ->
                    currentState.copy(currentLayout = PlaylistLayoutType.LIST)
                }
            }
        }
    }

    /**
     * Ensures a "Favorites" playlist exists. If not, it creates one.
     */
    private suspend fun ensureFavoritesPlaylistExists() {
        val existing = playlistRepository.getPlaylists().first().any {
            !it.isAutomatic && it.name.equals("Favorites", ignoreCase = true)
        }
        if (!existing) {
            val favorites = Playlist(name = "Favorites", isAutomatic = false, type = null)
            playlistRepository.createPlaylist(favorites)
        }
    }

    /**
     * Handles all incoming UI events from the screen.
     */
    fun onEvent(event: PlaylistEvent) {
        viewModelScope.launch {
            when (event) {
                is PlaylistEvent.DeletePlaylist -> {
                    // Prevent deletion of automatic or 'Favorites' playlists
                    if (event.playlistId < 0) {
                        _uiEvent.emit("Automatic playlists cannot be deleted.")
                        return@launch
                    }
                    val playlist = playlistRepository.getPlaylistById(event.playlistId).first()
                    if (playlist?.name?.equals("Favorites", ignoreCase = true) == true) {
                        _uiEvent.emit("Cannot delete the Favorites playlist.")
                        return@launch
                    }

                    try {
                        playlistRepository.deletePlaylist(event.playlistId)
                        _uiEvent.emit("Playlist deleted.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting playlist: ${e.message}", e)
                        _uiEvent.emit("Error deleting playlist: ${e.message}")
                    }
                }

                is PlaylistEvent.AddSongToPlaylist -> {
                    // Prevent adding songs to automatic playlists
                    if (event.playlistId < 0) {
                        _uiEvent.emit("Cannot manually add songs to automatic playlists.")
                        return@launch
                    }

                    try {
                        playlistRepository.addSongToPlaylist(event.playlistId, event.audioFile)
                        _uiEvent.emit("Song added to playlist.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding song to playlist: ${e.message}", e)
                        _uiEvent.emit("Error adding song: ${e.message}")
                    }
                }

                is PlaylistEvent.RemoveSongFromPlaylist -> {
                    // Prevent removing songs from automatic playlists
                    if (event.playlistId < 0) {
                        _uiEvent.emit("Cannot manually remove songs from automatic playlists.")
                        return@launch
                    }

                    try {
                        playlistRepository.removeSongFromPlaylist(event.playlistId, event.audioFileId)
                        _uiEvent.emit("Song removed from playlist.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing song from playlist: ${e.message}", e)
                        _uiEvent.emit("Error removing song: ${e.message}")
                    }
                }

                PlaylistEvent.LoadPlaylists -> {
                    loadPlaylists()
                }

                PlaylistEvent.ToggleLayout -> {
                    // Toggle the layout and save the preference
                    _uiState.update {
                        it.copy(
                            currentLayout = if (it.currentLayout == PlaylistLayoutType.LIST) {
                                PlaylistLayoutType.GRID
                            } else {
                                PlaylistLayoutType.LIST
                            }
                        )
                    }
                    settingsRepository.updatePlaylistLayout(_uiState.value.currentLayout)
                }
            }
        }
    }

    /**
     * Fetches playlists from the repository and updates the UI state.
     */
    private fun loadPlaylists() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        playlistRepository.getPlaylists().onEach { allPlaylists ->
            // Separate and sort playlists for display
            val automatic = allPlaylists.filter { it.isAutomatic }
            val user = allPlaylists.filter { !it.isAutomatic }
            val favorites = user.find { it.name.equals("Favorites", ignoreCase = true) }
            val others = user.filter { !it.name.equals("Favorites", ignoreCase = true) }.sortedBy { it.name }
            val userSorted = listOfNotNull(favorites) + others

            _uiState.update {
                it.copy(
                    automaticPlaylists = automatic,
                    userPlaylists = userSorted,
                    isLoading = false,
                    error = null
                )
            }
        }.launchIn(viewModelScope)
    }
}