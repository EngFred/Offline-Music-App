package com.engfred.musicplayer.feature_playlist.data.local.entity

import android.net.Uri
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Room Entity for a Song within a Playlist.
 * This entity stores the details of a song as it exists within a specific playlist.
 * It also acts as a join table for the many-to-many relationship.
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "audioFileId"], // Composite primary key
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE // Delete songs if parent playlist is deleted
        )
    ],
    indices = [Index(value = ["playlistId"])] // Index for faster lookups by playlist
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val audioFileId: Long, // Original ID from MediaStore/AudioFile
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Long,
    val uri: Uri,
    val albumArtUri: Uri?,
    val dateAdded: Long, // Date added to the device, from AudioFile
    val addedToPlaylistAt: Long = System.currentTimeMillis() // When it was added to THIS playlist
)