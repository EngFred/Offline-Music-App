package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.create

sealed class CreatePlaylistEvent {
    data class UpdateName(val name: String) : CreatePlaylistEvent()
    data class ToggleSongSelection(val songId: Long) : CreatePlaylistEvent()
    data class UpdateSearchQuery(val query: String) : CreatePlaylistEvent()
    object SavePlaylist : CreatePlaylistEvent()

    object ToggleFilterMenu : CreatePlaylistEvent()
    object DismissFilterMenu : CreatePlaylistEvent()
    data class SetSortOrder(val sort: SortOrder) : CreatePlaylistEvent()
}
