package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.model.DjMixPlaylistFilter

data class DjMixLauncherUiState(
    val isLoading: Boolean = true,
    val automaticPlaylists: List<Playlist> = emptyList(),
    val userPlaylists: List<Playlist> = emptyList(),
    val currentFilter: DjMixPlaylistFilter = DjMixPlaylistFilter.ALL,
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && automaticPlaylists.isEmpty() && userPlaylists.isEmpty()
}