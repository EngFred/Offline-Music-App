package com.engfred.musicplayer.feature_playlist.presentation.viewmodel.create

import com.engfred.musicplayer.core.domain.model.AudioFile

data class CreatePlaylistState(
    val name: String = "",
    val selectedSongIds: Set<Long> = emptySet(),
    val allSongs: List<AudioFile> = emptyList(),
    val error: String? = null,
    val isSaving: Boolean = false
)
