package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.create

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortOrder { LATEST, OLDEST, A_Z, Z_A }

@HiltViewModel
class CreatePlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val sharedAudioDataSource: SharedAudioDataSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreatePlaylistState())
    val uiState: StateFlow<CreatePlaylistState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<Any>()
    val uiEvent: SharedFlow<Any> = _uiEvent.asSharedFlow()

    // search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // sort order with default = LATEST
    private val _sortOrder = MutableStateFlow(SortOrder.LATEST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    // filter menu open state (kept in VM as requested)
    private val _isFilterMenuOpen = MutableStateFlow(false)
    val isFilterMenuOpen: StateFlow<Boolean> = _isFilterMenuOpen.asStateFlow()

    private val _uiEventInternal = _uiEvent // alias for emit

    /**
     * Exposed debounced, filtered and sorted list of songs. UI should collect this instead of re-filtering locally.
     */
    @OptIn(FlowPreview::class)
    val filteredSongs: StateFlow<List<AudioFile>> = combine(
        _uiState,
        _searchQuery.debounce(300),
        _sortOrder
    ) { state, query, sort ->
        val base = if (query.isBlank()) {
            state.allSongs
        } else {
            val q = query.trim()
            state.allSongs.filter { file ->
                file.title.contains(q, ignoreCase = true) ||
                        (file.artist?.contains(q, ignoreCase = true) ?: false)
            }
        }

        // apply sort
        when (sort) {
            SortOrder.LATEST -> base.sortedByDescending { it.dateAdded }
            SortOrder.OLDEST -> base.sortedBy { it.dateAdded }
            SortOrder.A_Z -> base.sortedBy { it.title.lowercase() }
            SortOrder.Z_A -> base.sortedByDescending { it.title.lowercase() }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // initial value: reflect default sorting (LATEST)
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            // load device songs once and update state
            val allSongs = sharedAudioDataSource.deviceAudioFiles.first()
            _uiState.update { it.copy(allSongs = allSongs) }
        }

        // ensure filteredSongs gets initial values once songs load
        viewModelScope.launch {
            // update initial filtered list after loading songs (filteredSongs stateIn will emit when combine inputs change)
            // Nothing needed here; above init does update _uiState which will trigger filteredSongs combine subscribers
        }
    }

    fun onEvent(event: CreatePlaylistEvent) {
        when (event) {
            is CreatePlaylistEvent.UpdateName -> {
                _uiState.update { it.copy(name = event.name, error = null) }
            }
            is CreatePlaylistEvent.ToggleSongSelection -> {
                _uiState.update {
                    val newSelected = it.selectedSongIds.toMutableSet()
                    if (!newSelected.add(event.songId)) {
                        newSelected.remove(event.songId)
                    }
                    it.copy(selectedSongIds = newSelected)
                }
            }
            is CreatePlaylistEvent.UpdateSearchQuery -> {
                _searchQuery.value = event.query
            }
            is CreatePlaylistEvent.ToggleFilterMenu -> {
                _isFilterMenuOpen.update { !it }
            }
            is CreatePlaylistEvent.DismissFilterMenu -> {
                _isFilterMenuOpen.value = false
            }
            is CreatePlaylistEvent.SetSortOrder -> {
                _sortOrder.value = event.sort
                _isFilterMenuOpen.value = false
            }
            CreatePlaylistEvent.SavePlaylist -> {
                viewModelScope.launch {
                    val name = uiState.value.name.trim()
                    if (name.isBlank()) {
                        _uiState.update { it.copy(error = "Playlist name cannot be empty.") }
                        return@launch
                    }

                    if (name.equals("Favorites", ignoreCase = true) || name.equals("Favorite", ignoreCase = true)) {
                        _uiEventInternal.emit("Cannot create playlist! Use another name.")
                        return@launch
                    }

                    val existingPlaylists = playlistRepository.getPlaylists().first().filter { !it.isAutomatic }
                    if (existingPlaylists.any { it.name.equals(name, ignoreCase = true) }) {
                        _uiState.update { it.copy(error = "Playlist with this name already exists.") }
                        return@launch
                    }

                    // Show small saving indicator (non-blocking)
                    _uiState.update { it.copy(isSaving = true) }

                    try {
                        val newPlaylist = Playlist(name = name, isAutomatic = false, type = null)
                        val playlistId = playlistRepository.createPlaylist(newPlaylist)

                        // persist selected songs
                        uiState.value.selectedSongIds.forEach { songId ->
                            val song = uiState.value.allSongs.find { it.id == songId }
                            if (song != null) {
                                playlistRepository.addSongToPlaylist(playlistId, song)
                            }
                        }

                        _uiEventInternal.emit("Playlist '$name' created!")
                        _uiEventInternal.emit(Unit) // Signal to navigate back
                    } catch (e: Exception) {
                        Log.e("CreatePlaylistViewModel", "Error creating playlist: ${e.message}", e)
                        _uiEventInternal.emit("Error creating playlist: ${e.message}")
                        _uiState.update { it.copy(error = e.message, isSaving = false) }
                    } finally {
                        _uiState.update { it.copy(isSaving = false) }
                    }
                }
            }
        }
    }
}
