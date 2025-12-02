package com.engfred.musicplayer.feature_edit.presentation.viewModel

import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.util.MediaUtils
import com.engfred.musicplayer.feature_edit.domain.usecases.EditAudioMetadataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val editAudioMetadataUseCase: EditAudioMetadataUseCase,
    private val playlistRepository: PlaylistRepository,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackController: PlaybackController
) : ViewModel() {

    // UI state for the screen
    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState

    // One-time events for UI (e.g., navigation, permission requests)
    private val _events = MutableSharedFlow<EditUIEvent>()
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

    // Flag to track if initial load has been completed
    private var initialLoadCompleted = false

    /**
     * Loads a specific audio file by its ID using the efficient repository method.
     */
    fun loadAudioFile(audioId: Long) {
        if (initialLoadCompleted) return

        viewModelScope.launch {
            when (val resource = libraryRepository.getAudioById(audioId)) {
                is Resource.Success -> {
                    val audioFile = resource.data
                    if (audioFile != null) {
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
                    } else {
                        _events.emit(EditUIEvent.Error("Audio file not found."))
                    }
                }
                is Resource.Error -> {
                    _events.emit(EditUIEvent.Error(resource.message ?: "Error loading audio file."))
                }
                else -> {
                    // Handle loading state if necessary
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
                _events.emit(EditUIEvent.Error("Failed to read album art image."))
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
                _events.emit(EditUIEvent.Success("No changes to save."))
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
                _events.emit(EditUIEvent.RequestWritePermission(intentSender))
            } else {
                _events.emit(EditUIEvent.Error("Need permission to edit this audio file."))
                clearPending()
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSaving = false) }
            _events.emit(EditUIEvent.Error(e.message ?: "Failed to update song metadata."))
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
                    _events.emit(EditUIEvent.Error("Updated, but local audio was not available to reflect changes."))
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
                    _events.emit(EditUIEvent.Error("Updated metadata but failed to update playlists: ${e.message}"))
                }
                sharedAudioDataSource.updateAudioFile(updatedAudio)
                playbackController.updateAudioMetadata(updatedAudio)
                _events.emit(EditUIEvent.Success("Song info updated successfully."))
                clearPending()
            }

            is Resource.Error -> {
                _events.emit(EditUIEvent.Error(result.message ?: "Failed to update song metadata."))
                clearPending()
            }

            else -> {
                _events.emit(EditUIEvent.Error("Unexpected state while saving metadata."))
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