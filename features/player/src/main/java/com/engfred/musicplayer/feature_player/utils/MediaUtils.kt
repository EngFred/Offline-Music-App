package com.engfred.musicplayer.feature_player.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Saves a Bitmap image to the device's public Pictures directory using MediaStore.
 *
 * @param context The application context.
 * @param bitmap The Bitmap image to save.
 * @param filename The desired filename (e.g., "album_art.jpg").
 * @param mimeType The MIME type of the image (e.g., "image/jpeg", "image/png").
 * @return True if the image was saved successfully, false otherwise.
 */
suspend fun saveBitmapToPictures(
    context: Context,
    bitmap: Bitmap,
    filename: String,
    mimeType: String
): Boolean {
    return withContext(Dispatchers.IO) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "MusicPlayerAlbumArt")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // Mark as pending until fully written
            }
        }

        val resolver = context.contentResolver
        var uri: Uri? = null
        var outputStream: OutputStream? = null

        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                return@withContext false
            }

            outputStream = resolver.openOutputStream(uri)
            if (outputStream == null) {
                return@withContext false
            }

            // Compress the bitmap based on MIME type
            val format = if (mimeType == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = 90 // Adjust quality as needed for JPEG

            if (!bitmap.compress(format, quality, outputStream)) {
                return@withContext false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0) // Unmark as pending
                resolver.update(uri, contentValues, null, null)
            }
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // If something went wrong, delete the pending entry
                resolver.delete(uri, null, null)
            }
            return@withContext false
        } finally {
            outputStream?.close()
        }
    }
}

/**
 * Loads a bitmap from a given URI.
 * @param context The application context.
 * @param uri The Uri of the image.
 * @return The Bitmap, or null if loading fails.
 */
suspend fun loadBitmapFromUri(context: Context, uri: Uri?): Bitmap? {
    return withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}