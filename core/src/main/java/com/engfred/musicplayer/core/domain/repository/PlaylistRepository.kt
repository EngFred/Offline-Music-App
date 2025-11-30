package com.engfred.musicplayer.core.domain.repository

import android.util.Log
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.Playlist
import kotlinx.coroutines.flow.Flow

/**
 * Defines the contract for data operations related to playlists.
 * This interface is part of the domain layer, making it independent of data source implementation.
 */
interface PlaylistRepository {
    /**
     * Gets a flow of all playlists.
     */
    fun getPlaylists(): Flow<List<Playlist>>

    /**
     * Gets a specific playlist by its ID.
     */
    fun getPlaylistById(playlistId: Long): Flow<Playlist?>

    /**
     * Creates a new playlist.
     * @return The ID of the newly created playlist.
     */
    suspend fun createPlaylist(playlist: Playlist): Long

    /**
     * Updates an existing playlist (e.g., changing its name).
     */
    suspend fun updatePlaylist(playlist: Playlist)

    /**
     * Deletes a playlist by its ID.
     */
    suspend fun deletePlaylist(playlistId: Long)

    /**
     * Adds a song to an existing playlist.
     */
    suspend fun addSongToPlaylist(playlistId: Long, audioFile: AudioFile)

    /**
     * Removes a song from a playlist.
     */
    suspend fun removeSongFromPlaylist(playlistId: Long, audioFileId: Long)

    suspend fun removeSongFromAllPlaylists(audioFileId: Long)

    suspend fun updateSongInAllPlaylists(updatedAudioFile: AudioFile)

    /**
     * Retrieves a flow of recently added songs, sorted by date added (descending).
     * @param limit The maximum number of songs to retrieve.
     */
    fun getRecentlyAddedSongs(limit: Int): Flow<List<AudioFile>>

    /**
     * Retrieves a flow of top played songs within a specific time frame.
     * @param sinceTimestamp The timestamp (milliseconds) from which to count play events.
     * @param limit The maximum number of songs to retrieve.
     */
    fun getTopPlayedSongs(sinceTimestamp: Long, limit: Int): Flow<List<Pair<AudioFile, Int>>>

    /**
     * Records a play event for a given audio file.
     * This is used to track play counts for "Top Songs" functionality.
     * @param audioFileId The ID of the audio file that was played.
     */
    suspend fun recordSongPlayEvent(audioFileId: Long)

    suspend fun addSongsToPlaylist(playlistId: Long, audioFiles: List<AudioFile>): Int
}