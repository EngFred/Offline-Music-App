package com.engfred.musicplayer.feature_library.presentation.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.activity.result.IntentSenderRequest
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.FilterOption
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.feature_library.domain.usecases.GetAllAudioFilesUseCase
import com.engfred.musicplayer.core.util.MediaUtils
import com.engfred.musicplayer.core.util.TextUtils.pluralize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val getAudioFilesUseCase: GetAllAudioFilesUseCase,
    private val permissionHandlerUseCase: PermissionHandlerUseCase,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackController: PlaybackController,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryScreenState())
    val uiState: StateFlow<LibraryScreenState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private val _deleteRequest = MutableSharedFlow<IntentSenderRequest>()
    val deleteRequest: SharedFlow<IntentSenderRequest> = _deleteRequest.asSharedFlow()

    init {
        observePermissionState()
        startObservingPlaybackState()
        observePlaylists()
        observeFilterOption()
    }

    // ... (Keep existing private observe methods) ...
    private fun observePermissionState() {
        val granted = permissionHandlerUseCase.hasAudioPermission() && permissionHandlerUseCase.hasWriteStoragePermission()
        _uiState.update { it.copy(hasStoragePermission = granted) }
    }

    private fun startObservingPlaybackState() {
        playbackController.getPlaybackState().onEach { state ->
            _uiState.update { currentState ->
                currentState.copy(
                    currentPlayingId = state.currentAudioFile?.id,
                    isPlaying = state.isPlaying,
                    currentPlaybackAudioFile = state.currentAudioFile
                )
            }
        }.launchIn(viewModelScope)
    }

    private fun observePlaylists() {
        playlistRepository.getPlaylists().onEach { playlists ->
            _uiState.update { it.copy(playlists = playlists.filterNot { playlist -> playlist.isAutomatic }) }
        }.launchIn(viewModelScope)
    }

    private fun observeFilterOption() {
        viewModelScope.launch {
            settingsRepository.getFilterOption().collectLatest { filterOption ->
                _uiState.update { it.copy(currentFilterOption = filterOption) }
                applySearchAndFilter()
            }
        }
    }

    fun onEvent(event: LibraryEvent) {
        viewModelScope.launch {
            when (event) {
                LibraryEvent.LoadAudioFiles -> {
                    if (_uiState.value.audioFiles.isEmpty() || _uiState.value.error != null) { loadAudioFiles() }
                }
                is LibraryEvent.PermissionGranted -> {
                    val granted = permissionHandlerUseCase.hasAudioPermission() && permissionHandlerUseCase.hasWriteStoragePermission()
                    _uiState.update { it.copy(hasStoragePermission = granted) }
                    if (granted && _uiState.value.audioFiles.isEmpty()) { loadAudioFiles() }
                }
                LibraryEvent.CheckPermission -> {
                    val granted = permissionHandlerUseCase.hasAudioPermission() && permissionHandlerUseCase.hasWriteStoragePermission()
                    _uiState.update { it.copy(hasStoragePermission = granted) }
                    if (!granted) { _uiEvent.emit("Storage permission denied. Cannot load music.") }
                }
                is LibraryEvent.PlayAudio -> startAudioPlayback(event.audioFile)
                is LibraryEvent.SearchQueryChanged -> {
                    _uiState.update { it.copy(searchQuery = event.query) }
                    applySearchAndFilter()
                }
                is LibraryEvent.FilterSelected -> {
                    _uiState.update { it.copy(currentFilterOption = event.filterOption) }
                    settingsRepository.updateFilterOption(event.filterOption)
                    applySearchAndFilter()
                }

                is LibraryEvent.AddedToPlaylist -> {
                    if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                        _uiState.update { it.copy(showAddToPlaylistDialog = true) }
                    } else {
                        _uiState.update { it.copy(showAddToPlaylistDialog = true, audioToAddToPlaylist = event.audioFile) }
                    }
                }

                is LibraryEvent.AddedSongToPlaylist -> {
                    val targetPlaylist = event.playlist
                    if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                        val selectedSongs = _uiState.value.selectedAudioFiles.toList()
                        try {
                            val addedCount = playlistRepository.addSongsToPlaylist(targetPlaylist.id, selectedSongs)
                            val skippedCount = selectedSongs.size - addedCount
                            _uiEvent.emit(if (skippedCount > 0) "$addedCount songs added, $skippedCount skipped" else "$addedCount songs added to ${targetPlaylist.name}")
                            onEvent(LibraryEvent.DeselectAll)
                        } catch (e: Exception) { _uiEvent.emit("Failed to add songs: ${e.message}") }
                    } else {
                        val audioFile = _uiState.value.audioToAddToPlaylist
                        if (audioFile != null) {
                            try {
                                playlistRepository.addSongToPlaylist(targetPlaylist.id, audioFile)
                                _uiEvent.emit("Added to ${targetPlaylist.name}")
                            } catch (e: Exception) { _uiEvent.emit("Failed to add song.") }
                            _uiState.update { it.copy(audioToAddToPlaylist = null) }
                        }
                    }
                    _uiState.update { it.copy(showAddToPlaylistDialog = false) }
                }

                LibraryEvent.DismissAddToPlaylistDialog -> {
                    _uiState.update { it.copy(showAddToPlaylistDialog = false, audioToAddToPlaylist = null) }
                }

                LibraryEvent.ShowCreatePlaylistDialog -> {
                    _uiState.update { it.copy(showCreatePlaylistDialog = true) }
                }

                LibraryEvent.DismissCreatePlaylistDialog -> {
                    _uiState.update { it.copy(showCreatePlaylistDialog = false) }
                }

                is LibraryEvent.CreatePlaylistAndAddSongs -> {
                    val name = event.playlistName
                    if (name.isBlank()) {
                        _uiEvent.emit("Playlist name cannot be empty")
                        return@launch
                    }

                    try {
                        // 1. Create the playlist
                        val newPlaylistId = playlistRepository.createPlaylist(
                            Playlist(
                                name = name,
                                isAutomatic = false,
                                type = null
                            )
                        )

                        // 2. Add songs to the NEW playlist ID
                        if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                            // Batch add
                            val selectedSongs = _uiState.value.selectedAudioFiles.toList()
                            playlistRepository.addSongsToPlaylist(newPlaylistId, selectedSongs)
                            _uiEvent.emit("Created '$name' and added ${selectedSongs.size} songs.")
                            onEvent(LibraryEvent.DeselectAll)
                        } else {
                            // Single add
                            val audioFile = _uiState.value.audioToAddToPlaylist
                            if (audioFile != null) {
                                playlistRepository.addSongToPlaylist(newPlaylistId, audioFile)
                                _uiEvent.emit("Created '$name' and added song.")
                                _uiState.update { it.copy(audioToAddToPlaylist = null) }
                            } else {
                                // Just created empty
                                _uiEvent.emit("Created playlist '$name'.")
                            }
                        }

                        _uiState.update { it.copy(showCreatePlaylistDialog = false) }

                    } catch (e: Exception) {
                        _uiEvent.emit("Failed to create playlist: ${e.message}")
                    }
                }

                is LibraryEvent.ShowDeleteConfirmation -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        _uiState.update { it.copy(audioFileToDelete = event.audioFile) }
                        onEvent(LibraryEvent.ConfirmDeleteAudioFile)
                    } else {
                        _uiState.update { it.copy(showDeleteConfirmationDialog = true, audioFileToDelete = event.audioFile) }
                    }
                }
                LibraryEvent.DismissDeleteConfirmationDialog -> {
                    _uiState.update { it.copy(showDeleteConfirmationDialog = false, audioFileToDelete = null, showBatchDeleteConfirmationDialog = false) }
                    if (_uiState.value.selectedAudioFiles.isNotEmpty()) { onEvent(LibraryEvent.DeselectAll) }
                }
                LibraryEvent.ConfirmDeleteAudioFile -> {
                    _uiState.value.audioFileToDelete?.let { audioFile ->
                        val intentSender = MediaUtils.deleteAudioFile(context, audioFile) { success, errorMessage ->
                            onEvent(LibraryEvent.DeletionResult(audioFile, success, errorMessage))
                        }
                        if (intentSender != null) { _deleteRequest.emit(IntentSenderRequest.Builder(intentSender).build()) }
                    }
                    _uiState.update { it.copy(showDeleteConfirmationDialog = false) }
                }
                is LibraryEvent.DeletionResult -> {
                    val audioFile = event.audioFile
                    if (event.success) {
                        _uiState.update { currentState ->
                            val updatedList = currentState.audioFiles.filter { it.id != audioFile.id }
                            val filteredList = updatedList.filter {
                                it.title.contains(currentState.searchQuery, ignoreCase = true) ||
                                        it.artist?.contains(currentState.searchQuery, ignoreCase = true) == true ||
                                        it.album?.contains(currentState.searchQuery, ignoreCase = true) == true
                            }
                            val sorted = sortAudioFiles(filteredList, currentState.currentFilterOption)
                            sharedAudioDataSource.setPlayingQueue(sorted)
                            currentState.copy(audioFiles = updatedList, filteredAudioFiles = sorted, showDeleteConfirmationDialog = false, audioFileToDelete = null)
                        }
                        playbackController.onAudioFileRemoved(audioFile)
                        sharedAudioDataSource.deleteAudioFile(audioFile)
                        playlistRepository.removeSongFromAllPlaylists(audioFile.id)
                        _uiEvent.emit("Successfully deleted '${audioFile.title}'.")
                    } else {
                        event.errorMessage?.let { _uiEvent.emit(it) }
                        _uiState.update { it.copy(showDeleteConfirmationDialog = false, audioFileToDelete = null) }
                    }
                }
                is LibraryEvent.PlayedNext -> {
                    playbackController.addAudioToQueueNext(event.audioFile)
                    _uiEvent.emit("'${event.audioFile.title}' will play next.")
                }
                LibraryEvent.Retry -> loadAudioFiles()
                is LibraryEvent.ToggleSelection -> {
                    _uiState.update { current ->
                        val newSelected = current.selectedAudioFiles.toMutableSet()
                        if (newSelected.contains(event.audioFile)) { newSelected.remove(event.audioFile) } else { newSelected.add(event.audioFile) }
                        current.copy(selectedAudioFiles = newSelected)
                    }
                }
                LibraryEvent.SelectAll -> {
                    _uiState.update { current -> current.copy(selectedAudioFiles = current.filteredAudioFiles.toSet()) }
                }
                LibraryEvent.DeselectAll -> {
                    _uiState.update { it.copy(selectedAudioFiles = emptySet()) }
                }
                LibraryEvent.ShowBatchDeleteConfirmation -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        onEvent(LibraryEvent.ConfirmBatchDelete)
                    } else {
                        if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                            _uiState.update { it.copy(showBatchDeleteConfirmationDialog = true) }
                        }
                    }
                }
                LibraryEvent.ConfirmBatchDelete -> {
                    val selected = _uiState.value.selectedAudioFiles.toList()
                    if (selected.isEmpty()) return@launch
                    val intentSender = MediaUtils.deleteAudioFiles(context, selected) { success, errorMessage ->
                        onEvent(LibraryEvent.BatchDeletionResult(success, errorMessage))
                    }
                    if (intentSender != null) { _deleteRequest.emit(IntentSenderRequest.Builder(intentSender).build()) }
                    _uiState.update { it.copy(showBatchDeleteConfirmationDialog = false) }
                }
                is LibraryEvent.BatchDeletionResult -> {
                    if (event.success) {
                        val selected = _uiState.value.selectedAudioFiles
                        _uiState.update { currentState ->
                            val updatedList = currentState.audioFiles.filterNot { selected.contains(it) }
                            val filteredList = updatedList.filter {
                                it.title.contains(currentState.searchQuery, ignoreCase = true) ||
                                        it.artist?.contains(currentState.searchQuery, ignoreCase = true) == true ||
                                        it.album?.contains(currentState.searchQuery, ignoreCase = true) == true
                            }
                            val sorted = sortAudioFiles(filteredList, currentState.currentFilterOption)
                            sharedAudioDataSource.setPlayingQueue(sorted)
                            currentState.copy(audioFiles = updatedList, filteredAudioFiles = sorted, selectedAudioFiles = emptySet(), showBatchDeleteConfirmationDialog = false)
                        }
                        selected.forEach { audioFile ->
                            playbackController.onAudioFileRemoved(audioFile)
                            sharedAudioDataSource.deleteAudioFile(audioFile)
                            playlistRepository.removeSongFromAllPlaylists(audioFile.id)
                        }
                        _uiEvent.emit("Successfully deleted ${pluralize(selected.size, "song", "songs")}")
                    } else {
                        event.errorMessage?.let { _uiEvent.emit(it) }
                        _uiState.update { it.copy(showBatchDeleteConfirmationDialog = false) }
                    }
                }
                else -> {}
            }
        }
    }

    private fun applySearchAndFilter() {
        _uiState.update { current ->
            val filtered = if (current.searchQuery.isBlank()) { current.audioFiles } else {
                current.audioFiles.filter { it.title.contains(current.searchQuery, ignoreCase = true) || it.artist?.contains(current.searchQuery, ignoreCase = true) == true || it.album?.contains(current.searchQuery, ignoreCase = true) == true }
            }
            val sortedFiltered = sortAudioFiles(filtered, current.currentFilterOption)
            current.copy(filteredAudioFiles = sortedFiltered)
        }
    }
    private fun sortAudioFiles(list: List<AudioFile>, filterOption: FilterOption): List<AudioFile> {
        return when (filterOption) {
            FilterOption.DATE_ADDED_ASC -> list.sortedBy { it.dateAdded }
            FilterOption.DATE_ADDED_DESC -> list.sortedByDescending { it.dateAdded }
            FilterOption.LENGTH_ASC -> list.sortedBy { it.duration }
            FilterOption.LENGTH_DESC -> list.sortedByDescending { it.duration }
            FilterOption.ALPHABETICAL_ASC -> list.sortedBy { it.title.lowercase() }
            FilterOption.ALPHABETICAL_DESC -> list.sortedByDescending { it.title.lowercase() }
        }
    }
    private suspend fun startAudioPlayback(audioFile: AudioFile) {
        val list = _uiState.value.filteredAudioFiles.ifEmpty { _uiState.value.audioFiles }
        sharedAudioDataSource.setPlayingQueue(list)
        playbackController.initiatePlayback(audioFile.uri)
    }
    fun getRequiredPermission(): String { return permissionHandlerUseCase.getRequiredReadPermission() }
    private fun loadAudioFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            getAudioFilesUseCase().collect { result ->
                _uiState.update { currentState ->
                    when (result) {
                        is Resource.Success -> {
                            val audioFiles = result.data ?: emptyList()
                            val sortedFiltered = sortAudioFiles(audioFiles, currentState.currentFilterOption)
                            sharedAudioDataSource.setDeviceAudioFiles(audioFiles)
                            currentState.copy(audioFiles = audioFiles, filteredAudioFiles = sortedFiltered, isLoading = false, error = null)
                        }
                        is Resource.Error -> {
                            sharedAudioDataSource.clearPlayingQueue()
                            _uiEvent.emit("Failed to load songs: ${result.message}")
                            currentState.copy(isLoading = false, error = result.message, filteredAudioFiles = emptyList())
                        }
                        is Resource.Loading -> currentState
                    }
                }
            }
        }
    }
}