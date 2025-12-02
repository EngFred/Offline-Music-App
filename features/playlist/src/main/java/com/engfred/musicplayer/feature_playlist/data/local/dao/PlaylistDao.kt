package com.engfred.musicplayer.feature_playlist.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistSongEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.SongPlayEventEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.TopPlayedAudioFileId
import com.engfred.musicplayer.feature_playlist.data.local.model.PlaylistWithSongs
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // If we send a Playlist with ID -1,
    // and it doesn't exist, Room creates it. If it exists, Room updates it.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSongs(playlistSongs: List<PlaylistSongEntity>): List<Long>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND audioFileId = :audioFileId")
    suspend fun deletePlaylistSong(playlistId: Long, audioFileId: Long)

    @Transaction
    @Query("SELECT * FROM playlists")
    fun getPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongsById(playlistId: Long): Flow<PlaylistWithSongs?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongPlayEvent(event: SongPlayEventEntity)

    @Query("SELECT audioFileId, COUNT(audioFileId) as playCount FROM song_play_events WHERE timestamp >= :sinceTimestamp GROUP BY audioFileId ORDER BY playCount DESC LIMIT :limit")
    fun getTopPlayedAudioFileIds(sinceTimestamp: Long, limit: Int): Flow<List<TopPlayedAudioFileId>>

    @Query("DELETE FROM song_play_events WHERE timestamp < :olderThanTimestamp")
    suspend fun deleteOldPlayEvents(olderThanTimestamp: Long)

    @Query("SELECT playlistId FROM playlist_songs WHERE audioFileId = :audioFileId")
    suspend fun getPlaylistIdsContainingSong(audioFileId: Long): List<Long>

    @Query("UPDATE playlist_songs SET title = :title, artist = :artist, albumArtUri = :albumArtUri WHERE audioFileId = :audioFileId")
    suspend fun updatePlaylistSongMetadata(audioFileId: Long, title: String, artist: String, albumArtUri: String?)
}