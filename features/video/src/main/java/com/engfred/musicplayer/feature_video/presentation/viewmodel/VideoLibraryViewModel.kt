package com.engfred.musicplayer.feature_video.presentation.viewmodel

import android.content.Context
import android.os.Build
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.domain.model.VideoFile
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.VideoRepository
import com.engfred.musicplayer.core.util.MediaUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VideoLibraryEvent {
    data class OnSearchQueryChange(val query: String) : VideoLibraryEvent
    data class OnSearchActiveChange(val active: Boolean) : VideoLibraryEvent
    data class OnSortOptionChange(val sortOption: VideoSortOption) : VideoLibraryEvent
    data class OnFolderSelect(val folder: String?) : VideoLibraryEvent
    data class ShowDeleteConfirmation(val videoFile: VideoFile) : VideoLibraryEvent
    data object DismissDeleteConfirmationDialog : VideoLibraryEvent
    data object ConfirmDeleteVideoFile : VideoLibraryEvent
    data class DeletionResult(
        val videoFile: VideoFile,
        val success: Boolean,
        val errorMessage: String? = null
    ) : VideoLibraryEvent
    data object Refresh : VideoLibraryEvent
}

@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackController: PlaybackController,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private var pendingSingleDeletion: VideoFile? = null

    private val _uiState = MutableStateFlow(VideoLibraryState())
    val uiState: StateFlow<VideoLibraryState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private val _deleteRequest = MutableSharedFlow<IntentSenderRequest>()
    val deleteRequest: SharedFlow<IntentSenderRequest> = _deleteRequest.asSharedFlow()

    init {
        loadVideos()
        observePlaybackState()
    }

    fun onEvent(event: VideoLibraryEvent) {
        when (event) {
            is VideoLibraryEvent.OnSearchQueryChange -> {
                _uiState.update { it.copy(searchQuery = event.query) }
                applyFilterAndSort()
            }
            is VideoLibraryEvent.OnSearchActiveChange -> {
                _uiState.update { it.copy(isSearchActive = event.active, searchQuery = if (!event.active) "" else it.searchQuery) }
                applyFilterAndSort()
            }
            is VideoLibraryEvent.OnSortOptionChange -> {
                _uiState.update { it.copy(sortOption = event.sortOption) }
                applyFilterAndSort()
            }
            is VideoLibraryEvent.OnFolderSelect -> {
                _uiState.update { it.copy(selectedFolder = event.folder) }
                applyFilterAndSort()
            }
            is VideoLibraryEvent.ShowDeleteConfirmation -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    _uiState.update { it.copy(videoFileToDelete = event.videoFile) }
                    onEvent(VideoLibraryEvent.ConfirmDeleteVideoFile)
                } else {
                    _uiState.update {
                        it.copy(
                            showDeleteConfirmationDialog = true,
                            videoFileToDelete = event.videoFile
                        )
                    }
                }
            }
            VideoLibraryEvent.DismissDeleteConfirmationDialog -> {
                _uiState.update {
                    it.copy(
                        showDeleteConfirmationDialog = false,
                        videoFileToDelete = null
                    )
                }
            }
            VideoLibraryEvent.ConfirmDeleteVideoFile -> {
                _uiState.value.videoFileToDelete?.let { videoFile ->
                    pendingSingleDeletion = videoFile
                    val intentSender = MediaUtils.deleteVideoFile(context, videoFile) { success, errorMessage ->
                        onEvent(VideoLibraryEvent.DeletionResult(videoFile, success, errorMessage))
                    }
                    if (intentSender != null) {
                        viewModelScope.launch {
                            _deleteRequest.emit(IntentSenderRequest.Builder(intentSender).build())
                        }
                    }
                }
                _uiState.update { it.copy(showDeleteConfirmationDialog = false) }
            }
            is VideoLibraryEvent.DeletionResult -> {
                val videoFile = event.videoFile.takeIf { it.id != 0L }
                    ?: pendingSingleDeletion
                    ?: return
                pendingSingleDeletion = null

                if (event.success) {
                    _uiState.update { current ->
                        current.copy(
                            videos = current.videos.filter { it.id != videoFile.id },
                            filteredVideos = current.filteredVideos.filter { it.id != videoFile.id },
                            showDeleteConfirmationDialog = false,
                            videoFileToDelete = null
                        )
                    }
                    viewModelScope.launch {
                        _uiEvent.emit("Successfully deleted '${videoFile.title}'.")
                    }
                } else {
                    event.errorMessage?.let { message ->
                        viewModelScope.launch { _uiEvent.emit(message) }
                    }
                    _uiState.update {
                        it.copy(showDeleteConfirmationDialog = false, videoFileToDelete = null)
                    }
                }
            }
            is VideoLibraryEvent.Refresh -> {
                loadVideos()
            }
        }
    }

    private fun loadVideos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            videoRepository.getAllVideoFiles()
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Failed to load videos") }
                }
                .collect { videoList ->
                    val folders = videoList.mapNotNull { it.folderName }.distinct().sorted()
                    _uiState.update {
                        it.copy(
                            videos = videoList,
                            availableFolders = folders,
                            isLoading = false
                        )
                    }
                    applyFilterAndSort()
                }
        }
    }

    private fun observePlaybackState() {
        viewModelScope.launch {
            playbackController.getPlaybackState().collect { pbState ->
                _uiState.update {
                    it.copy(
                        isCastConnected = pbState.castState == CastState.CONNECTED,
                        castState = pbState.castState
                    )
                }
            }
        }
    }

    private fun applyFilterAndSort() {
        val current = _uiState.value
        var filtered = current.videos

        // 1. Folder filter
        if (!current.selectedFolder.isNullOrBlank()) {
            filtered = filtered.filter { it.folderName == current.selectedFolder }
        }

        // 2. Search query filter
        if (current.searchQuery.isNotBlank()) {
            val query = current.searchQuery.trim().lowercase()
            filtered = filtered.filter {
                it.title.lowercase().contains(query) || (it.folderName?.lowercase()?.contains(query) == true)
            }
        }

        // 3. Sort
        val sorted = when (current.sortOption) {
            VideoSortOption.DATE_ADDED_DESC -> filtered.sortedByDescending { it.dateModified }
            VideoSortOption.DATE_ADDED_ASC -> filtered.sortedBy { it.dateModified }
            VideoSortOption.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            VideoSortOption.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            VideoSortOption.DURATION_DESC -> filtered.sortedByDescending { it.duration }
            VideoSortOption.SIZE_DESC -> filtered.sortedByDescending { it.size ?: 0L }
        }

        _uiState.update { it.copy(filteredVideos = sorted) }
    }
}
