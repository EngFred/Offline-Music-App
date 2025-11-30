package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.feature_playlist.domain.model.PlaylistSortOrder

data class PlaylistDetailScreenState(
    val playlist: Playlist? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showRenameDialog: Boolean = false,
    val allAudioFiles: List<AudioFile> = emptyList(), //for adding new songs to playlist
    val currentPlayingAudioFile: AudioFile? = null,
    val isPlaying: Boolean = false,
    val showAddToPlaylistDialog: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val audioToAddToPlaylist: AudioFile? = null,
    val showRemoveSongConfirmationDialog: Boolean = false,
    val audioFileToRemove: AudioFile? = null,
    val currentSortOrder: PlaylistSortOrder = PlaylistSortOrder.DATE_ADDED,
    val sortedSongs: List<AudioFile> = emptyList(),
    // Multi-selection
    val selectedSongs: Set<AudioFile> = emptySet(),
    val showBatchRemoveConfirmationDialog: Boolean = false
)