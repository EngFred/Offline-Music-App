package com.engfred.musicplayer.feature_library.presentation.viewmodel

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.activity.result.IntentSenderRequest
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "LibraryViewModel"

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val getAudioFilesUseCase: GetAllAudioFilesUseCase,
    private val permissionHandlerUseCase: PermissionHandlerUseCase,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackController: PlaybackController,
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    // ── capture selected files BEFORE the async system dialog ──────
    // selectedAudioFiles in uiState can be cleared by any state update between
    // ConfirmBatchDelete and the Activity returning BatchDeletionResult.
    private var pendingBatchDeletion: List<AudioFile> = emptyList()
    private var pendingSingleDeletion: AudioFile? = null

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
        observeMixOfTheDay()
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observePermissionState() {
        val granted = permissionHandlerUseCase.hasAudioPermission() &&
                permissionHandlerUseCase.hasWriteStoragePermission()
        _uiState.update { it.copy(hasStoragePermission = granted) }
        if (granted) loadAudioFiles()
    }

    private fun startObservingPlaybackState() {
        playbackController.getPlaybackState()
            .distinctUntilChanged()
            .onEach { state ->
                _uiState.update { current ->
                    current.copy(
                        currentPlayingId = state.currentAudioFile?.id,
                        isPlaying = state.isPlaying,
                        currentPlaybackAudioFile = state.currentAudioFile
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observePlaylists() {
        playlistRepository.getPlaylists()
            .distinctUntilChanged()
            .onEach { playlists ->
                _uiState.update {
                    it.copy(playlists = playlists.filterNot { p -> p.isAutomatic })
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeFilterOption() {
        viewModelScope.launch {
            settingsRepository.getFilterOption()
                .distinctUntilChanged()
                .collectLatest { filterOption ->
                    _uiState.update { it.copy(currentFilterOption = filterOption) }
                    applySearchAndFilter()
                }
        }
    }

    /**
     * Continuously observes the Mix of the Day playlist from Room.
     *
     * Key design points:
     * - Uses [launchIn] so the coroutine lives for the entire ViewModel lifetime
     * (tied to [viewModelScope]) — no `first()` one-shot collection.
     * - [distinctUntilChanged] prevents redundant recompositions when Room fires
     * spurious invalidations without an actual data change.
     * - Because [PlaylistRepository.getPlaylistById] for the mix ID is backed by a
     * Room @Transaction query, this Flow is guaranteed to emit only after the
     * atomic write in [MixOfTheDayWorker] completes — so `playlist.songs` is
     * always either null (not yet generated) or fully populated (35 tracks).
     */
    private fun observeMixOfTheDay() {
        playlistRepository
            .getPlaylistById(AutomaticPlaylistType.MIX_OF_THE_DAY_PLAYLIST_ID)
            .distinctUntilChanged()
            .onEach { playlist ->
                Log.d(TAG, "Mix of the Day updated: ${playlist?.songs?.size ?: 0} tracks")
                _uiState.update { it.copy(mixOfTheDayPlaylist = playlist) }
            }
            .launchIn(viewModelScope)
    }

    // ── Event handler ─────────────────────────────────────────────────────────

    fun onEvent(event: LibraryEvent) {
        viewModelScope.launch {
            when (event) {

                LibraryEvent.LoadAudioFiles -> {
                    if (_uiState.value.audioFiles.isEmpty() || _uiState.value.error != null) {
                        loadAudioFiles()
                    }
                }

                is LibraryEvent.PermissionGranted -> {
                    val granted = permissionHandlerUseCase.hasAudioPermission() &&
                            permissionHandlerUseCase.hasWriteStoragePermission()
                    _uiState.update { it.copy(hasStoragePermission = granted) }
                    if (granted && _uiState.value.audioFiles.isEmpty() && !_uiState.value.isLoading) {
                        loadAudioFiles()
                    }
                }

                LibraryEvent.CheckPermission -> {
                    val granted = permissionHandlerUseCase.hasAudioPermission() &&
                            permissionHandlerUseCase.hasWriteStoragePermission()
                    _uiState.update { it.copy(hasStoragePermission = granted) }
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

                // ── Playlist dialogs ──────────────────────────────────────────

                is LibraryEvent.AddedToPlaylist -> {
                    if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                        _uiState.update { it.copy(showAddToPlaylistDialog = true) }
                    } else {
                        _uiState.update {
                            it.copy(showAddToPlaylistDialog = true, audioToAddToPlaylist = event.audioFile)
                        }
                    }
                }

                is LibraryEvent.AddedSongToPlaylist -> {
                    val targetPlaylist = event.playlist
                    if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                        val selectedSongs = _uiState.value.selectedAudioFiles.toList()
                        try {
                            val addedCount = playlistRepository.addSongsToPlaylist(targetPlaylist.id, selectedSongs)
                            val skippedCount = selectedSongs.size - addedCount
                            _uiEvent.emit(
                                if (skippedCount > 0) "$addedCount songs added, $skippedCount skipped"
                                else "$addedCount songs added to ${targetPlaylist.name}"
                            )
                            onEvent(LibraryEvent.DeselectAll)
                        } catch (e: Exception) {
                            _uiEvent.emit("Failed to add songs: ${e.message}")
                        }
                    } else {
                        val audioFile = _uiState.value.audioToAddToPlaylist
                        if (audioFile != null) {
                            try {
                                playlistRepository.addSongToPlaylist(targetPlaylist.id, audioFile)
                                _uiEvent.emit("Added to ${targetPlaylist.name}")
                            } catch (e: Exception) {
                                _uiEvent.emit("Failed to add song.")
                            }
                            _uiState.update { it.copy(audioToAddToPlaylist = null) }
                        }
                    }
                    _uiState.update { it.copy(showAddToPlaylistDialog = false) }
                }

                LibraryEvent.DismissAddToPlaylistDialog -> {
                    _uiState.update { it.copy(showAddToPlaylistDialog = false, audioToAddToPlaylist = null) }
                }

                LibraryEvent.ShowCreatePlaylistDialog -> {
                    _uiState.update { it.copy(showCreatePlaylistDialog = true, showAddToPlaylistDialog = false) }
                }

                LibraryEvent.DismissCreatePlaylistDialog -> {
                    _uiState.update { it.copy(showCreatePlaylistDialog = false, audioToAddToPlaylist = null) }
                }

                is LibraryEvent.CreatePlaylistAndAddSongs -> {
                    val name = event.playlistName.trim()
                    if (name.isBlank()) {
                        _uiEvent.emit("Playlist name cannot be empty.")
                        return@launch
                    }
                    if (name.equals("Favorites", ignoreCase = true) ||
                        name.equals("Favorite", ignoreCase = true)
                    ) {
                        _uiEvent.emit("Cannot use this playlist name. Please choose another.")
                        return@launch
                    }

                    val existingPlaylists = playlistRepository.getPlaylists()
                        .first()
                        .filter { !it.isAutomatic }
                    if (existingPlaylists.any { it.name.equals(name, ignoreCase = true) }) {
                        _uiEvent.emit("A playlist with this name already exists.")
                        return@launch
                    }

                    try {
                        val newPlaylistId = playlistRepository.createPlaylist(
                            Playlist(name = name, isAutomatic = false, type = null)
                        )
                        if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                            val selectedSongs = _uiState.value.selectedAudioFiles.toList()
                            playlistRepository.addSongsToPlaylist(newPlaylistId, selectedSongs)
                            _uiEvent.emit("Created '$name' and added ${selectedSongs.size} songs.")
                            onEvent(LibraryEvent.DeselectAll)
                        } else {
                            val audioFile = _uiState.value.audioToAddToPlaylist
                            if (audioFile != null) {
                                playlistRepository.addSongToPlaylist(newPlaylistId, audioFile)
                                _uiEvent.emit("Created '$name' and added the song.")
                                _uiState.update { it.copy(audioToAddToPlaylist = null) }
                            } else {
                                _uiEvent.emit("Created playlist '$name'.")
                            }
                        }
                        _uiState.update { it.copy(showCreatePlaylistDialog = false) }
                    } catch (e: Exception) {
                        _uiEvent.emit("Failed to create playlist: ${e.message}")
                    }
                }

                // ── Deletion ──────────────────────────────────────────────────

                is LibraryEvent.ShowDeleteConfirmation -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        _uiState.update { it.copy(audioFileToDelete = event.audioFile) }
                        onEvent(LibraryEvent.ConfirmDeleteAudioFile)
                    } else {
                        _uiState.update {
                            it.copy(showDeleteConfirmationDialog = true, audioFileToDelete = event.audioFile)
                        }
                    }
                }

                LibraryEvent.DismissDeleteConfirmationDialog -> {
                    _uiState.update {
                        it.copy(
                            showDeleteConfirmationDialog = false,
                            audioFileToDelete = null,
                            showBatchDeleteConfirmationDialog = false
                        )
                    }
                    if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                        onEvent(LibraryEvent.DeselectAll)
                    }
                }

                // ── Bug-2 fix: capture before async system dialog ─────────────
                LibraryEvent.ConfirmDeleteAudioFile -> {
                    _uiState.value.audioFileToDelete?.let { audioFile ->
                        pendingSingleDeletion = audioFile          // ← capture here
                        val intentSender = MediaUtils.deleteAudioFile(context, audioFile) { success, errorMessage ->
                            onEvent(LibraryEvent.DeletionResult(audioFile, success, errorMessage))
                        }
                        if (intentSender != null) {
                            _deleteRequest.emit(IntentSenderRequest.Builder(intentSender).build())
                        }
                    }
                    _uiState.update { it.copy(showDeleteConfirmationDialog = false) }
                }

                is LibraryEvent.DeletionResult -> {
                    // Prefer the event's audioFile; fall back to pendingSingleDeletion
                    val audioFile = event.audioFile.takeIf { it.id != 0L }
                        ?: pendingSingleDeletion
                        ?: return@launch
                    pendingSingleDeletion = null

                    if (event.success) {
                        _uiState.update { currentState ->
                            val updatedList = currentState.audioFiles.filter { it.id != audioFile.id }
                            val filteredList = applyQueryFilter(updatedList, currentState.searchQuery)
                            val sorted = sortAudioFiles(filteredList, currentState.currentFilterOption)
                            sharedAudioDataSource.setPlayingQueue(sorted)
                            currentState.copy(
                                audioFiles = updatedList,
                                filteredAudioFiles = sorted,
                                showDeleteConfirmationDialog = false,
                                audioFileToDelete = null
                            )
                        }
                        playbackController.onAudioFileRemoved(audioFile)
                        sharedAudioDataSource.deleteAudioFile(audioFile)
                        playlistRepository.removeSongFromAllPlaylists(audioFile.id)
                        _uiEvent.emit("Successfully deleted '${audioFile.title}'.")
                    } else {
                        event.errorMessage?.let { _uiEvent.emit(it) }
                        _uiState.update {
                            it.copy(showDeleteConfirmationDialog = false, audioFileToDelete = null)
                        }
                    }
                }

                is LibraryEvent.PlayedNext -> {
                    playbackController.addAudioToQueueNext(event.audioFile)
                    _uiEvent.emit("'${event.audioFile.title}' will play next.")
                }

                LibraryEvent.Retry -> loadAudioFiles()

                // ── Selection ─────────────────────────────────────────────────

                is LibraryEvent.ToggleSelection -> {
                    _uiState.update { current ->
                        val newSelected = current.selectedAudioFiles.toMutableSet().apply {
                            if (!add(event.audioFile)) remove(event.audioFile)
                        }
                        current.copy(selectedAudioFiles = newSelected)
                    }
                }

                LibraryEvent.SelectAll -> {
                    _uiState.update { current ->
                        current.copy(selectedAudioFiles = current.filteredAudioFiles.toSet())
                    }
                }

                LibraryEvent.DeselectAll -> {
                    _uiState.update { it.copy(selectedAudioFiles = emptySet()) }
                }

                // ── Batch deletion ────────────────────────────────────────────

                LibraryEvent.ShowBatchDeleteConfirmation -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        onEvent(LibraryEvent.ConfirmBatchDelete)
                    } else if (_uiState.value.selectedAudioFiles.isNotEmpty()) {
                        _uiState.update { it.copy(showBatchDeleteConfirmationDialog = true) }
                    }
                }

                LibraryEvent.ConfirmBatchDelete -> {
                    val selected = _uiState.value.selectedAudioFiles.toList()
                    if (selected.isEmpty()) return@launch
                    pendingBatchDeletion = selected               // ← capture before async
                    val intentSender = MediaUtils.deleteAudioFiles(context, selected) { success, errorMessage ->
                        // Pre-Q: callback fires synchronously; pendingBatchDeletion is still valid.
                        onEvent(LibraryEvent.BatchDeletionResult(success, errorMessage))
                    }
                    if (intentSender != null) {
                        // Q+: system dialog shown; result arrives via Activity → BatchDeletionResult
                        _deleteRequest.emit(IntentSenderRequest.Builder(intentSender).build())
                    }
                    _uiState.update { it.copy(showBatchDeleteConfirmationDialog = false) }
                }

                is LibraryEvent.BatchDeletionResult -> {
                    if (event.success) {
                        // Use the pre-captured list; fall back to current state if still populated.
                        val selected = pendingBatchDeletion
                            .takeIf { it.isNotEmpty() }
                            ?.toSet()
                            ?: _uiState.value.selectedAudioFiles
                        pendingBatchDeletion = emptyList()

                        _uiState.update { currentState ->
                            val updatedList = currentState.audioFiles.filterNot { selected.contains(it) }
                            val filteredList = applyQueryFilter(updatedList, currentState.searchQuery)
                            val sorted = sortAudioFiles(filteredList, currentState.currentFilterOption)
                            sharedAudioDataSource.setPlayingQueue(sorted)
                            currentState.copy(
                                audioFiles = updatedList,
                                filteredAudioFiles = sorted,
                                selectedAudioFiles = emptySet(),
                                showBatchDeleteConfirmationDialog = false
                            )
                        }
                        selected.forEach { audioFile ->
                            playbackController.onAudioFileRemoved(audioFile)
                            sharedAudioDataSource.deleteAudioFile(audioFile)
                            playlistRepository.removeSongFromAllPlaylists(audioFile.id)
                        }
                        _uiEvent.emit("Successfully deleted ${pluralize(selected.size, "song", "songs")}")
                    } else {
                        pendingBatchDeletion = emptyList()
                        event.errorMessage?.let { _uiEvent.emit(it) }
                        _uiState.update { it.copy(showBatchDeleteConfirmationDialog = false) }
                    }
                }

                else -> Unit
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Applies the current search query and sort option to [_uiState.audioFiles] and
     * pushes the result to [_uiState.filteredAudioFiles].
     */
    private fun applySearchAndFilter() {
        _uiState.update { current ->
            val filtered = applyQueryFilter(current.audioFiles, current.searchQuery)
            current.copy(filteredAudioFiles = sortAudioFiles(filtered, current.currentFilterOption))
        }
    }

    /**
     * Pure filter — extracted so it can be reused identically in deletion result
     * handlers without duplicating the predicate.
     */
    private fun applyQueryFilter(list: List<AudioFile>, query: String): List<AudioFile> {
        if (query.isBlank()) return list
        return list.filter { audio ->
            audio.title.contains(query, ignoreCase = true) ||
                    audio.artist?.contains(query, ignoreCase = true) == true ||
                    audio.album?.contains(query, ignoreCase = true) == true
        }
    }

    private fun sortAudioFiles(list: List<AudioFile>, filterOption: FilterOption): List<AudioFile> =
        when (filterOption) {
            FilterOption.DATE_ADDED_ASC  -> list.sortedBy { it.dateAdded }
            FilterOption.DATE_ADDED_DESC -> list.sortedByDescending { it.dateAdded }
            FilterOption.LENGTH_ASC      -> list.sortedBy { it.duration }
            FilterOption.LENGTH_DESC     -> list.sortedByDescending { it.duration }
            FilterOption.ALPHABETICAL_ASC  -> list.sortedBy { it.title.lowercase() }
            FilterOption.ALPHABETICAL_DESC -> list.sortedByDescending { it.title.lowercase() }
        }

    private suspend fun startAudioPlayback(audioFile: AudioFile) {
        val queue = _uiState.value.filteredAudioFiles.ifEmpty { _uiState.value.audioFiles }
        sharedAudioDataSource.setPlayingQueue(queue)
        playbackController.initiatePlayback(audioFile.uri)
    }

    fun getRequiredPermission(): String = permissionHandlerUseCase.getRequiredReadPermission()

    // ── loadAudioFiles: trigger reconciliation on every MediaStore refresh ─────
    private fun loadAudioFiles() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            getAudioFilesUseCase().collect { result ->
                when (result) {
                    is Resource.Success -> {
                        val audioFiles = result.data ?: emptyList()
                        val currentState = _uiState.value
                        val filtered = applyQueryFilter(audioFiles, currentState.searchQuery)
                        val sorted = sortAudioFiles(filtered, currentState.currentFilterOption)

                        sharedAudioDataSource.setDeviceAudioFiles(audioFiles)
                        sharedAudioDataSource.setPlayingQueue(sorted)

                        _uiState.update {
                            it.copy(
                                audioFiles = audioFiles,
                                filteredAudioFiles = sorted,
                                isLoading = false,
                                error = null
                            )
                        }

                        // Every time MediaStore emits a new list (including after
                        // external deletions), purge playlist rows that no longer
                        // have a backing file on the device.
                        reconcilePlaylistsWithDeviceFiles(audioFiles)
                    }
                    is Resource.Error -> {
                        sharedAudioDataSource.clearPlayingQueue()
                        _uiEvent.emit("Failed to load songs: ${result.message}")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = result.message,
                                filteredAudioFiles = emptyList()
                            )
                        }
                    }
                    is Resource.Loading -> { /* no-op */ }
                }
            }
        }
    }

    /**
     * Launches reconciliation on the IO dispatcher, isolated from the UI
     * collect loop so a DB failure can never crash the audio loading flow.
     */
    private fun reconcilePlaylistsWithDeviceFiles(audioFiles: List<AudioFile>) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistRepository.reconcileWithDeviceFiles(
                existingAudioFileIds = audioFiles.map { it.id }.toSet()
            )
        }
    }
}