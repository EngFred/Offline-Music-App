package com.engfred.musicplayer.feature_library.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.scale
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_library.data.source.local.ContentResolverDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.CannotWriteException
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException
import org.jaudiotagger.audio.exceptions.UnableToCreateFileException
import org.jaudiotagger.tag.FieldDataInvalidException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
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

    /**
     * Scoped-storage-safe metadata editor using JAudioTagger. Supports MP3 and M4A.
     * Updates only provided fields; preserves others. Uses app cache for temp mods.
     * Avoids JAudioTagger backup issues by writing to a new temp file.
     */
    override suspend fun editAudioMetadata(
        id: Long,
        newTitle: String?,
        newArtist: String?,
        newAlbumArt: ByteArray?,
        context: Context
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

        // Query MIME type for extension mapping
        val projection = arrayOf(MediaStore.Audio.Media.MIME_TYPE)
        val mimeType = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@withContext Resource.Error("Cannot query MIME type")

        val extension = when (mimeType) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            else -> return@withContext Resource.Error("Unsupported MIME type: $mimeType for metadata editing")
        }

        val temp1Name = "edit_temp1_${UUID.randomUUID()}.$extension"
        val temp1 = File(context.cacheDir, temp1Name)
        val temp2Name = "edit_temp2_${UUID.randomUUID()}.$extension"
        val temp2 = File(context.cacheDir, temp2Name)
        var bytesCopied: Long = 0L
        try {
            // Step 1: Copy file to unique temp1 in app cache (to read original content)
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp1.outputStream().use { output ->
                    bytesCopied = input.copyTo(output)
                }
            } ?: return@withContext Resource.Error("Failed to open source file for editing")

            if (bytesCopied == 0L) {
                Log.e(TAG, "No bytes copied - source file may be empty or inaccessible")
                temp1.delete()
                return@withContext Resource.Error("Failed to copy file content - empty source")
            }

            // Step 1.5: Copy full content from temp1 to temp2 (ensures temp2 has valid size for JAudioTagger precheck)
            temp1.inputStream().use { input ->
                temp2.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Full content copied to temp2: ${temp2.length()} bytes")

            // Step 2: Modify tags on temp2 (avoids .bak on temp1; .bak on temp2 is fine in cache)
            TagOptionSingleton.getInstance().setAndroid(true)
            var jaudiotaggerAudioFile = AudioFileIO.read(temp2)
            jaudiotaggerAudioFile.setFile(temp2)  // Explicitly set to ensure correct path for backup/rename
            val tag: Tag = jaudiotaggerAudioFile.getTagOrCreateAndSetDefault()

            // Update only provided fields
            newTitle?.let { tag.setField(FieldKey.TITLE, it) }
            newArtist?.let { tag.setField(FieldKey.ARTIST, it) }

            // Handle album art
            newAlbumArt?.let { artBytes ->
                val processedArt = resizeAndCompressImage(artBytes)
                val artwork: Artwork = ArtworkFactory.getNew().apply {
                    setBinaryData(processedArt)
                    setMimeType("image/jpeg")
                    setPictureType(3) // Front cover
                }
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            // Write back to temp2 (now full size, passes precheck)
            Log.d(TAG, "AudioFile path before write: ${jaudiotaggerAudioFile.file.absolutePath}")
            AudioFileIO.write(jaudiotaggerAudioFile)
            Log.d(TAG, "Tags written to temp2: $temp2Name")

            // Step 3: Stream temp2 back to MediaStore URI
            // (Relies on per-file grant via createWriteRequest or RecoverableSecurityException handling)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                temp2.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return@withContext Resource.Error("Failed to write updated file")

            // Step 4: Update MediaStore metadata (text fields for sync; album art is embedded)
            val updateValues = ContentValues().apply {
                newTitle?.let { put(MediaStore.Audio.Media.TITLE, it) }
                newArtist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            if (updateValues.size() > 0) {
                val updated = context.contentResolver.update(uri, updateValues, null, null)
                Log.d(TAG, "MediaStore updated rows: $updated")
            }

            Resource.Success(Unit)
        } catch (re: android.app.RecoverableSecurityException) {
            Log.w(TAG, "RecoverableSecurityException while editing metadata: ${re.message}")
            throw re
        } catch (e: CannotReadException) {
            Log.e(TAG, "Cannot read audio file", e)
            Resource.Error("Invalid audio file format")
        } catch (e: InvalidAudioFrameException) {
            Log.e(TAG, "Invalid audio frame", e)
            Resource.Error("Corrupted audio file")
        } catch (e: ReadOnlyFileException) {
            Log.e(TAG, "Read-only file", e)
            Resource.Error("File is read-only")
        } catch (e: CannotWriteException) {
            Log.e(TAG, "Cannot write metadata", e)
            Resource.Error("Failed to write metadata")
        } catch (e: UnableToCreateFileException) {
            Log.e(TAG, "Unable to create temp file for metadata", e)
            Resource.Error("Permission denied for file modification")
        } catch (e: FieldDataInvalidException) {
            Log.e(TAG, "Invalid artwork data", e)
            Resource.Error("Failed to process artwork")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to edit audio metadata", e)
            Resource.Error(e.message ?: "Unknown error occurred while editing metadata.")
        } finally {
            // Cleanup temps and any potential .bak (defensive)
            temp1.delete()
            temp2.delete()
            val temp1Bak = File(temp1.absolutePath + ".bak")
            temp1Bak.delete()
            val temp2Bak = File(temp2.absolutePath + ".bak")
            temp2Bak.delete()
        }
    }

    private fun resizeAndCompressImage(imageBytes: ByteArray): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        val maxDimension = maxOf(originalWidth, originalHeight)
        val targetSize = 500
        val scaleFactor = if (maxDimension > targetSize) targetSize.toFloat() / maxDimension else 1f

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val scaledBitmap = bitmap.scale(
            (originalWidth * scaleFactor).toInt(),
            (originalHeight * scaleFactor).toInt()
        )
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)

        bitmap.recycle()
        scaledBitmap.recycle()

        return outputStream.toByteArray()
    }
}