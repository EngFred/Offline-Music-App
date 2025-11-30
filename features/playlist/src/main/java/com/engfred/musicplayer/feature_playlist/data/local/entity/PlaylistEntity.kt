package com.engfred.musicplayer.feature_playlist.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType

/**
 * Room Entity for a Playlist.
 * Represents the 'playlists' table in the local database.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val playlistId: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isAutomatic: Boolean = false, // Indicates if this is an automatically generated playlist
    val type: AutomaticPlaylistType? = null // Specifies the type for automatic playlists
)