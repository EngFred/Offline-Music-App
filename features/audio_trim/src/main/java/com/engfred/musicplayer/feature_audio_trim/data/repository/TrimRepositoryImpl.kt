package com.engfred.musicplayer.feature_audio_trim.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_audio_trim.domain.model.TrimResult
import com.engfred.musicplayer.feature_audio_trim.domain.repository.TrimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

private const val TAG = "TrimRepository"

@UnstableApi
class TrimRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : TrimRepository {

    override suspend fun trimAudio(
        audioFile: AudioFile,
        startMs: Long,
        endMs: Long
    ): Flow<TrimResult> = callbackFlow {
        coroutineScope {
            launch(Dispatchers.IO) {
                performTrim(this@callbackFlow, audioFile, startMs, endMs)
            }
        }
        awaitClose { }
    }

    private suspend fun performTrim(
        channel: kotlinx.coroutines.channels.SendChannel<TrimResult>,
        audioFile: AudioFile,
        startMs: Long,
        endMs: Long
    ) {
        var transformer: Transformer? = null
        val done = CompletableDeferred<Unit>()

        try {
            if (!coroutineContext.isActive) return

            val inputUri = audioFile.uri
            val outputDir = context.getExternalFilesDir(null)
            val outputFileName = "trimmed_${audioFile.id}_${System.currentTimeMillis()}.m4a"
            val outputFile = File(outputDir, outputFileName)

            val mediaItem = MediaItem.Builder()
                .setUri(inputUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

            transformer = withContext(Dispatchers.Main) {
                Transformer.Builder(context)
                    .experimentalSetTrimOptimizationEnabled(false)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.completeExceptionally(exportException)
                        }

                        override fun onFallbackApplied(
                            composition: Composition,
                            originalTransformationRequest: TransformationRequest,
                            fallbackTransformationRequest: TransformationRequest
                        ) { }
                    })
                    .build()
            }

            withContext(Dispatchers.Main) {
                transformer.start(editedMediaItem, outputFile.absolutePath)
            }

            done.await()

            val newDuration = endMs - startMs
            if (newDuration <= 0) {
                channel.trySend(TrimResult.Error("Trim duration too short"))
                return
            }

            val trimmedTitle = audioFile.title  // Use original title without "(trimmed)"
            val artist = audioFile.artist ?: "Unknown Artist"
            val album = audioFile.album ?: "Unknown Album"
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$trimmedTitle.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.TITLE, trimmedTitle)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.DURATION, newDuration)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val newUri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Failed to insert into MediaStore")

            context.contentResolver.openOutputStream(newUri)?.use { outStream ->
                outputFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outStream)
                }
            } ?: throw IOException("Cannot write to new MediaStore URI")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(newUri, updateValues, null, null)
            }

            // Embed metadata (non-fatally: continue to success even if it fails, e.g., no album art)
            try {
                val processedAlbumArt = audioFile.albumArtUri?.let { artUri ->
                    context.contentResolver.openInputStream(artUri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.isNotEmpty()) resizeAndCompressImage(bytes) else null
                    }
                }
                embedMetadata(newUri, trimmedTitle, artist, album, processedAlbumArt, context)
                Log.d(TAG, "Metadata embedding completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Metadata embedding failed (e.g., no album art or format issue), but trim succeeded", e)
                // Non-fatal: Audio file is already saved and playable without embedded metadata
            }

            // Media Scan and Cleanup
            val newPath = getFilePath(context, newUri)
            newPath?.let {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(it),
                    arrayOf("audio/mp4"),
                    null
                )
            }

            outputFile.delete()

            Log.d(TAG, "Trim process completed successfully")
            channel.trySend(TrimResult.Success)

        } catch (e: IOException) {
            Log.e(TAG, "IO error during trim", e)
            channel.trySend(TrimResult.Error(e.message ?: "Trim failed: IO Error"))
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied during trim", e)
            channel.trySend(TrimResult.PermissionDenied)
        } catch (e: ExportException) {
            Log.e(TAG, "Export error during trim (unsupported input format?)", e)
            channel.trySend(TrimResult.Error(e.message ?: "Trim failed: Export Error (check input format)"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during trim", e)
            channel.trySend(TrimResult.Error(e.message ?: "Trim failed: Unexpected Error"))
        } finally {
            transformer?.let { t ->
                withContext(Dispatchers.Main) { t.cancel() }
            }
        }
    }

    /**
     * Scoped-storage-safe metadata embedding using JAudioTagger.
     */
    private suspend fun embedMetadata(
        uri: Uri,
        title: String,
        artist: String,
        album: String,
        albumArt: ByteArray?,
        context: Context
    ) {
        var tempFile: File? = null
        try {
            // Set pending for Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pendingValues = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 1) }
                context.contentResolver.update(uri, pendingValues, null, null)
            }

            // Copy to temp in cacheDir
            tempFile = copyToTempFile(context, uri)
            if (tempFile == null) return

            // Modify on temp
            TagOptionSingleton.getInstance().setAndroid(true)
            val jaudiotaggerAudioFile = AudioFileIO.read(tempFile)
            val tag: Tag = jaudiotaggerAudioFile.getTagOrCreateAndSetDefault()

            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            tag.setField(FieldKey.ALBUM, album)

            albumArt?.let { artBytes ->
                val artwork: Artwork = ArtworkFactory.getNew().apply {
                    setBinaryData(artBytes)
                    setMimeType("image/jpeg")
                    setPictureType(3)
                }
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            AudioFileIO.write(jaudiotaggerAudioFile)

            // Stream back
            if (!streamTempToMediaStore(tempFile, uri, context)) return

            // Finalize pending
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalizeValues = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, finalizeValues, null, null)
            }

            // Re-scan
            val path = getFilePath(context, uri)
            path?.let {
                MediaScannerConnection.scanFile(context, arrayOf(it), arrayOf("audio/mp4"), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error embedding metadata", e)
            // Non-fatal; MediaStore has basic info
        } finally {
            tempFile?.delete()
        }
    }

    /**
     * Copies the audio file from URI to a temp file in app's cacheDir.
     */
    private fun copyToTempFile(context: Context, uri: Uri): File? {
        val tempDir = context.cacheDir
        val tempFile = File.createTempFile("trim_audio_", ".m4a", tempDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to temp: ${e.message}")
            tempFile.delete()
            null
        }
    }

    /**
     * Streams the modified temp file back to MediaStore URI.
     */
    private fun streamTempToMediaStore(tempFile: File, uri: Uri, context: Context): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream temp to MediaStore: ${e.message}")
            false
        }
    }

    private fun getFilePath(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
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