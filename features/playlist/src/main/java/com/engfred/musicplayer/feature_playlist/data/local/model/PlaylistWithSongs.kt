package com.engfred.musicplayer.feature_playlist.data.local.model

import androidx.room.Embedded
import androidx.room.Relation
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistSongEntity

/**
 * Data class to represent a Playlist with its associated songs, used for Room relations.
 */
data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "playlistId"
    )
    val songs: List<PlaylistSongEntity>
)