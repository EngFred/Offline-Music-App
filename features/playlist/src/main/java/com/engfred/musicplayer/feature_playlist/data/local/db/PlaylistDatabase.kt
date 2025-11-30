package com.engfred.musicplayer.feature_playlist.data.local.db

import android.net.Uri
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.engfred.musicplayer.feature_playlist.data.local.dao.PlaylistDao
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistSongEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.SongPlayEventEntity

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SongPlayEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN customArtUri TEXT DEFAULT NULL")
            }
        }
    }
}

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