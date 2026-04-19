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

@UnstableApi
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaybackState())
    val uiState: StateFlow<PlaybackState> = _uiState.asStateFlow()

    private val _playerLayoutState = MutableStateFlow<PlayerLayout?>(null)
    val playerLayoutState: StateFlow<PlayerLayout?> = _playerLayoutState.asStateFlow()

    private val _customBackgroundUri = MutableStateFlow<String?>(null)
    val customBackgroundUri: StateFlow<String?> = _customBackgroundUri.asStateFlow()

    private var favoritesId: Long = -1L

    init {
        viewModelScope.launch {
            favoritesId = ensureFavoritesPlaylist()
            playbackController.getPlaybackState().collect { playbackState ->
                val isFavorite = if (playbackState.currentAudioFile != null) {
                    isSongInFavorites(playbackState.currentAudioFile!!.id)
                } else false
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val appSettings = settingsRepository.getAppSettings().first()
                _playerLayoutState.value = appSettings.selectedPlayerLayout
                Log.d("NowPlayingViewModel", "Layout initialized: ${appSettings.selectedPlayerLayout}")
            } catch (e: Exception) {
                Log.e("NowPlayingViewModel", "Failed to load layout: ${e.message}", e)
                _playerLayoutState.value = PlayerLayout.MINIMALIST_GROOVE
            }
        }

        // Observe custom background separately so it stays live across settings changes.
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.getAppSettings().collect { settings ->
                _customBackgroundUri.value = settings.customPlayerBackgroundUri
            }
        }
    }

    private suspend fun ensureFavoritesPlaylist(): Long {
        val playlists = playlistRepository.getPlaylists().first()
        val fav = playlists.find { !it.isAutomatic && it.name.equals("Favorites", ignoreCase = true) }
        if (fav != null) return fav.id
        return playlistRepository.createPlaylist(
            Playlist(name = "Favorites", isAutomatic = false, type = null)
        )
    }

    private suspend fun isSongInFavorites(songId: Long): Boolean {
        if (favoritesId == -1L) favoritesId = ensureFavoritesPlaylist()
        val playlist = playlistRepository.getPlaylistById(favoritesId).first() ?: return false
        return playlist.songs.any { it.id == songId }
    }

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
                    }
                    is PlayerEvent.SetShuffleMode -> playbackController.setShuffleMode(event.mode)
                    PlayerEvent.ReleasePlayer -> playbackController.releasePlayer()
                    is PlayerEvent.AddToFavorites -> {
                        if (favoritesId == -1L) favoritesId = ensureFavoritesPlaylist()
                        playlistRepository.addSongToPlaylist(favoritesId, event.audioFile)
                        _uiState.update { it.copy(isFavorite = true) }
                    }
                    is PlayerEvent.RemoveFromFavorites -> {
                        if (favoritesId == -1L) favoritesId = ensureFavoritesPlaylist()
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
                    is PlayerEvent.SetCustomBackground -> {
                        // Update in-memory state immediately for zero-latency UI response,
                        // then persist on IO dispatcher — fire-and-forget is safe here because
                        // DataStore writes are atomic and the StateFlow is the source of truth.
                        _customBackgroundUri.value = event.uri
                        viewModelScope.launch(Dispatchers.IO) {
                            settingsRepository.updateCustomPlayerBackground(event.uri)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NowPlayingViewModel", "Event handling failed: ${e.message}", e)
                _uiState.update { it.copy(error = "Event handling failed: ${e.message}", isLoading = false) }
            }
        }
    }
}