package com.engfred.musicplayer.feature_playlist.data.repository

import android.util.Log
import androidx.core.net.toUri
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val sharedAudioDataSource: SharedAudioDataSource
) : PlaylistRepository {

    private val TAG = "PlaylistRepositoryImpl"

    companion object {
        // Single source of truth for play-count logic.
        // Change these constants to affect both getPlaylists() and getPlaylistById().
        private const val TOP_PLAYED_WINDOW_MS   = 30L * 24 * 60 * 60 * 1000L  // 30 days
        private const val TOP_PLAYED_FULL_LIMIT  = 50   // used when opening the playlist detail
        private const val TOP_PLAYED_PREVIEW_LIMIT = 20 // used for the playlist list card preview
        // A song must have been played at least this many times to appear in Most Played.
        // Lowered from 3 → 2 so users with smaller libraries still see results.
        private const val MIN_PLAYS_TO_QUALIFY   = 2
    }

    // ── Domain mappers ────────────────────────────────────────────────────────

    private fun PlaylistWithSongs.toDomain(): Playlist = Playlist(
        id           = playlist.playlistId,
        name         = playlist.name,
        createdAt    = playlist.createdAt,
        songs        = songs.map { it.toDomain() },
        isAutomatic  = playlist.isAutomatic,
        type         = playlist.type,
        customArtUri = playlist.customArtUri?.toUri()
    )

    private fun PlaylistEntity.toDomain(): Playlist = Playlist(
        id           = playlistId,
        name         = name,
        createdAt    = createdAt,
        isAutomatic  = isAutomatic,
        type         = type,
        customArtUri = customArtUri?.toUri()
    )

    private fun Playlist.toEntity(): PlaylistEntity = PlaylistEntity(
        playlistId   = id,
        name         = name,
        createdAt    = createdAt,
        isAutomatic  = isAutomatic,
        type         = type,
        customArtUri = customArtUri?.toString()
    )

    private fun PlaylistSongEntity.toDomain(): AudioFile = AudioFile(
        id           = audioFileId,
        title        = title,
        artist       = artist,
        album        = album,
        duration     = duration,
        uri          = uri,
        albumArtUri  = albumArtUri,
        dateAdded    = dateAdded,
        artistId     = null
    )

    private fun AudioFile.toPlaylistSongEntity(playlistId: Long): PlaylistSongEntity =
        PlaylistSongEntity(
            playlistId   = playlistId,
            audioFileId  = id,
            title        = title,
            artist       = artist,
            album        = album,
            duration     = duration,
            uri          = uri,
            albumArtUri  = albumArtUri,
            dateAdded    = dateAdded
        )

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns System.currentTimeMillis() - TOP_PLAYED_WINDOW_MS.
     *  Extracted so the same value is used everywhere — no more separate
     *  hardcoded 7-day vs 30-day windows in different call sites. */
    private fun topPlayedSinceTimestamp(): Long =
        System.currentTimeMillis() - TOP_PLAYED_WINDOW_MS

    // ── Playlist reads ────────────────────────────────────────────────────────

    override fun getPlaylists(): Flow<List<Playlist>> {
        val dbPlaylistsFlow = playlistDao.getPlaylistsWithSongs()
            .distinctUntilChanged()
            .map { list -> list.map { it.toDomain() } }

        val recentlyAddedFlow = getRecentlyAddedSongs(limit = 20)
        // FIX: use shared constant for timestamp and preview limit — was 7 days / limit 20
        val topPlayedFlow     = getTopPlayedSongs(
            sinceTimestamp = topPlayedSinceTimestamp(),
            limit          = TOP_PLAYED_PREVIEW_LIMIT
        )
        val artistPlaylistsFlow = getArtistPlaylists()

        return combine(
            dbPlaylistsFlow,
            recentlyAddedFlow,
            topPlayedFlow,
            artistPlaylistsFlow
        ) { dbPlaylists, recentlyAddedSongs, topPlayedPairs, artistPlaylists ->

            val userPlaylists = dbPlaylists.filter { it.id > 0 }
            val metaPlaylists = dbPlaylists.filter { it.id < 0 }.associateBy { it.id }
            val automaticPlaylists = mutableListOf<Playlist>()

            // ── Recently Added ─────────────────────────────────────────────
            if (recentlyAddedSongs.isNotEmpty()) {
                automaticPlaylists.add(
                    Playlist(
                        id           = -1,
                        name         = "Recently Added",
                        songs        = recentlyAddedSongs,
                        isAutomatic  = true,
                        type         = AutomaticPlaylistType.RECENTLY_ADDED,
                        customArtUri = metaPlaylists[-1]?.customArtUri
                    )
                )
            }

            // ── Most Played ────────────────────────────────────────────────
            automaticPlaylists.add(
                Playlist(
                    id           = -2,
                    name         = "Most Played",
                    songs        = topPlayedPairs.map { it.first },
                    isAutomatic  = true,
                    type         = AutomaticPlaylistType.MOST_PLAYED,
                    playCounts   = topPlayedPairs.associate { it.first.id to it.second },
                    customArtUri = metaPlaylists[-2]?.customArtUri
                )
            )

            // ── Artist playlists ───────────────────────────────────────────
            automaticPlaylists.addAll(
                artistPlaylists.map { artistPlaylist ->
                    val savedMeta = metaPlaylists[artistPlaylist.id]
                    if (savedMeta != null) artistPlaylist.copy(customArtUri = savedMeta.customArtUri)
                    else artistPlaylist
                }
            )

            automaticPlaylists + userPlaylists
        }
    }

    override fun getPlaylistById(playlistId: Long): Flow<Playlist?> {
        val dbMetadataFlow: Flow<Playlist?> = if (playlistId < 0) {
            playlistDao.getPlaylistWithSongsById(playlistId)
                .distinctUntilChanged()
                .map { it?.toDomain() }
        } else {
            flowOf(null)
        }

        return when {
            // ── Recently Added ─────────────────────────────────────────────
            playlistId == -1L -> combine(
                getRecentlyAddedSongs(limit = 20),
                dbMetadataFlow
            ) { songs, meta ->
                if (songs.isNotEmpty()) Playlist(
                    id           = -1,
                    name         = "Recently Added",
                    songs        = songs,
                    isAutomatic  = true,
                    type         = AutomaticPlaylistType.RECENTLY_ADDED,
                    customArtUri = meta?.customArtUri
                ) else null
            }

            // ── Most Played ────────────────────────────────────────────────
            // FIX: was using a hardcoded 7-day window and limit=50.
            // Now uses the same shared constants as getPlaylists() so both
            // screens show identical data.
            playlistId == -2L -> combine(
                getTopPlayedSongs(
                    sinceTimestamp = topPlayedSinceTimestamp(),
                    limit          = TOP_PLAYED_FULL_LIMIT
                ),
                dbMetadataFlow
            ) { pairs, meta ->
                Playlist(
                    id           = -2,
                    name         = "Most Played",
                    songs        = pairs.map { it.first },
                    isAutomatic  = true,
                    type         = AutomaticPlaylistType.MOST_PLAYED,
                    playCounts   = pairs.associate { it.first.id to it.second },
                    customArtUri = meta?.customArtUri
                )
            }

            // ── Artist playlists ───────────────────────────────────────────
            playlistId < 0 -> {
                val artistId = -playlistId
                combine(
                    sharedAudioDataSource.deviceAudioFiles,
                    dbMetadataFlow
                ) { allAudioFiles, meta ->
                    val songs = allAudioFiles
                        .filter { it.artistId == artistId }
                        .sortedBy { it.title }
                    if (songs.isEmpty()) return@combine null

                    val artistName = songs.first().artist
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown Artist"

                    Playlist(
                        id           = playlistId,
                        name         = artistName,
                        songs        = songs,
                        isAutomatic  = true,
                        type         = AutomaticPlaylistType.ARTIST,
                        customArtUri = meta?.customArtUri
                    )
                }
            }

            // ── User playlists ─────────────────────────────────────────────
            else -> playlistDao.getPlaylistWithSongsById(playlistId)
                .distinctUntilChanged()
                .map { it?.toDomain() }
        }
    }

    // ── Playlist writes ───────────────────────────────────────────────────────

    override suspend fun updatePlaylist(playlist: Playlist) {
        if (playlist.id > 0) {
            playlistDao.updatePlaylist(playlist.toEntity())
        } else {
            playlistDao.insertPlaylist(playlist.toEntity())
        }
    }

    override suspend fun createPlaylist(playlist: Playlist): Long =
        playlistDao.insertPlaylist(playlist.toEntity())

    override suspend fun deletePlaylist(playlistId: Long) =
        playlistDao.deletePlaylist(playlistId)

    // ── Song management ───────────────────────────────────────────────────────

    override suspend fun addSongToPlaylist(playlistId: Long, audioFile: AudioFile) =
        playlistDao.insertPlaylistSong(audioFile.toPlaylistSongEntity(playlistId))

    override suspend fun addSongsToPlaylist(playlistId: Long, audioFiles: List<AudioFile>): Int {
        if (audioFiles.isEmpty()) return 0
        val entities = audioFiles.map { it.toPlaylistSongEntity(playlistId) }
        val results  = playlistDao.insertPlaylistSongs(entities)
        return results.count { it != -1L }
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, audioFileId: Long) =
        playlistDao.deletePlaylistSong(playlistId, audioFileId)

    override suspend fun removeSongFromAllPlaylists(audioFileId: Long) {
        val playlistIds = playlistDao.getPlaylistIdsContainingSong(audioFileId)
        playlistIds.forEach { removeSongFromPlaylist(it, audioFileId) }
        Log.d(TAG, "Removed song $audioFileId from ${playlistIds.size} playlist(s).")
    }

    override suspend fun updateSongInAllPlaylists(updatedAudioFile: AudioFile) {
        try {
            playlistDao.updatePlaylistSongMetadata(
                audioFileId  = updatedAudioFile.id,
                title        = updatedAudioFile.title,
                artist       = updatedAudioFile.artist ?: "Unknown Artist",
                albumArtUri  = updatedAudioFile.albumArtUri?.toString()
            )
            Log.d(TAG, "Updated song ${updatedAudioFile.id} metadata in all playlists.")
        } catch (ex: Exception) {
            Log.e(TAG, "Error updating song metadata in all playlists: ${ex.message}")
        }
    }

    // ── Smart / automatic playlists ───────────────────────────────────────────

    override fun getRecentlyAddedSongs(limit: Int): Flow<List<AudioFile>> =
        sharedAudioDataSource.deviceAudioFiles.map { files ->
            files.sortedByDescending { it.dateAdded }.take(limit)
        }

    override fun getTopPlayedSongs(sinceTimestamp: Long, limit: Int): Flow<List<Pair<AudioFile, Int>>> =
        combine(
            playlistDao.getTopPlayedAudioFileIds(sinceTimestamp, limit),
            sharedAudioDataSource.deviceAudioFiles
        ) { topPlayedIds, allAudioFiles ->
            val audioFileMap = allAudioFiles.associateBy { it.id }
            topPlayedIds
                // FIX: was hardcoded 3 — now uses the shared constant so there's
                // one place to change the threshold.
                .filter { it.playCount >= MIN_PLAYS_TO_QUALIFY }
                .take(limit)
                .mapNotNull { topId ->
                    audioFileMap[topId.audioFileId]?.let { it to topId.playCount }
                }
        }

    override suspend fun recordSongPlayEvent(audioFileId: Long) {
        try {
            playlistDao.insertSongPlayEvent(
                SongPlayEventEntity(
                    audioFileId = audioFileId,
                    timestamp   = System.currentTimeMillis()
                )
            )
            Log.d(TAG, "Recorded play event for audioFileId: $audioFileId")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording play event for audioFileId: $audioFileId", e)
        }
    }

    override suspend fun reconcileWithDeviceFiles(existingAudioFileIds: Set<Long>) {
        try {
            val inPlaylists = playlistDao.getAllPlaylistSongAudioFileIds().toSet()
            val orphanIds   = inPlaylists - existingAudioFileIds

            if (orphanIds.isEmpty()) return

            Log.d(TAG, "Reconciliation: purging ${orphanIds.size} orphaned song(s) from playlists")
            orphanIds.forEach { orphanId ->
                playlistDao.deletePlaylistSongsByAudioFileId(orphanId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playlist reconciliation failed", e)
        }
    }

    private fun getArtistPlaylists(): Flow<List<Playlist>> =
        sharedAudioDataSource.deviceAudioFiles.map { allAudioFiles ->
            allAudioFiles.groupBy { it.artistId }.mapNotNull { (artistId, songs) ->
                if (artistId == null || artistId <= 0) return@mapNotNull null
                val artistName = songs.firstOrNull()?.artist
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "Unknown Artist"
                Playlist(
                    id          = -artistId,
                    name        = artistName,
                    songs       = songs.sortedBy { it.title },
                    isAutomatic = true,
                    type        = AutomaticPlaylistType.ARTIST
                )
            }.sortedBy { it.name }
        }
}