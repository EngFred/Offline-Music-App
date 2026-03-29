package com.engfred.musicplayer.feature_edit.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.scale
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.feature_edit.domain.repository.EditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Metadata editor backed by FFmpeg (ffmpeg-kit-min community fork).
 *
 * ── Why FFmpeg instead of JAudioTagger ───────────────────────────────────────
 *
 * JAudioTagger is a pure-Java library with solid support for MP3 and M4A, but it
 * silently fails or throws unchecked exceptions for FLAC, OGG, OPUS, WAV, WMA,
 * and any file with non-standard tagging. The previous implementation short-circuited
 * with "Unsupported MIME type" for anything outside MP3/M4A, which meant the Edit
 * feature was invisible to a large portion of a typical music library.
 *
 * FFmpeg handles all of the above formats via `-c copy -metadata key=value`.
 * No re-encode takes place — the audio bitstream is copied byte-for-byte, only
 * the container metadata atoms/frames are rewritten.
 *
 * ── Album art embedding ───────────────────────────────────────────────────────
 *
 * Art is embedded using format-specific FFmpeg flags:
 * • MP3  : ID3v2 APIC frame  (-id3v2_version 3 + stream disposition)
 * • M4A/MP4 : covr atom      (-disposition:v:0 attached_pic)
 * • FLAC : METADATA_BLOCK_PICTURE  (-disposition:v:0 attached_pic)
 * • OGG/OPUS/WAV : title and artist are written; art is silently skipped
 *   because embedding art in Vorbis comment blocks or RIFF chunks requires a
 *   more complex multi-pass approach that offers no benefit for a music player.
 *
 * ── RecoverableSecurityException ─────────────────────────────────────────────
 *
 * On Android 10/11, writing back to a MediaStore URI for a file not owned by
 * this app throws RecoverableSecurityException.  We re-throw it so the ViewModel
 * can launch the system's permission IntentSender — same contract as before.
 *
 * ── Threading ─────────────────────────────────────────────────────────────────
 *
 * All I/O and FFmpeg execution run on [Dispatchers.IO].  [suspendCancellableCoroutine]
 * is used for FFmpeg so coroutine cancellation propagates to the native process.
 */
class EditRepositoryImpl @Inject constructor() : EditRepository {

    private val TAG = "EditRepositoryImpl"

    override suspend fun editAudioMetadata(
        id: Long,
        newTitle: String?,
        newArtist: String?,
        newAlbumArt: ByteArray?,
        context: Context
    ): Resource<Unit> = withContext(Dispatchers.IO) {

        val uri = android.content.ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
        )

        // ── 1. Resolve MIME type and extension ───────────────────────────────
        // FFmpeg auto-detects format from file headers, but we still need the
        // extension to name temp files correctly and to branch art-embedding logic.
        val mimeType = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@withContext Resource.Error("Cannot query MIME type — file may no longer exist")

        val extension = mimeTypeToExtension(mimeType)

        // Temp files in app's private cache directory.
        val tempInput  = File(context.cacheDir, "edit_in_${System.currentTimeMillis()}.$extension")
        val tempOutput = File(context.cacheDir, "edit_out_${System.currentTimeMillis()}.$extension")
        var artFile: File? = null

        try {
            // ── 2. Copy source URI → temp input ──────────────────────────────
            // RecoverableSecurityException from openInputStream is uncommon but
            // re-thrown so the ViewModel can request user permission.
            try {
                context.contentResolver.openInputStream(uri)?.use { src ->
                    tempInput.outputStream().use { src.copyTo(it) }
                } ?: return@withContext Resource.Error("Cannot open source file for reading")
            } catch (rse: android.app.RecoverableSecurityException) {
                throw rse // ViewModel handles this
            } catch (se: SecurityException) {
                throw se  // Also propagate
            } catch (e: IOException) {
                return@withContext Resource.Error("IO error reading source: ${e.message}")
            }

            if (tempInput.length() == 0L) {
                return@withContext Resource.Error("Source file is empty or inaccessible")
            }

            // ── 3. Prepare resized album art temp file (if provided) ──────────
            if (newAlbumArt != null) {
                val resized = resizeAndCompressImage(newAlbumArt)
                artFile = File(context.cacheDir, "edit_art_${System.currentTimeMillis()}.jpg")
                artFile.writeBytes(resized)
                Log.d(TAG, "Art file prepared: ${artFile.length()} bytes")
            }

            // ── 4. Build FFmpeg command ───────────────────────────────────────
            val command = buildEditCommand(
                inputPath  = tempInput.absolutePath,
                outputPath = tempOutput.absolutePath,
                newTitle   = newTitle,
                newArtist  = newArtist,
                artPath    = artFile?.absolutePath,
                extension  = extension
            )

            val ffmpegOk = executeFFmpeg(command)

            if (!ffmpegOk || !tempOutput.exists() || tempOutput.length() == 0L) {
                Log.e(TAG, "FFmpeg metadata write failed for .$extension")
                return@withContext Resource.Error(
                    "Could not write metadata for this .$extension file. " +
                            "The file may be DRM-protected or use an unsupported sub-format."
                )
            }

            // ── 5. Stream modified file back to the original MediaStore URI ───
            // This write triggers RecoverableSecurityException on Android 10/11
            // when the file was not created by this app (common for files copied
            // from a PC or downloaded by another app).
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tempOutput.inputStream().use { it.copyTo(out) }
                } ?: return@withContext Resource.Error("Cannot write modified file back to storage")
            } catch (rse: android.app.RecoverableSecurityException) {
                throw rse // ViewModel launches IntentSender for user approval
            } catch (se: SecurityException) {
                throw se
            } catch (e: IOException) {
                return@withContext Resource.Error("IO error writing to storage: ${e.message}")
            }

            // ── 6. Sync MediaStore text columns ───────────────────────────────
            // FFmpeg already embedded the tags in the file; updating these columns
            // keeps MediaStore's indexed cache in sync so the Library screen
            // immediately reflects the new title/artist without a full media scan.
            val updateValues = ContentValues().apply {
                newTitle?.let  { put(MediaStore.Audio.Media.TITLE, it) }
                newArtist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            if (updateValues.size() > 0) {
                val rows = context.contentResolver.update(uri, updateValues, null, null)
                Log.d(TAG, "MediaStore updated $rows row(s) for id=$id")
            }

            Log.i(TAG, "Metadata edit success — id=$id ($extension)")
            Resource.Success(Unit)

        } catch (rse: android.app.RecoverableSecurityException) {
            // Do NOT wrap — ViewModel checks for this exact type to launch IntentSender.
            Log.w(TAG, "RecoverableSecurityException — awaiting user permission grant")
            throw rse
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException during metadata edit", se)
            throw se
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during metadata edit for id=$id", e)
            Resource.Error(e.message ?: "Unknown error editing metadata")
        } finally {
            // Clean up all temp files regardless of success or failure.
            tempInput.delete()
            tempOutput.delete()
            artFile?.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFmpeg command builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the FFmpeg metadata-edit command for [extension].
     *
     * Core strategy:
     * 1. `-c copy` — stream copy, no audio re-encode, zero quality loss.
     * 2. `-map_metadata 0` — carry forward ALL existing tags from the source as
     *    the base; explicit `-metadata` flags then override only the changed fields.
     * 3. Art embedding is format-specific:
     *    - MP3 : strip existing art with `-map 0:a`, attach new art with
     *      `-map 1:v`, then apply id3v2 disposition flags.
     *    - M4A/FLAC : same strip-and-attach approach with `attached_pic`.
     *    - OGG/OPUS/WAV : art is skipped silently — title/artist still written.
     * 4. When no new art is provided: `-map 0` preserves all streams (including
     *    any existing embedded art) without modification.
     */
    private fun buildEditCommand(
        inputPath: String,
        outputPath: String,
        newTitle: String?,
        newArtist: String?,
        artPath: String?,
        extension: String
    ): String {
        val embedArt = artPath != null && supportsEmbeddedArt(extension)

        return buildString {
            append("-y ")
            append("-i \"$inputPath\" ")

            if (embedArt) {
                // Second input: the JPEG art file.
                append("-i \"$artPath\" ")
            }

            // ── Stream mapping ──────────────────────────────────────────────
            if (embedArt) {
                // Take ONLY audio from the source (this discards any pre-existing
                // embedded art stream so we don't end up with two cover images).
                append("-map 0:a ")
                // Then attach the new art as a video stream.
                append("-map 1:v ")
            } else {
                // No art change: preserve every stream from source as-is.
                append("-map 0 ")
            }

            // ── Codec + metadata ────────────────────────────────────────────
            append("-c copy ")
            append("-map_metadata 0 ") // Copy all existing tags as the base.

            // ── Art disposition flags (per container) ───────────────────────
            if (embedArt) {
                when (extension) {
                    "mp3" -> {
                        // ID3v2 APIC frame — required flags for players to
                        // recognise the attached picture as album art.
                        append("-id3v2_version 3 ")
                        append("-metadata:s:v title=\"Album cover\" ")
                        append("-metadata:s:v comment=\"Cover (front)\" ")
                    }
                    "m4a", "mp4", "flac" -> {
                        // M4A covr atom / FLAC METADATA_BLOCK_PICTURE.
                        // The attached_pic disposition tells FFmpeg this image
                        // stream is album art rather than an embedded video clip.
                        append("-disposition:v:0 attached_pic ")
                    }
                    // OGG/OPUS/WAV: art not embedded (see supportsEmbeddedArt).
                }
            }

            // ── Override title/artist ────────────────────────────────────────
            // These override the same keys copied by -map_metadata 0, so no
            // duplicate or conflicting tags are written.
            newTitle?.let  { append("-metadata title=\"${it.escapeShell()}\" ") }
            newArtist?.let { append("-metadata artist=\"${it.escapeShell()}\" ") }

            append("\"$outputPath\"")
        }
    }

    /**
     * Returns true for containers where the `-map 0:a -map 1:v` album-art
     * embedding approach produces a correctly tagged result.
     *
     * OGG Vorbis and OPUS use METADATA_BLOCK_PICTURE embedded in Vorbis comment
     * headers, which requires a different command structure.  WAV has no standard
     * art tag at all.  For these three formats the user still gets title/artist
     * edits — only the art embedding is skipped.
     */
    private fun supportsEmbeddedArt(extension: String): Boolean = when (extension) {
        "mp3", "m4a", "mp4", "flac" -> true
        else                        -> false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFmpeg execution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes an FFmpeg command asynchronously and suspends until completion.
     *
     * [suspendCancellableCoroutine] integrates FFmpegKit's callback-based async
     * API with Kotlin coroutines.  When the calling coroutine is cancelled (e.g.
     * the user navigates away mid-save), [FFmpegKit.cancel] is called on the
     * specific session so the native process is terminated promptly.
     */
    private suspend fun executeFFmpeg(command: String): Boolean =
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "[FFmpeg] $command")

            val session = FFmpegKit.executeAsync(
                command,
                { completedSession ->
                    if (cont.isActive) {
                        val ok = ReturnCode.isSuccess(completedSession.returnCode)
                        if (!ok) {
                            Log.e(
                                TAG,
                                "[FFmpeg] FAILED rc=${completedSession.returnCode} " +
                                        "— ${completedSession.allLogsAsString?.take(600)}"
                            )
                        }
                        cont.resume(ok)
                    }
                },
                null, // log callback  — null = default FFmpegKit logger
                null  // statistics callback — not needed for metadata edits
            )

            cont.invokeOnCancellation {
                Log.d(TAG, "[FFmpeg] Cancelling session ${session.sessionId}")
                FFmpegKit.cancel(session.sessionId)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Image processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scales any album art down to a 500×500 px maximum and re-compresses to
     * JPEG at 85 % quality before embedding.  This keeps embedded art from
     * bloating the audio file with multi-megabyte images while still looking
     * sharp on all modern device displays.
     */
    private fun resizeAndCompressImage(imageBytes: ByteArray): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

        val originalWidth  = options.outWidth
        val originalHeight = options.outHeight
        val maxDimension   = maxOf(originalWidth, originalHeight)
        val targetSize     = 500
        val scaleFactor    = if (maxDimension > targetSize) targetSize.toFloat() / maxDimension else 1f

        val bitmap       = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val scaledBitmap = bitmap.scale(
            (originalWidth  * scaleFactor).toInt().coerceAtLeast(1),
            (originalHeight * scaleFactor).toInt().coerceAtLeast(1)
        )

        val out = ByteArrayOutputStream()
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)

        bitmap.recycle()
        scaledBitmap.recycle()

        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps MIME type → file extension for temp file naming.
     * Covers every codec in the ffmpeg-kit-min audio set.
     * Defaults to "mp3" for unknown types (FFmpeg still auto-detects from header).
     */
    private fun mimeTypeToExtension(mime: String): String = when (mime) {
        "audio/mpeg", "audio/mp3"                        -> "mp3"
        "audio/mp4", "audio/x-m4a",
        "audio/aac",  "audio/mp4a-latm"                  -> "m4a"
        "audio/flac", "audio/x-flac"                     -> "flac"
        "audio/ogg",  "audio/x-ogg", "application/ogg"  -> "ogg"
        "audio/opus"                                     -> "opus"
        "audio/wav",  "audio/x-wav", "audio/wave"        -> "wav"
        "audio/x-ms-wma"                                 -> "wma"
        else                                             -> "mp3"
    }

    /**
     * Escapes characters that would break FFmpeg's command-line argument parsing.
     * Applied to user-supplied strings (title, artist) before they are interpolated
     * into the -metadata flags.
     */
    private fun String.escapeShell(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
}