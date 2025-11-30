package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.feature_playlist.domain.model.PlaylistSortOrder

sealed class PlaylistDetailEvent {
    data class RenamePlaylist(val newName: String) : PlaylistDetailEvent()
    data object ShowRenameDialog : PlaylistDetailEvent()
    data object HideRenameDialog : PlaylistDetailEvent()
    data class AddSong(val audioFile: AudioFile) : PlaylistDetailEvent()
    data object PlayAll: PlaylistDetailEvent()
    data class PlayAudio(val audioFile: AudioFile) : PlaylistDetailEvent()
    data class LoadPlaylist(val playlistId: Long) : PlaylistDetailEvent()
    data object ShuffleAll : PlaylistDetailEvent()
    data object PlayPause: PlaylistDetailEvent()
    data object PlayNext: PlaylistDetailEvent()
    data object PlayPrev: PlaylistDetailEvent()
    data object DismissAddToPlaylistDialog : PlaylistDetailEvent()
    data class ShowPlaylistsDialog(val audioFile: AudioFile) : PlaylistDetailEvent()
    data class AddedSongToPlaylist(val playlist: Playlist) : PlaylistDetailEvent()
    data class SetPlayNext(val audioFile: AudioFile) : PlaylistDetailEvent()
    data class ShowRemoveSongConfirmation(val audioFile: AudioFile) : PlaylistDetailEvent()
    data object DismissRemoveSongConfirmation : PlaylistDetailEvent()
    data object ConfirmRemoveSong : PlaylistDetailEvent()
    data class SetSortOrder(val sortOrder: PlaylistSortOrder) : PlaylistDetailEvent()
    // Multi-selection events
    data class ToggleSelection(val audioFile: AudioFile) : PlaylistDetailEvent()
    data object SelectAll : PlaylistDetailEvent()
    data object DeselectAll : PlaylistDetailEvent()
    data object ShowBatchRemoveConfirmation : PlaylistDetailEvent()
    data object DismissBatchRemoveConfirmation : PlaylistDetailEvent()
    data object ConfirmBatchRemove : PlaylistDetailEvent()
    data class BatchRemoveResult(
        val success: Boolean,
        val errorMessage: String? = null
    ) : PlaylistDetailEvent()

    data class SetPlaylistCover(val audioFile: AudioFile) : PlaylistDetailEvent()
    data object ConfirmSetCover : PlaylistDetailEvent()
    data object DismissSetCoverConfirmation : PlaylistDetailEvent()
}