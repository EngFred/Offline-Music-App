package com.engfred.musicplayer.feature_video.data.repository

import android.net.Uri
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.model.VideoFile
import com.engfred.musicplayer.core.domain.repository.VideoRepository
import com.engfred.musicplayer.feature_video.data.model.VideoFileDto
import com.engfred.musicplayer.feature_video.data.source.local.VideoContentResolverDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class VideoRepositoryImpl @Inject constructor(
    private val dataSource: VideoContentResolverDataSource
) : VideoRepository {

    override fun getAllVideoFiles(): Flow<List<VideoFile>> {
        return dataSource.getAllVideosFlow().map { dtoList ->
            dtoList.map { it.toDomain() }
        }
    }

    override suspend fun getVideoFileByUri(uri: Uri): Resource<VideoFile> {
        return try {
            val dto = dataSource.getVideoByUri(uri)
            if (dto != null) {
                Resource.Success(dto.toDomain())
            } else {
                Resource.Error("Video file not found")
            }
        } catch (e: Exception) {
            Resource.Error("Failed to fetch video: ${e.localizedMessage}")
        }
    }

    override suspend fun getVideoById(id: Long): Resource<VideoFile> {
        return try {
            val dto = dataSource.getVideoById(id)
            if (dto != null) {
                Resource.Success(dto.toDomain())
            } else {
                Resource.Error("Video file not found for ID: $id")
            }
        } catch (e: Exception) {
            Resource.Error("Failed to fetch video: ${e.localizedMessage}")
        }
    }

    private fun VideoFileDto.toDomain(): VideoFile {
        return VideoFile(
            id = id,
            title = title,
            duration = duration,
            uri = uri,
            thumbnailUri = thumbnailUri,
            resolution = resolution,
            width = width,
            height = height,
            size = size,
            dateAdded = dateAdded,
            dateModified = dateModified,
            mimeType = mimeType,
            folderName = bucketDisplayName,
            folderPath = dataPath?.substringBeforeLast('/', "")
        )
    }
}
