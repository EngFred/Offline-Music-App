package com.engfred.musicplayer.feature_settings.presentation.viewmodel

import android.content.Context
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.util.MediaUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DuplicatesUiState(
    val isLoading: Boolean = true,
    val duplicateGroups: List<List<AudioFile>> = emptyList(),
    val selectedFiles: Set<AudioFile> = emptySet(),
)

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackController: PlaybackController,
    private val playlistRepository: PlaylistRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private val _deleteRequest = MutableSharedFlow<IntentSenderRequest>()
    val deleteRequest: SharedFlow<IntentSenderRequest> = _deleteRequest.asSharedFlow()

    // Required for tracking state before the system deletion dialog returns
    private var pendingBatchDeletion: List<AudioFile> = emptyList()

    init {
        findDuplicates()
    }

    private fun findDuplicates() {
        viewModelScope.launch(Dispatchers.Default) {
            val allFiles = sharedAudioDataSource.deviceAudioFiles.value

            // Group files that have the exact same title, size, and duration
            val groups = allFiles
                .groupBy { "${it.title.lowercase().trim()}_${it.size}_${it.duration}" }
                .values
                .filter { it.size > 1 }

            // Smart Selection: We auto-select all duplicates EXCEPT the first one (the "original")
            val autoSelected = groups.flatMap { it.drop(1) }.toSet()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    duplicateGroups = groups,
                    selectedFiles = autoSelected
                )
            }
        }
    }

    fun toggleSelection(audioFile: AudioFile) {
        _uiState.update { current ->
            val newSelection = current.selectedFiles.toMutableSet()
            if (newSelection.contains(audioFile)) {
                newSelection.remove(audioFile)
            } else {
                newSelection.add(audioFile)
            }
            current.copy(selectedFiles = newSelection)
        }
    }

    fun requestDeletion() {
        viewModelScope.launch {
            val selected = _uiState.value.selectedFiles.toList()
            if (selected.isEmpty()) return@launch

            pendingBatchDeletion = selected
            val intentSender = MediaUtils.deleteAudioFiles(context, selected) { success, msg ->
                // For older Android versions where callback is synchronous
                handleDeletionResult(success, msg)
            }

            if (intentSender != null) {
                // For modern Android (Scoped Storage dialog)
                _deleteRequest.emit(IntentSenderRequest.Builder(intentSender).build())
            }
        }
    }

    fun handleDeletionResult(success: Boolean, errorMessage: String?) {
        viewModelScope.launch {
            if (success) {
                val deletedFiles = pendingBatchDeletion

                // Keep SharedAudioDataSource in sync
                deletedFiles.forEach { file ->
                    playbackController.onAudioFileRemoved(file)
                    sharedAudioDataSource.deleteAudioFile(file)
                    playlistRepository.removeSongFromAllPlaylists(file.id)
                }

                _uiEvent.emit("Successfully freed up space.")
                // Refresh list using remaining files
                findDuplicates()
            } else {
                errorMessage?.let { _uiEvent.emit(it) }
            }
            pendingBatchDeletion = emptyList()
        }
    }
}