package com.engfred.musicplayer.feature_video.data.source.local

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.engfred.musicplayer.feature_video.data.model.VideoFileDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

class VideoContentResolverDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val TAG = "VideoContentResolverDS"

    private val VIDEO_PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.TITLE,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    )

    fun getAllVideosFlow(): Flow<List<VideoFileDto>> = callbackFlow {
        val fetchAndSendVideos = {
            val videoList = mutableListOf<VideoFileDto>()
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    VIDEO_PROJECTION,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val titleCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val displayNameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durationCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateAddedCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val dateModifiedCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val mimeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val dataCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val bucketCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val rawTitle = it.getString(titleCol)
                        val displayName = it.getString(displayNameCol)
                        val title = if (!displayName.isNullOrBlank()) displayName else (rawTitle ?: "Video_$id")
                        val duration = it.getLong(durationCol)
                        val size = it.getLong(sizeCol)
                        val dateAdded = it.getLong(dateAddedCol)
                        val dateModified = it.getLong(dateModifiedCol)
                        val mime = it.getString(mimeCol) ?: "video/*"
                        val dataPath = it.getString(dataCol)
                        val bucketName = it.getString(bucketCol)

                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        videoList.add(
                            VideoFileDto(
                                id = id,
                                title = title,
                                duration = duration,
                                uri = contentUri,
                                thumbnailUri = contentUri, // Coil video decoder loads thumbnail directly from content URI
                                resolution = null,
                                size = size,
                                dateAdded = dateAdded,
                                dateModified = dateModified,
                                mimeType = mime,
                                dataPath = dataPath,
                                bucketDisplayName = bucketName
                            )
                        )
                    }
                }
                trySend(videoList)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching video files", e)
                trySend(emptyList())
            } finally {
                cursor?.close()
            }
        }

        val videoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                fetchAndSendVideos()
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            videoObserver
        )

        fetchAndSendVideos()

        awaitClose {
            context.contentResolver.unregisterContentObserver(videoObserver)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getVideoById(id: Long): VideoFileDto? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        return@withContext getVideoByUri(uri)
    }

    suspend fun getVideoByUri(uri: Uri): VideoFileDto? = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                VIDEO_PROJECTION,
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val titleCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val displayNameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durationCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateAddedCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val dateModifiedCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val mimeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val dataCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val bucketCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                    val id = it.getLong(idCol)
                    val rawTitle = it.getString(titleCol)
                    val displayName = it.getString(displayNameCol)
                    val title = if (!displayName.isNullOrBlank()) displayName else (rawTitle ?: "Video_$id")
                    val duration = it.getLong(durationCol)
                    val size = it.getLong(sizeCol)
                    val dateAdded = it.getLong(dateAddedCol)
                    val dateModified = it.getLong(dateModifiedCol)
                    val mime = it.getString(mimeCol) ?: "video/*"
                    val dataPath = it.getString(dataCol)
                    val bucketName = it.getString(bucketCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    return@withContext VideoFileDto(
                        id = id,
                        title = title,
                        duration = duration,
                        uri = contentUri,
                        thumbnailUri = contentUri,
                        resolution = null,
                        size = size,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        mimeType = mime,
                        dataPath = dataPath,
                        bucketDisplayName = bucketName
                    )
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying single video by Uri", e)
            return@withContext null
        } finally {
            cursor?.close()
        }
    }
}
