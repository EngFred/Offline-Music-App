package com.engfred.musicplayer.core.domain.model

import android.net.Uri

/**
 * Represents a user-created playlist in the domain layer.
 */
data class Playlist(
    val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songs: List<AudioFile> = emptyList(),
    val isAutomatic: Boolean,
    val type: AutomaticPlaylistType?,
    val playCounts: Map<Long, Int>? = null,
    val customArtUri: Uri? = null
)