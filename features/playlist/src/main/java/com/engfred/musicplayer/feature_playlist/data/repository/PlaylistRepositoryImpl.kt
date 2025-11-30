package com.engfred.musicplayer.feature_playlist.data.repository

import android.util.Log
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.feature_playlist.data.local.dao.PlaylistDao
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.PlaylistSongEntity
import com.engfred.musicplayer.feature_playlist.data.local.entity.SongPlayEventEntity
import com.engfred.musicplayer.feature_playlist.data.local.model.PlaylistWithSongs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val sharedAudioDataSource: SharedAudioDataSource
) : PlaylistRepository {

    private val TAG = "PlaylistRepositoryImpl"

    private fun PlaylistWithSongs.toDomain(): Playlist {
        return Playlist(
            id = this.playlist.playlistId,
            name = this.playlist.name,
            createdAt = this.playlist.createdAt,
            songs = this.songs.map { it.toDomain() },
            isAutomatic = false,
            type = null
        )
    }

    private fun PlaylistEntity.toDomain(): Playlist {
        return Playlist(
            id = this.playlistId,
            name = this.name,
            createdAt = this.createdAt,
            isAutomatic = false,
            type = null
        )
    }

    private fun PlaylistSongEntity.toDomain(): AudioFile {
        return AudioFile(
            id = this.audioFileId,
            title = this.title,
            artist = this.artist,
            album = this.album,
            duration = this.duration,
            uri = this.uri,
            albumArtUri = this.albumArtUri,
            dateAdded = this.dateAdded,
            artistId = null
        )
    }

    private fun Playlist.toEntity(): PlaylistEntity {
        return PlaylistEntity(
            playlistId = this.id,
            name = this.name,
            createdAt = this.createdAt
        )
    }

    private fun AudioFile.toPlaylistSongEntity(playlistId: Long): PlaylistSongEntity {
        return PlaylistSongEntity(
            playlistId = playlistId,
            audioFileId = this.id,
            title = this.title,
            artist = this.artist,
            album = this.album,
            duration = this.duration,
            uri = this.uri,
            albumArtUri = this.albumArtUri,
            dateAdded = this.dateAdded
        )
    }

    override fun getPlaylists(): Flow<List<Playlist>> {
        // Fetch user-created and automatic playlists and combine them.
        val userPlaylistsFlow = playlistDao.getPlaylistsWithSongs().map { playlistWithSongsList ->
            playlistWithSongsList.map { it.toDomain() }
        }

        val recentlyAddedFlow = getRecentlyAddedSongs(limit = 20)
        val topPlayedFlow = getTopPlayedSongs(
            sinceTimestamp = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L),
            limit = 20
        )
        val artistPlaylistsFlow = getArtistPlaylists()

        return combine(
            userPlaylistsFlow,
            recentlyAddedFlow,
            topPlayedFlow,
            artistPlaylistsFlow
        ) { userPlaylists, recentlyAddedSongs, topPlayedPairs, artistPlaylists ->
            val automaticPlaylists = mutableListOf<Playlist>()

            if (recentlyAddedSongs.isNotEmpty()) {
                automaticPlaylists.add(
                    Playlist(
                        id = -1,
                        name = "Recently Added",
                        songs = recentlyAddedSongs,
                        isAutomatic = true,
                        type = AutomaticPlaylistType.RECENTLY_ADDED
                    )
                )
            }

            automaticPlaylists.add(
                Playlist(
                    id = -2,
                    name = "Most Played",
                    songs = topPlayedPairs.map { it.first },
                    isAutomatic = true,
                    type = AutomaticPlaylistType.MOST_PLAYED,
                    playCounts = topPlayedPairs.associate { it.first.id to it.second }
                )
            )

            automaticPlaylists.addAll(artistPlaylists)
            automaticPlaylists + userPlaylists
        }
    }

    override fun getPlaylistById(playlistId: Long): Flow<Playlist?> {
        return when {
            playlistId == -1L -> getRecentlyAddedSongs(limit = 20).map { songs ->
                if (songs.isNotEmpty()) Playlist(
                    id = -1,
                    name = "Recently Added",
                    songs = songs,
                    isAutomatic = true,
                    type = AutomaticPlaylistType.RECENTLY_ADDED
                ) else null
            }
            playlistId == -2L -> getTopPlayedSongs(
                sinceTimestamp = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L),
                limit = 50
            ).map { pairs ->
                Playlist(
                    id = -2,
                    name = "Most Played",
                    songs = pairs.map { it.first },
                    isAutomatic = true,
                    type = AutomaticPlaylistType.MOST_PLAYED,
                    playCounts = pairs.associate { it.first.id to it.second }
                )
            }
            playlistId < 0 -> {
                val artistId = -playlistId
                sharedAudioDataSource.deviceAudioFiles.map { allAudioFiles ->
                    val songs = allAudioFiles.filter { it.artistId == artistId }.sortedBy { it.title }
                    if (songs.isEmpty()) {
                        null
                    } else {
                        var artistName = songs.first().artist ?: "Unknown Artist"
                        if (artistName == "<unknown>") { artistName = "Unknown Artist" }
                        Playlist(
                            id = playlistId,
                            name = artistName,
                            songs = songs,
                            isAutomatic = true,
                            type = AutomaticPlaylistType.ARTIST
                        )
                    }
                }
            }
            else -> playlistDao.getPlaylistWithSongsById(playlistId).map { playlistWithSongs ->
                playlistWithSongs?.toDomain()
            }
        }
    }

    override suspend fun createPlaylist(playlist: Playlist): Long {
        return playlistDao.insertPlaylist(playlist.toEntity())
    }

    override suspend fun updatePlaylist(playlist: Playlist) {
        playlistDao.updatePlaylist(playlist.toEntity())
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }

    override suspend fun addSongToPlaylist(playlistId: Long, audioFile: AudioFile) {
        val playlistSongEntity = audioFile.toPlaylistSongEntity(playlistId)
        playlistDao.insertPlaylistSong(playlistSongEntity)
    }

    override suspend fun addSongsToPlaylist(playlistId: Long, audioFiles: List<AudioFile>): Int {
        if (audioFiles.isEmpty()) return 0

        val entities = audioFiles.map { it.toPlaylistSongEntity(playlistId) }

        // Batch insert.
        // Duplicate songs will be ignored by Room (OnConflictStrategy.IGNORE).
        // It returns a list of Row IDs. -1 indicates the row was ignored.
        val results = playlistDao.insertPlaylistSongs(entities)

        // Count how many were NOT -1 to get the number of successfully added songs.
        val addedCount = results.count { it != -1L }

        return addedCount
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, audioFileId: Long) {
        playlistDao.deletePlaylistSong(playlistId, audioFileId)
    }

    override suspend fun removeSongFromAllPlaylists(audioFileId: Long) {
        val playlistIds = playlistDao.getPlaylistIdsContainingSong(audioFileId)
        playlistIds.forEach { playlistId ->
            removeSongFromPlaylist(playlistId, audioFileId)
        }
        Log.d(TAG, "Removed song ID: $audioFileId from all playlists (${playlistIds.size} playlists affected).")
    }

    override suspend fun updateSongInAllPlaylists(updatedAudioFile: AudioFile) {
        try {
            playlistDao.updatePlaylistSongMetadata(
                audioFileId = updatedAudioFile.id,
                title = updatedAudioFile.title,
                artist = updatedAudioFile.artist ?: "Unknown Artist",
                albumArtUri = updatedAudioFile.albumArtUri?.toString()
            )
            Log.d(TAG, "Updated song metadata in all playlists with ID: ${updatedAudioFile.id}")
        } catch (ex: Exception) {
            Log.e(TAG, "Error updating song metadata in all playlists: ${ex.message}")
        }
    }

    override fun getRecentlyAddedSongs(limit: Int): Flow<List<AudioFile>> {
        return sharedAudioDataSource.deviceAudioFiles.map { allAudioFiles ->
            allAudioFiles
                .sortedByDescending { it.dateAdded }
                .take(limit)
        }
    }

    override fun getTopPlayedSongs(sinceTimestamp: Long, limit: Int): Flow<List<Pair<AudioFile, Int>>> {
        return combine(
            playlistDao.getTopPlayedAudioFileIds(sinceTimestamp, limit),
            sharedAudioDataSource.deviceAudioFiles
        ) { topPlayedIds, allAudioFiles ->
            val audioFileMap = allAudioFiles.associateBy { it.id }
            val filtered = topPlayedIds
                .asSequence()
                .filter { it.playCount >= 3 }
                .take(limit)
                .toList()
            filtered.mapNotNull { topId ->
                audioFileMap[topId.audioFileId]?.let { audioFile ->
                    audioFile to topId.playCount
                }
            }
        }
    }

    override suspend fun recordSongPlayEvent(audioFileId: Long) {
        try {
            val playEvent = SongPlayEventEntity(
                audioFileId = audioFileId,
                timestamp = System.currentTimeMillis()
            )
            playlistDao.insertSongPlayEvent(playEvent)
            Log.d(TAG, "Recorded play event for audioFileId: $audioFileId")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording song play event for audioFileId: $audioFileId", e)
        }
    }

    private fun getArtistPlaylists(): Flow<List<Playlist>> {
        return sharedAudioDataSource.deviceAudioFiles.map { allAudioFiles ->
            allAudioFiles.groupBy { it.artistId }.mapNotNull { (artistId, songs) ->
                if (artistId == null || artistId <= 0) return@mapNotNull null
                var artistName = songs.firstOrNull()?.artist ?: "Unknown Artist"
                if (artistName == "<unknown>") {
                    artistName = "Unknown Artist"
                }
                Playlist(
                    id = -artistId,
                    name = artistName,
                    songs = songs.sortedBy { it.title },
                    isAutomatic = true,
                    type = AutomaticPlaylistType.ARTIST
                )
            }.sortedBy { it.name }
        }
    }
}