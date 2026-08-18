package com.engfred.musicplayer.core.domain.repository

import android.net.Uri
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.model.VideoFile
import kotlinx.coroutines.flow.Flow

/**
 * Defines data operations for video files.
 */
interface VideoRepository {
    /**
     * Emits a reactive stream of all video files available on device.
     */
    fun getAllVideoFiles(): Flow<List<VideoFile>>

    /**
     * Fetch a single VideoFile by its content URI.
     */
    suspend fun getVideoFileByUri(uri: Uri): Resource<VideoFile>

    /**
     * Fetch a single VideoFile by MediaStore ID.
     */
    suspend fun getVideoById(id: Long): Resource<VideoFile>
}
