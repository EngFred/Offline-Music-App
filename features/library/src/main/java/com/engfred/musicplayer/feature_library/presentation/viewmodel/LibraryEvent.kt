package com.engfred.musicplayer.feature_library.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.model.FilterOption

/**
 * Sealed class representing all possible events that can occur on the Library Screen.
 */
sealed class LibraryEvent {
    data object LoadAudioFiles : LibraryEvent()
    data object PermissionGranted : LibraryEvent()
    data object CheckPermission : LibraryEvent()
    data class PlayAudio(val audioFile: AudioFile) : LibraryEvent()
    data class SearchQueryChanged(val query: String) : LibraryEvent()
    data class FilterSelected(val filterOption: FilterOption) : LibraryEvent()
    data object DismissAddToPlaylistDialog : LibraryEvent()
    data class AddedSongToPlaylist(val playlist: Playlist) : LibraryEvent()
    data class ShowDeleteConfirmation(val audioFile: AudioFile) : LibraryEvent()
    data object DismissDeleteConfirmationDialog : LibraryEvent()
    data object ConfirmDeleteAudioFile : LibraryEvent()
    data class DeletionResult(
        val audioFile: AudioFile,
        val success: Boolean,
        val errorMessage: String? = null
    ) : LibraryEvent()

    data class AddedToPlaylist(val audioFile: AudioFile) : LibraryEvent()
    data class PlayedNext(val audioFile: AudioFile) : LibraryEvent()
    data object Retry: LibraryEvent()

    //multi-selection and batch deletion
    data class ToggleSelection(val audioFile: AudioFile) : LibraryEvent()
    data object SelectAll : LibraryEvent()
    data object DeselectAll : LibraryEvent()
    data object ShowBatchDeleteConfirmation : LibraryEvent()
    data object ConfirmBatchDelete : LibraryEvent()
    data class BatchDeletionResult(
        val success: Boolean,
        val errorMessage: String? = null
    ) : LibraryEvent()

    data object ShowCreatePlaylistDialog : LibraryEvent()
    data object DismissCreatePlaylistDialog : LibraryEvent()
    data class CreatePlaylistAndAddSongs(val playlistName: String) : LibraryEvent()
}