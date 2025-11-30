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

/**
 * Data Access Object (DAO) for managing Playlists and PlaylistSongs in the local database.
 */
@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND audioFileId = :audioFileId")
    suspend fun deletePlaylistSong(playlistId: Long, audioFileId: Long)

    @Transaction
    @Query("SELECT * FROM playlists")
    fun getPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongsById(playlistId: Long): Flow<PlaylistWithSongs?>

    @Insert(onConflict = OnConflictStrategy.IGNORE) // Ignore if same event is somehow tried to be inserted
    suspend fun insertSongPlayEvent(event: SongPlayEventEntity)

    /**
     * Retrieves a list of audio file IDs and their play counts within a specified time range,
     * ordered by play count descending.
     * @param sinceTimestamp The start timestamp (milliseconds) for counting play events.
     * @param limit The maximum number of top played songs to return.
     * @return A Flow of list of TopPlayedAudioFileId objects.
     */
    @Query("SELECT audioFileId, COUNT(audioFileId) as playCount FROM song_play_events WHERE timestamp >= :sinceTimestamp GROUP BY audioFileId ORDER BY playCount DESC LIMIT :limit")
    fun getTopPlayedAudioFileIds(sinceTimestamp: Long, limit: Int): Flow<List<TopPlayedAudioFileId>>

    /**
     * Deletes play events older than a specified timestamp.
     * This is crucial for managing database size for "Top Songs (for the week)" functionality.
     */
    @Query("DELETE FROM song_play_events WHERE timestamp < :olderThanTimestamp")
    suspend fun deleteOldPlayEvents(olderThanTimestamp: Long)

    //Query to get all playlist IDs that contain a specific audio file.
    @Query("SELECT playlistId FROM playlist_songs WHERE audioFileId = :audioFileId")
    suspend fun getPlaylistIdsContainingSong(audioFileId: Long): List<Long>

    //Update metadata for the song in all playlists (updates all rows with matching audioFileId)
    @Query("UPDATE playlist_songs SET title = :title, artist = :artist, albumArtUri = :albumArtUri WHERE audioFileId = :audioFileId")
    suspend fun updatePlaylistSongMetadata(audioFileId: Long, title: String, artist: String, albumArtUri: String?)
}