package com.engfred.musicplayer.feature_playlist.data.local.db

import android.net.Uri
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.engfred.musicplayer.feature_playlist.data.local.dao.PlaylistDao
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistSongEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.SongPlayEventEntity // Import the new entity

/**
 * Room Database for managing Playlists and their songs.
 */
@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SongPlayEventEntity::class
    ],
    version = 2,
    exportSchema = false // For production, set to true and provide schema exports
)
@TypeConverters(Converters::class)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
}

/**
 * Type converters for Room to handle custom data types like Uri.
 */
class Converters {
    @TypeConverter
    fun fromUri(uri: Uri?): String? {
        return uri?.toString()
    }

    @TypeConverter
    fun toUri(uriString: String?): Uri? {
        return uriString?.let { Uri.parse(it) }
    }
}