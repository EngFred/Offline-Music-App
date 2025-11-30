package com.engfred.musicplayer.feature_library.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.model.FilterOption
/**
 * Data class representing the complete UI state for the Library Screen.
 */
data class LibraryScreenState(
    val audioFiles: List<AudioFile> = emptyList(),
    val filteredAudioFiles: List<AudioFile> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasStoragePermission: Boolean = false,
    val currentPlayingId: Long? = null,
    val isPlaying: Boolean = false,
    val currentFilterOption: FilterOption = FilterOption.ALPHABETICAL_DESC,
    val showAddToPlaylistDialog: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val audioToAddToPlaylist: AudioFile? = null,
    val showDeleteConfirmationDialog: Boolean = false,
    val audioFileToDelete: AudioFile? = null,
    val currentPlaybackAudioFile: AudioFile? = null,

    // multi-selection
    val selectedAudioFiles: Set<AudioFile> = emptySet(),
    val showBatchDeleteConfirmationDialog: Boolean = false,

    val showCreatePlaylistDialog: Boolean = false,
)