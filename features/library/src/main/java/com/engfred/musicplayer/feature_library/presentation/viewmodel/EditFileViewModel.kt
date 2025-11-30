package com.engfred.musicplayer.feature_library.presentation.viewmodel

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.util.MediaUtils
import com.engfred.musicplayer.feature_library.domain.usecases.EditAudioMetadataUseCase
import com.engfred.musicplayer.feature_library.domain.usecases.GetAllAudioFilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the song editing screen.
 */
data class EditFileUiState(
    val title: String = "",
    val artist: String = "",
    val albumArtPreviewUri: Uri? = null,
    val isSaving: Boolean = false,
    val audioFile: AudioFile? = null
)

@HiltViewModel
class EditFileViewModel @Inject constructor(
    private val getAllAudioFilesUseCase: GetAllAudioFilesUseCase,
    private val editAudioMetadataUseCase: EditAudioMetadataUseCase,
    private val playlistRepository: PlaylistRepository,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackController: PlaybackController
) : ViewModel() {

    // UI state for the screen
    private val _uiState = MutableStateFlow(EditFileUiState())
    val uiState: StateFlow<EditFileUiState> = _uiState

    // One-time events for UI (e.g., navigation, permission requests)
    private val _events = MutableSharedFlow<EditFileViewModel.Event>()
    val events = _events.asSharedFlow()

    // Original values used to check for changes before saving
    private var originalTitle: String? = null
    private var originalArtist: String? = null
    private var originalAlbumArtUri: Uri? = null

    // State to hold pending changes during a permission request
    private var pendingAudioId: Long? = null
    private var pendingTitle: String? = null
    private var pendingArtist: String? = null
    private var pendingAlbumArt: ByteArray? = null

    // Flag to track if initial load has been attempted (prevents repeated errors on flow emissions)
    private var initialLoadCompleted = false

    sealed class Event {
        data class Success(val message: String) : EditFileViewModel.Event()
        data class Error(val message: String) : EditFileViewModel.Event()
        data class RequestWritePermission(val intentSender: IntentSender) : EditFileViewModel.Event()
    }

    /**
     * Loads a specific audio file by its ID and updates the UI state.
     * The flow is collected to get the latest data.
     */
    fun loadAudioFile(audioId: Long) {
        viewModelScope.launch {
            getAllAudioFilesUseCase().collect { resource ->
                if (resource is Resource.Success) {
                    val audioFile = resource.data?.find { it.id == audioId }
                    if (audioFile != null) {
                        if (!initialLoadCompleted) {
                            updateOriginalValues(audioFile)
                            initialLoadCompleted = true
                            _uiState.update { state ->
                                state.copy(
                                    title = originalTitle ?: "",
                                    artist = originalArtist ?: "Unknown Artist",
                                    albumArtPreviewUri = originalAlbumArtUri,
                                    audioFile = audioFile
                                )
                            }
                        }
                        // Optionally update UI on subsequent emissions if needed, but skip for edit screen stability
                    } else if (!initialLoadCompleted) {
                        _events.emit(Event.Error("Audio file not found."))
                        initialLoadCompleted = true
                    }
                } else if (resource is Resource.Error && !initialLoadCompleted) {
                    _events.emit(Event.Error(resource.message ?: "Error loading audio files."))
                    initialLoadCompleted = true
                }
            }
        }
    }

    fun updateTitle(newTitle: String) {
        _uiState.update { it.copy(title = newTitle) }
    }

    fun updateArtist(newArtist: String) {
        _uiState.update { it.copy(artist = newArtist) }
    }

    fun pickImage(imageUri: Uri) {
        _uiState.update { it.copy(albumArtPreviewUri = imageUri) }
    }

    /**
     * Initiates the process of saving changes to the media store.
     * It checks for changes, reads the new album art, and then calls `performSave`.
     */
    fun saveChanges(audioId: Long, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val currentState = _uiState.value
            var albumArtBytes = getAlbumArtBytes(currentState.albumArtPreviewUri, context)

            // Fail early if new album art cannot be read
            if (albumArtBytes == null && currentState.albumArtPreviewUri != originalAlbumArtUri) {
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(Event.Error("Failed to read album art image."))
                return@launch
            }

            // Compress if too large
            if (albumArtBytes != null && albumArtBytes.size > 2 * 1024 * 1024) { // 2MB limit
                albumArtBytes = MediaUtils.compressImage(albumArtBytes)
            }

            // Save pending values only if they've changed
            pendingAudioId = audioId
            pendingTitle = currentState.title.takeIf { it != originalTitle }
            pendingArtist = currentState.artist.takeIf { it != originalArtist }
            pendingAlbumArt = albumArtBytes.takeIf { currentState.albumArtPreviewUri != originalAlbumArtUri }

            // Handle case where nothing has changed
            if (pendingTitle == null && pendingArtist == null && pendingAlbumArt == null) {
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(Event.Success("No changes to save."))
                clearPending()
                return@launch
            }

            performSave(audioId, context)
        }
    }

    /**
     * Continues the save process after a user grants a required permission.
     */
    fun continueSaveAfterPermission(context: Context) {
        val audioId = pendingAudioId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            performSave(audioId, context)
        }
    }

    /**
     * Executes the save operation and handles `RecoverableSecurityException` for permissions.
     * Removed version gate—legacy support via permissions.
     */
    private suspend fun performSave(audioId: Long, context: Context) {
        try {
            val result = editAudioMetadataUseCase(
                id = audioId,
                title = pendingTitle,
                artist = pendingArtist,
                albumArt = pendingAlbumArt,
                context = context
            )
            handleSaveResult(result)
        } catch (e: RecoverableSecurityException) {
            _uiState.update { it.copy(isSaving = false) }
            val intentSender = e.userAction.actionIntent.intentSender
            if (intentSender != null) {
                _events.emit(Event.RequestWritePermission(intentSender))
            } else {
                _events.emit(Event.Error("Need permission to edit this audio file."))
                clearPending()
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSaving = false) }
            _events.emit(Event.Error(e.message ?: "Failed to update song metadata."))
            clearPending()
        }
    }

    /**
     * Handles the result of the save operation. On success, it locally updates the `AudioFile`
     * object and notifies the `PlaylistRepository` of the changes.
     */
    private suspend fun handleSaveResult(result: Resource<Unit>) {
        _uiState.update { it.copy(isSaving = false) }
        when (result) {
            is Resource.Success -> {
                val currentAudio = _uiState.value.audioFile
                if (currentAudio == null) {
                    _events.emit(Event.Error("Updated, but local audio was not available to reflect changes."))
                    clearPending()
                    return
                }

                // Create an updated AudioFile object with the new metadata
                // Note: albumArtUri remains the audio URI-derived value; preview is temporary for UI
                val updatedAudio = currentAudio.copy(
                    title = pendingTitle ?: currentAudio.title,
                    artist = pendingArtist ?: currentAudio.artist
                    // Do not override albumArtUri—let query refresh from embedded art
                )

                // Update UI and the original values for future comparisons
                updateOriginalValues(updatedAudio)
                _uiState.update { state ->
                    state.copy(
                        title = updatedAudio.title,
                        artist = updatedAudio.artist ?: "Unknown Artist",
                        // Keep preview for immediate UI if art changed; else use audio's (will refresh on next query)
                        albumArtPreviewUri = if (pendingAlbumArt != null) state.albumArtPreviewUri else updatedAudio.albumArtUri,
                        audioFile = updatedAudio
                    )
                }

                // Notify other parts of the app that the song has been updated
                try {
                    playlistRepository.updateSongInAllPlaylists(updatedAudio)
                } catch (e: Exception) {
                    _events.emit(Event.Error("Updated metadata but failed to update playlists: ${e.message}"))
                }
                sharedAudioDataSource.updateAudioFile(updatedAudio)
                playbackController.updateAudioMetadata(updatedAudio)
                _events.emit(Event.Success("Song info updated successfully."))
                clearPending()
            }

            is Resource.Error -> {
                _events.emit(Event.Error(result.message ?: "Failed to update song metadata."))
                clearPending()
            }

            else -> {
                _events.emit(Event.Error("Unexpected state while saving metadata."))
                clearPending()
            }
        }
    }

    /**
     * Reads the byte array of an image from a given URI.
     */
    private fun getAlbumArtBytes(uri: Uri?, context: Context): ByteArray? {
        return uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    inputStream.readBytes()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun updateOriginalValues(audioFile: AudioFile) {
        originalTitle = audioFile.title
        originalArtist = audioFile.artist ?: "Unknown Artist"
        originalAlbumArtUri = audioFile.albumArtUri
    }

    private fun clearPending() {
        pendingAudioId = null
        pendingTitle = null
        pendingArtist = null
        pendingAlbumArt = null
    }
}