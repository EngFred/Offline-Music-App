package com.engfred.musicplayer.feature_library.data.repository

import android.net.Uri
import android.util.Log
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_library.data.source.local.ContentResolverDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryRepositoryImpl @Inject constructor(
    private val dataSource: ContentResolverDataSource,
    private val settingsRepository: SettingsRepository
) : LibraryRepository {

    private val TAG = "LibraryRepositoryImpl"

    override fun getAllAudioFiles(): Flow<List<AudioFile>> = dataSource.getAllAudioFilesFlow()
        .combine(settingsRepository.getAudioFileTypeFilter()) { dtos, filter ->
            // Filter by MIME type if MP3-only
            val filteredDtos = if (filter == AudioFileTypeFilter.MP3_ONLY) {
                dtos.filter { dto ->
                    dto.mimeType == "audio/mpeg"  // MP3 MIME
                }
            } else {
                dtos
            }
            filteredDtos.map { dto ->
                AudioFile(
                    id = dto.id,
                    title = dto.title ?: "Unknown Title",
                    artist = dto.artist ?: "Unknown Artist",
                    album = dto.album ?: "Unknown Album",
                    duration = dto.duration,
                    uri = dto.uri,
                    albumArtUri = dto.albumArtUri,
                    dateAdded = dto.dateAdded * 1000L,
                    artistId = dto.artistId,
                    size = dto.size
                )
            }
        }
        .map { audioFiles -> audioFiles }  // Flatten the inner map

    override suspend fun getAudioFileByUri(uri: Uri): Resource<AudioFile> {
        return try {
            val dto = dataSource.getAudioFileByUri(uri)
            if (dto != null) {
                val audioFile = AudioFile(
                    id = dto.id,
                    title = dto.title ?: "Unknown Title",
                    artist = dto.artist ?: "Unknown Artist",
                    album = dto.album ?: "Unknown Album",
                    duration = dto.duration,
                    uri = dto.uri,
                    albumArtUri = dto.albumArtUri,
                    dateAdded = dto.dateAdded * 1000L,
                    artistId = dto.artistId,
                    size = dto.size
                )
                Resource.Success(audioFile)
            } else {
                Resource.Error("Audio file not found for uri: $uri")
            }
        } catch (se: android.app.RecoverableSecurityException) {
            Log.w(TAG, "RecoverableSecurityException while getting audio file by uri: ${se.message}")
            throw se
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio file by uri", e)
            Resource.Error(e.message ?: "Unknown error while fetching audio file")
        }
    }

    override suspend fun getAudioById(id: Long): Resource<AudioFile> {
        return try {
            val dto = dataSource.getAudioById(id)
            if (dto != null) {
                val audioFile = AudioFile(
                    id = dto.id,
                    title = dto.title ?: "Unknown Title",
                    artist = dto.artist ?: "Unknown Artist",
                    album = dto.album ?: "Unknown Album",
                    duration = dto.duration,
                    uri = dto.uri,
                    albumArtUri = dto.albumArtUri,
                    dateAdded = dto.dateAdded * 1000L,
                    artistId = dto.artistId,
                    size = dto.size
                )
                Resource.Success(audioFile)
            } else {
                Resource.Error("Audio file not found for id: $id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio file by id", e)
            Resource.Error(e.message ?: "Unknown error while fetching audio file")
        }
    }
}