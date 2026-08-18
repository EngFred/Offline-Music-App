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

    // ---------------------------------------------------------------------------
    // Playlist CRUD
    // ---------------------------------------------------------------------------

    /**
     * Inserts or fully replaces a playlist row.
     * Used for automatic playlists (negative IDs) and initial creation of user playlists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    /**
     * Updates an existing user playlist row in-place.
     * Prefer this over insertPlaylist for user playlists (ID > 0) to avoid
     * accidentally deleting orphaned FK rows on engines without CASCADE.
     */
    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteAllSongsFromPlaylist(playlistId: Long)

    // ---------------------------------------------------------------------------
    // Playlist Songs CRUD
    // ---------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity)

    /**
     * Bulk-insert songs, silently ignoring duplicates (same playlistId + audioFileId).
     * Returns the row-ID of each insertion; -1 means the row was ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSongs(playlistSongs: List<PlaylistSongEntity>): List<Long>

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND audioFileId = :audioFileId")
    suspend fun deletePlaylistSong(playlistId: Long, audioFileId: Long)

    // ---------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------

    /**
     * Observes ALL playlists (user + automatic) together with their songs.
     * No WHERE clause so negative automatic IDs are always included.
     */
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongsById(playlistId: Long): Flow<PlaylistWithSongs?>

    // ---------------------------------------------------------------------------
    // Play-event tracking
    // ---------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongPlayEvent(event: SongPlayEventEntity)

    @Query(
        """
        SELECT audioFileId, COUNT(audioFileId) as playCount
        FROM song_play_events
        WHERE timestamp >= :sinceTimestamp
        GROUP BY audioFileId
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    fun getTopPlayedAudioFileIds(sinceTimestamp: Long, limit: Int): Flow<List<TopPlayedAudioFileId>>

    @Query("DELETE FROM song_play_events WHERE timestamp < :olderThanTimestamp")
    suspend fun deleteOldPlayEvents(olderThanTimestamp: Long)

    // ---------------------------------------------------------------------------
    // Cross-playlist helpers
    // ---------------------------------------------------------------------------

    @Query("SELECT playlistId FROM playlist_songs WHERE audioFileId = :audioFileId")
    suspend fun getPlaylistIdsContainingSong(audioFileId: Long): List<Long>

    @Query(
        """
        UPDATE playlist_songs
        SET title = :title, artist = :artist, albumArtUri = :albumArtUri
        WHERE audioFileId = :audioFileId
        """
    )
    suspend fun updatePlaylistSongMetadata(
        audioFileId: Long,
        title: String,
        artist: String,
        albumArtUri: String?
    )


    // Add inside PlaylistDao interface

    /**
     * Returns every distinct audioFileId that exists in ANY playlist.
     * Used by the reconciliation pass to find orphaned entries.
     */
    @Query("SELECT DISTINCT audioFileId FROM playlist_songs")
    suspend fun getAllPlaylistSongAudioFileIds(): List<Long>

    /**
     * Removes a song from EVERY playlist in one query.
     * More efficient than the existing loop through getPlaylistIdsContainingSong.
     */
    @Query("DELETE FROM playlist_songs WHERE audioFileId = :audioFileId")
    suspend fun deletePlaylistSongsByAudioFileId(audioFileId: Long)
}