package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.util.TextUtils.pluralize
import com.engfred.musicplayer.feature_playlist.domain.model.PlaylistSortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object PlaylistDetailArgs {
    const val PLAYLIST_ID = "playlistId"
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackController: PlaybackController,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val TAG = "PlaylistDetailViewModel"

    private val _uiState = MutableStateFlow(PlaylistDetailScreenState())
    val uiState: StateFlow<PlaylistDetailScreenState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent = _uiEvent.asSharedFlow()

    private var currentPlaylistId: Long? = null

    // FIX: track last known file count so we only reconcile when the device
    // library actually changes in size, not on every StateFlow emission.
    // A file count change (add or delete) is the only condition that can
    // introduce orphaned playlist entries, so this is the correct trigger.
    private var lastKnownFileCount = -1

    init {
        sharedAudioDataSource.deviceAudioFiles.onEach { audioFiles ->
            _uiState.update { it.copy(allAudioFiles = audioFiles) }

            // FIX: was running a full DB SELECT on every emission regardless of
            // whether the library changed. Now only reconciles when the file count
            // actually changes — covers both additions and deletions.
            if (audioFiles.size != lastKnownFileCount && audioFiles.isNotEmpty()) {
                lastKnownFileCount = audioFiles.size
                reconcilePlaylistWithDeviceFiles(audioFiles)
            }
        }.launchIn(viewModelScope)

        loadPlaylistDetails(savedStateHandle)
        startObservingPlaybackState(playbackController)

        playlistRepository.getPlaylists().onEach { allPlaylists ->
            _uiState.update { currentState ->
                currentState.copy(
                    playlists = allPlaylists.filter { playlist ->
                        playlist.id != currentPlaylistId && !playlist.isAutomatic
                    }
                )
            }
        }.launchIn(viewModelScope)
    }

    private fun startObservingPlaybackState(playbackController: PlaybackController) {
        playbackController.getPlaybackState().onEach { state ->
            _uiState.update { currentState ->
                currentState.copy(
                    currentPlayingAudioFile = state.currentAudioFile,
                    isPlaying               = state.isPlaying,
                    isCastConnected         = state.castState == com.engfred.musicplayer.core.domain.model.CastState.CONNECTED
                )
            }
        }.launchIn(viewModelScope)
    }

    private fun reconcilePlaylistWithDeviceFiles(audioFiles: List<AudioFile>) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistRepository.reconcileWithDeviceFiles(
                existingAudioFileIds = audioFiles.map { it.id }.toSet()
            )
        }
    }

    fun onEvent(event: PlaylistDetailEvent) {
        viewModelScope.launch {
            val currentPlaylist = _uiState.value.playlist
            when (event) {
                is PlaylistDetailEvent.ShowRemoveSongConfirmation -> {
                    if (currentPlaylist?.isAutomatic == true && !currentPlaylist.name.equals("Favorites", true)) {
                        _uiEvent.emit("Cannot remove songs from automatic playlists.")
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            showRemoveSongConfirmationDialog = true,
                            audioFileToRemove               = event.audioFile
                        )
                    }
                }

                PlaylistDetailEvent.DismissRemoveSongConfirmation -> {
                    _uiState.update {
                        it.copy(
                            showRemoveSongConfirmationDialog = false,
                            audioFileToRemove               = null
                        )
                    }
                }

                PlaylistDetailEvent.ConfirmRemoveSong -> {
                    val audioFileToRemove = _uiState.value.audioFileToRemove
                    val playlistId        = currentPlaylistId
                    if (playlistId != null && audioFileToRemove != null) {
                        if (currentPlaylist?.isAutomatic == true && !currentPlaylist.name.equals("Favorites", true)) {
                            _uiEvent.emit("Cannot remove songs from automatic playlists.")
                            return@launch
                        }
                        try {
                            playlistRepository.removeSongFromPlaylist(playlistId, audioFileToRemove.id)
                            _uiEvent.emit("Removed '${audioFileToRemove.title}' from playlist.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to remove song from playlist: ${e.message}", e)
                            _uiEvent.emit("Failed to remove song!")
                        } finally {
                            _uiState.update {
                                it.copy(
                                    showRemoveSongConfirmationDialog = false,
                                    audioFileToRemove               = null
                                )
                            }
                        }
                    } else {
                        _uiEvent.emit("No song or playlist selected for removal.")
                        _uiState.update {
                            it.copy(showRemoveSongConfirmationDialog = false, audioFileToRemove = null)
                        }
                    }
                }

                is PlaylistDetailEvent.RenamePlaylist -> {
                    if (currentPlaylist?.isAutomatic == true) {
                        _uiEvent.emit("Cannot rename automatic playlists.")
                        _uiState.update { it.copy(showRenameDialog = false) }
                        return@launch
                    }
                    if (event.newName.equals("Favorites", ignoreCase = true) ||
                        event.newName.equals("Favorite",  ignoreCase = true)) {
                        _uiEvent.emit("Cannot use this playlist name! Please choose another.")
                        return@launch
                    }
                    val existing = playlistRepository.getPlaylists().first().filter { !it.isAutomatic }
                    if (existing.any { it.name.equals(event.newName, ignoreCase = true) }) {
                        _uiEvent.emit("Playlist with this name already exists.")
                        return@launch
                    }
                    currentPlaylistId?.let {
                        if (currentPlaylist != null && event.newName.isNotBlank()) {
                            try {
                                playlistRepository.updatePlaylist(currentPlaylist.copy(name = event.newName))
                                _uiState.update { it.copy(showRenameDialog = false) }
                                _uiEvent.emit("Playlist renamed to '${event.newName}'.")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to rename playlist: ${e.message}", e)
                                _uiEvent.emit("Failed to rename playlist!")
                            }
                        } else {
                            _uiEvent.emit("Invalid playlist name!")
                        }
                    }
                }

                PlaylistDetailEvent.ShowRenameDialog -> {
                    if (currentPlaylist?.isAutomatic == true) {
                        _uiEvent.emit("Cannot rename automatic playlists.")
                        return@launch
                    }
                    _uiState.update { it.copy(showRenameDialog = true, error = null) }
                }

                PlaylistDetailEvent.HideRenameDialog ->
                    _uiState.update { it.copy(showRenameDialog = false, error = null) }

                is PlaylistDetailEvent.AddSong -> {
                    if (currentPlaylist?.isAutomatic == true && !currentPlaylist.name.equals("Favorites", true)) {
                        _uiEvent.emit("Cannot manually add songs to automatic playlists.")
                        return@launch
                    }
                    currentPlaylistId?.let { playlistId ->
                        val alreadyIn = uiState.value.playlist?.songs?.any { it.id == event.audioFile.id } == true
                        if (!alreadyIn) {
                            try {
                                playlistRepository.addSongToPlaylist(playlistId, event.audioFile)
                                _uiEvent.emit("Song added to playlist.")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to add song to playlist: ${e.message}", e)
                                _uiEvent.emit("Failed to add song to playlist!")
                            }
                        } else {
                            _uiEvent.emit("Song already in this playlist.")
                        }
                    }
                }

                PlaylistDetailEvent.PlayAll -> {
                    uiState.value.playlist?.songs?.let { songs ->
                        if (songs.isNotEmpty()) {
                            playbackController.setShuffleMode(ShuffleMode.OFF)
                            startAudioPlayback()
                        } else {
                            _uiEvent.emit("Playlist is empty, cannot play.")
                        }
                    }
                }

                PlaylistDetailEvent.ShuffleAll -> {
                    uiState.value.playlist?.songs?.let { songs ->
                        if (songs.isNotEmpty()) playbackController.initiateShufflePlayback(songs)
                        else _uiEvent.emit("Playlist is empty, cannot shuffle play.")
                    }
                }

                is PlaylistDetailEvent.LoadPlaylist -> loadPlaylistDetails(savedStateHandle)

                PlaylistDetailEvent.PlayNext  -> playbackController.skipToNext()
                PlaylistDetailEvent.PlayPause -> playbackController.playPause()
                PlaylistDetailEvent.PlayPrev  -> playbackController.skipToPrevious()

                is PlaylistDetailEvent.SetPlayNext ->
                    playbackController.addAudioToQueueNext(event.audioFile)

                is PlaylistDetailEvent.SetSortOrder -> {
                    _uiState.update { currentState ->
                        currentState.copy(
                            currentSortOrder = event.sortOrder,
                            sortedSongs      = applySorting(event.sortOrder, currentState.playlist)
                        )
                    }
                }

                is PlaylistDetailEvent.PlayAudio -> {
                    if (uiState.value.playlist?.songs?.isNotEmpty() == true) {
                        sharedAudioDataSource.setPlayingQueue(uiState.value.sortedSongs)
                        playbackController.initiatePlayback(event.audioFile.uri)
                    } else {
                        _uiEvent.emit("Playlist is empty, cannot play.")
                    }
                }

                is PlaylistDetailEvent.ToggleSelection -> {
                    if (_uiState.value.playlist?.isAutomatic == true &&
                        !_uiState.value.playlist!!.name.equals("Favorites", true)) return@launch
                    _uiState.update { current ->
                        val newSelected = current.selectedSongs.toMutableSet()
                        if (!newSelected.add(event.audioFile)) newSelected.remove(event.audioFile)
                        current.copy(selectedSongs = newSelected)
                    }
                }

                PlaylistDetailEvent.SelectAll ->
                    _uiState.update { it.copy(selectedSongs = it.sortedSongs.toSet()) }

                PlaylistDetailEvent.DeselectAll ->
                    _uiState.update { it.copy(selectedSongs = emptySet()) }

                PlaylistDetailEvent.ShowBatchRemoveConfirmation -> {
                    if (_uiState.value.selectedSongs.isNotEmpty())
                        _uiState.update { it.copy(showBatchRemoveConfirmationDialog = true) }
                }

                PlaylistDetailEvent.DismissBatchRemoveConfirmation ->
                    _uiState.update { it.copy(showBatchRemoveConfirmationDialog = false, selectedSongs = emptySet()) }

                PlaylistDetailEvent.ConfirmBatchRemove -> {
                    val selected = _uiState.value.selectedSongs.toList()
                    if (selected.isEmpty()) return@launch
                    try {
                        selected.forEach { audioFile ->
                            currentPlaylistId?.let { playlistRepository.removeSongFromPlaylist(it, audioFile.id) }
                        }
                        onEvent(PlaylistDetailEvent.BatchRemoveResult(true, null))
                    } catch (e: Exception) {
                        onEvent(PlaylistDetailEvent.BatchRemoveResult(false, "Failed to remove songs: ${e.message}"))
                    }
                }

                is PlaylistDetailEvent.BatchRemoveResult -> {
                    if (event.success) {
                        val selected = _uiState.value.selectedSongs
                        _uiState.update { currentState ->
                            val updatedSongs   = currentState.sortedSongs.filterNot { selected.contains(it) }
                            val updatedPlaylist = currentState.playlist?.copy(songs = updatedSongs)
                            currentState.copy(
                                playlist                        = updatedPlaylist,
                                sortedSongs                     = updatedSongs,
                                selectedSongs                   = emptySet(),
                                showBatchRemoveConfirmationDialog = false
                            )
                        }
                        _uiEvent.emit("Successfully removed ${pluralize(selected.size, "song", "songs")} from playlist.")
                    } else {
                        _uiEvent.emit(event.errorMessage ?: "Failed to remove selected songs.")
                        _uiState.update { it.copy(showBatchRemoveConfirmationDialog = false) }
                    }
                }

                is PlaylistDetailEvent.SetPlaylistCover ->
                    _uiState.update { it.copy(showSetCoverConfirmationDialog = true, potentialCoverAudioFile = event.audioFile) }

                PlaylistDetailEvent.DismissSetCoverConfirmation ->
                    _uiState.update { it.copy(showSetCoverConfirmationDialog = false, potentialCoverAudioFile = null) }

                PlaylistDetailEvent.ConfirmSetCover -> {
                    val audioFile = _uiState.value.potentialCoverAudioFile
                    val playlist  = _uiState.value.playlist
                    if (audioFile != null && playlist != null) {
                        val artUri = audioFile.albumArtUri
                        if (artUri != null) {
                            try {
                                playlistRepository.updatePlaylist(playlist.copy(customArtUri = artUri))
                                _uiEvent.emit("Playlist cover updated!")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to set playlist cover", e)
                                _uiEvent.emit("Failed to update playlist cover.")
                            }
                        } else {
                            _uiEvent.emit("This song has no album art.")
                        }
                    }
                    _uiState.update { it.copy(showSetCoverConfirmationDialog = false, potentialCoverAudioFile = null) }
                }

                is PlaylistDetailEvent.ShowPlaylistsDialog ->
                    _uiState.update { it.copy(audioToAddToPlaylist = event.audioFile, showAddToPlaylistDialog = true) }

                is PlaylistDetailEvent.AddedSongToPlaylist -> {
                    val audioFile = _uiState.value.audioToAddToPlaylist
                    if (audioFile != null) {
                        if (event.playlist.songs.any { it.id == audioFile.id }) {
                            _uiEvent.emit("Song already in playlist")
                        } else {
                            try {
                                playlistRepository.addSongToPlaylist(event.playlist.id, audioFile)
                                _uiEvent.emit("Added successfully!")
                            } catch (ex: Exception) {
                                _uiEvent.emit("Failed to add song to playlist!")
                            }
                        }
                    }
                    _uiState.update { it.copy(audioToAddToPlaylist = null, showAddToPlaylistDialog = false) }
                }

                PlaylistDetailEvent.DismissAddToPlaylistDialog ->
                    _uiState.update { it.copy(audioToAddToPlaylist = null, showAddToPlaylistDialog = false) }

                PlaylistDetailEvent.ShowCreatePlaylistDialog ->
                    _uiState.update { it.copy(showAddToPlaylistDialog = false, showCreatePlaylistDialog = true) }

                PlaylistDetailEvent.DismissCreatePlaylistDialog ->
                    _uiState.update { it.copy(showCreatePlaylistDialog = false, audioToAddToPlaylist = null) }

                is PlaylistDetailEvent.CreatePlaylistAndAddSongs -> {
                    val name = event.playlistName
                    if (name.isBlank()) { _uiEvent.emit("Playlist name cannot be empty."); return@launch }
                    if (name.equals("Favorites", ignoreCase = true) || name.equals("Favorite", ignoreCase = true)) {
                        _uiEvent.emit("Cannot use this playlist name! Please choose another."); return@launch
                    }
                    val existing = playlistRepository.getPlaylists().first().filter { !it.isAutomatic }
                    if (existing.any { it.name.equals(name, ignoreCase = true) }) {
                        _uiEvent.emit("Playlist with this name already exists."); return@launch
                    }
                    try {
                        val newId     = playlistRepository.createPlaylist(Playlist(name = name, isAutomatic = false, type = null))
                        val audioFile = _uiState.value.audioToAddToPlaylist
                        if (audioFile != null) {
                            playlistRepository.addSongToPlaylist(newId, audioFile)
                            _uiEvent.emit("Created '$name' playlist and added the selected song.")
                            _uiState.update { it.copy(audioToAddToPlaylist = null) }
                        } else {
                            _uiEvent.emit("Created playlist '$name'.")
                        }
                        _uiState.update { it.copy(showCreatePlaylistDialog = false) }
                    } catch (e: Exception) {
                        _uiEvent.emit("Failed to create playlist: ${e.message}")
                    }
                }
            }
        }
    }

    private fun loadPlaylistDetails(savedStateHandle: SavedStateHandle) {
        savedStateHandle.get<Long>(PlaylistDetailArgs.PLAYLIST_ID)?.let { playlistId ->
            currentPlaylistId = playlistId
            _uiState.update { it.copy(isLoading = true, error = null) }

            playlistRepository.getPlaylistById(playlistId)
                .onEach { playlist ->
                    if (playlist != null) {
                        _uiState.update { currentState ->
                            currentState.copy(
                                playlist    = playlist,
                                sortedSongs = applySorting(currentState.currentSortOrder, playlist),
                                isLoading   = false
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "Playlist not found.", playlist = null) }
                    }
                }
                .launchIn(viewModelScope)
        } ?: run {
            _uiState.update { it.copy(error = "Playlist ID not provided.") }
        }
    }

    private suspend fun startAudioPlayback() {
        uiState.value.playlist?.songs?.let {
            sharedAudioDataSource.setPlayingQueue(_uiState.value.sortedSongs)
            playbackController.initiatePlayback(_uiState.value.sortedSongs.first().uri)
        }
    }

    private fun applySorting(order: PlaylistSortOrder, playlist: Playlist?): List<AudioFile> =
        when (order) {
            PlaylistSortOrder.DATE_ADDED   -> if (playlist?.isAutomatic?.not() == true)
                playlist.songs.reversed()
            else
                playlist?.songs?.sortedByDescending { it.dateAdded }
            PlaylistSortOrder.ALPHABETICAL -> playlist?.songs?.sortedBy { it.title.lowercase() }
            PlaylistSortOrder.PLAY_COUNT   -> playlist?.songs?.sortedByDescending {
                playlist.playCounts?.get(it.id) ?: 0
            }
        } ?: emptyList()
}