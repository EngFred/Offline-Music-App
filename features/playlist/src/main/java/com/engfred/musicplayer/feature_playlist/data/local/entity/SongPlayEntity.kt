package com.engfred.musicplayer.feature_playlist.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single play event for an audio file.
 * Used for tracking play counts for features like "Top Songs".
 */
@Entity(tableName = "song_play_events",
    indices = [Index(value = ["audioFileId", "timestamp"])] // Index for efficient querying by song and time
)
data class SongPlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audioFileId: Long,
    val timestamp: Long
)
