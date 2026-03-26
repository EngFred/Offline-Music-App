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
    // Atomic Mix-of-the-Day replacement
    //
    // All three operations run inside a single SQLite transaction, so Room
    // fires exactly ONE invalidation signal → exactly ONE downstream Flow
    // emission. This prevents the intermediate "mix exists but songs = []"
    // state that previously caused the UI card to never appear.
    // ---------------------------------------------------------------------------

    /**
     * Atomically replaces the Mix of the Day playlist and its songs.
     *
     * Execution order matters:
     *  1. Delete old songs first (FK child rows) so the parent row can be
     *     re-inserted without constraint violations.
     *  2. Re-insert / replace the playlist metadata row.
     *  3. Bulk-insert the new songs.
     *
     * Because all three steps share the same transaction, observers of
     * [getPlaylistsWithSongs] and [getPlaylistWithSongsById] see either the
     * fully-populated new mix or the old one — never an empty intermediate state.
     */
    @Transaction
    suspend fun replaceMixOfTheDay(
        playlist: PlaylistEntity,
        songs: List<PlaylistSongEntity>
    ) {
        // Step 1: purge old songs before touching the parent row.
        deleteAllSongsFromPlaylist(playlist.playlistId)
        // Step 2: upsert the playlist metadata (name, createdAt, type, etc.).
        insertPlaylist(playlist)
        // Step 3: bulk-insert the new tracks.
        insertPlaylistSongs(songs)
    }

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
}