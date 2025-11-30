package com.engfred.musicplayer.feature_library.data.source.local

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.engfred.musicplayer.feature_library.data.model.AudioFileDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ContentResolverDataSource @Inject constructor(
    private val context: Context
) {

    private val TAG = "ContentResolverDataSource"

    private val AUDIO_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.SIZE
    )

    private val AUDIO_SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    fun getAllAudioFilesFlow(): Flow<List<AudioFileDto>> = callbackFlow {
        val fetchAndSendAudioFiles = {
            val audioFiles = mutableListOf<AudioFileDto>()
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    AUDIO_PROJECTION,
                    AUDIO_SELECTION,
                    null,
                    null
                )

                cursor?.use {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val artistIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                    val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val title = it.getString(titleColumn)
                        val artist = it.getString(artistColumn)
                        val album = it.getString(albumColumn)
                        val duration = it.getLong(durationColumn)
                        val albumId = it.getLong(albumIdColumn)
                        val dateAdded = it.getLong(dateAddedColumn)
                        val mime = it.getString(mimeColumn)
                        val artistId = it.getLong(artistIdColumn)
                        val size = it.getLong(sizeColumn)

                        val contentUri: Uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        val albumArtUri: Uri? = Uri.withAppendedPath(contentUri, "albumart")

                        audioFiles.add(
                            AudioFileDto(
                                id = id,
                                title = title,
                                artist = artist,
                                album = album,
                                duration = duration,
                                data = contentUri.toString(),
                                uri = contentUri,
                                albumId = albumId,
                                albumArtUri = albumArtUri,
                                dateAdded = dateAdded,
                                mimeType = mime,
                                artistId = artistId,
                                size = size
                            )
                        )
                    }
                }
                trySend(audioFiles)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching audio files", e)
                trySend(emptyList())
            } finally {
                cursor?.close()
            }
        }

        val audioObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                fetchAndSendAudioFiles()
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            audioObserver
        )

        fetchAndSendAudioFiles()

        awaitClose {
            context.contentResolver.unregisterContentObserver(audioObserver)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fetch a single audio file by Uri. Returns AudioFileDto or null if not found.
     *
     * This is a suspend function and executes on Dispatchers.IO.
     */
    suspend fun getAudioFileByUri(uri: Uri): AudioFileDto? = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                AUDIO_PROJECTION,
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val artistIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                    val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)
                    val artist = it.getString(artistColumn)
                    val album = it.getString(albumColumn)
                    val duration = it.getLong(durationColumn)
                    val albumId = it.getLong(albumIdColumn)
                    val dateAdded = it.getLong(dateAddedColumn)
                    val mime = it.getString(mimeColumn)
                    val artistId = it.getLong(artistIdColumn)
                    val size = it.getLong(sizeColumn)

                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val albumArtUri: Uri? = Uri.withAppendedPath(contentUri, "albumart")

                    return@withContext AudioFileDto(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        data = contentUri.toString(),
                        uri = contentUri,
                        albumId = albumId,
                        albumArtUri = albumArtUri,
                        dateAdded = dateAdded,
                        mimeType = mime,
                        artistId = artistId,
                        size = size
                    )
                }
            }
            return@withContext null
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException querying single audio by Uri: ${se.message}")
            // bubble up as null so higher layer forms Resource.Error if desired
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying single audio by Uri", e)
            return@withContext null
        } finally {
            cursor?.close()
        }
    }
}