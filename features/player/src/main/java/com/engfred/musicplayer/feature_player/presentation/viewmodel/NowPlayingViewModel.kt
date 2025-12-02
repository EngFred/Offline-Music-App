package com.engfred.musicplayer.feature_player.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the now playing screen, managing UI state related to audio playback.
 * It interacts with the PlaybackController, PlaylistRepository, and SettingsRepository.
 */
@UnstableApi
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // State flows to expose to the UI
    private val _uiState = MutableStateFlow(PlaybackState())
    val uiState: StateFlow<PlaybackState> = _uiState.asStateFlow()

    private val _playerLayoutState = MutableStateFlow<PlayerLayout?>(null)
    val playerLayoutState: StateFlow<PlayerLayout?> = _playerLayoutState.asStateFlow()

    private var favoritesId: Long = -1L

    init {
        viewModelScope.launch {
            // Ensure a "Favorites" playlist exists and retrieve its ID
            favoritesId = ensureFavoritesPlaylist()

            // Collect playback state from the controller and update UI state
            playbackController.getPlaybackState().collect { playbackState ->
                val isFavorite = if (playbackState.currentAudioFile != null) {
                    isSongInFavorites(playbackState.currentAudioFile!!.id)
                } else {
                    false
                }
                _uiState.update { currentState ->
                    playbackState.copy(
                        isLoading = if (playbackState.currentAudioFile != currentState.currentAudioFile) {
                            playbackState.isLoading
                        } else {
                            currentState.isLoading
                        },
                        isFavorite = isFavorite,
                        isSeeking = currentState.isSeeking
                    )
                }
            }
        }

        // Load saved player layout from settings
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val appSettings = settingsRepository.getAppSettings().first()
                _playerLayoutState.value = appSettings.selectedPlayerLayout
                Log.d("NowPlayingViewModel", "Player Layout initialized from settings: ${appSettings.selectedPlayerLayout}")
            } catch (e: Exception) {
                Log.e("NowPlayingViewModel", "Failed to load player settings from settings: ${e.message}", e)
                _playerLayoutState.value = PlayerLayout.MINIMALIST_GROOVE
            }
        }
    }

    /**
     * Ensures a "Favorites" playlist exists. If it doesn't, it creates one.
     * Returns the ID of the "Favorites" playlist.
     */
    private suspend fun ensureFavoritesPlaylist(): Long {
        val playlists = playlistRepository.getPlaylists().first()
        val fav = playlists.find { !it.isAutomatic && it.name.equals("Favorites", ignoreCase = true) }
        if (fav != null) return fav.id
        val newPlaylist = Playlist(name = "Favorites", isAutomatic = false, type = null)
        return playlistRepository.createPlaylist(newPlaylist)
    }

    /**
     * Checks if a song is currently in the "Favorites" playlist.
     * @param songId The ID of the audio file to check.
     * @return `true` if the song is in "Favorites", `false` otherwise.
     */
    private suspend fun isSongInFavorites(songId: Long): Boolean {
        if (favoritesId == -1L) {
            favoritesId = ensureFavoritesPlaylist()
        }
        val playlist = playlistRepository.getPlaylistById(favoritesId).first() ?: return false
        return playlist.songs.any { it.id == songId }
    }

    /**
     * Handles UI events triggered by the user on the now playing screen.
     * @param event The [PlayerEvent] to be processed.
     */
    fun onEvent(event: PlayerEvent) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                when (event) {
                    PlayerEvent.PlayPause -> playbackController.playPause()
                    PlayerEvent.SkipToNext -> playbackController.skipToNext()
                    PlayerEvent.SkipToPrevious -> playbackController.skipToPrevious()
                    is PlayerEvent.SeekTo -> playbackController.seekTo(event.positionMs)
                    is PlayerEvent.SetRepeatMode -> {
                        playbackController.setRepeatMode(event.mode)
                        settingsRepository.updateRepeatMode(event.mode)
                        Log.d("NowPlayingViewModel", "Repeat mode set to ${event.mode}")
                    }
                    is PlayerEvent.SetShuffleMode -> {
                        playbackController.setShuffleMode(event.mode)
                        Log.d("NowPlayingViewModel", "Shuffle mode set to ${event.mode}")
                    }
                    PlayerEvent.ReleasePlayer -> playbackController.releasePlayer()
                    is PlayerEvent.AddToFavorites -> {
                        if (favoritesId == -1L) {
                            favoritesId = ensureFavoritesPlaylist()
                        }
                        playlistRepository.addSongToPlaylist(favoritesId, event.audioFile)
                        _uiState.update { it.copy(isFavorite = true) }
                    }
                    is PlayerEvent.RemoveFromFavorites -> {
                        if (favoritesId == -1L) {
                            favoritesId = ensureFavoritesPlaylist()
                        }
                        playlistRepository.removeSongFromPlaylist(favoritesId, event.audioFileId)
                        _uiState.update { it.copy(isFavorite = false) }
                    }
                    is PlayerEvent.SetSeeking -> _uiState.update { it.copy(isSeeking = event.seeking) }
                    is PlayerEvent.PlayAudioFile -> playbackController.initiatePlayback(event.audioFile.uri)
                    is PlayerEvent.SelectPlayerLayout -> {
                        _playerLayoutState.value = event.layout
                        settingsRepository.updatePlayerLayout(event.layout)
                    }
                    is PlayerEvent.RemovedFromQueue -> playbackController.removeFromQueue(event.audioFile)
                    PlayerEvent.ToggleStopAfterCurrent -> playbackController.toggleStopAfterCurrent()
                }
            } catch (e: Exception) {
                Log.e("NowPlayingViewModel", "Event handling failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        error = "Event handling failed: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }
}