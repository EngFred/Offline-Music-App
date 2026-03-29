package com.engfred.musicplayer.feature_trim.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_trim.domain.model.TrimResult
import com.engfred.musicplayer.feature_trim.domain.repository.TrimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume

private const val TAG = "TrimRepository"

/**
 * Audio trim implementation backed by FFmpeg (ffmpeg-kit-min community fork).
 *
 * ── Why FFmpeg instead of Media3 Transformer ─────────────────────────────────
 *
 * Media3 Transformer with setAudioMimeType(MimeTypes.AUDIO_AAC) performs a full
 * decode → re-encode pipeline on every trim operation. This made trims take 8-15
 * seconds and fail on FLAC, OGG, OPUS, WAV, and many non-standard MP3/AAC files.
 *
 * FFmpeg with `-c copy` (stream copy) remuxes the compressed bitstream directly
 * without decoding a single audio frame. For a 5-minute track the trim completes
 * in under 200 ms on any modern Android device. Because no re-encode happens, there
 * is zero quality loss regardless of the original codec or bitrate.
 *
 * ── Format support ────────────────────────────────────────────────────────────
 *
 * Stream copy works for: MP3, AAC/M4A, FLAC, OGG Vorbis, OPUS, WAV, WMA, and
 * any other format in the ffmpeg-kit-min codec set.
 * For edge-case formats where the source codec cannot be remuxed into the same
 * container (extremely rare), an automatic fallback re-encodes to AAC/M4A.
 *
 * ── Metadata ──────────────────────────────────────────────────────────────────
 *
 * `-map_metadata 0` copies all existing ID3/Vorbis/FLAC tags from the source
 * directly into the trimmed output. No JAudioTagger step is needed.
 *
 * ── Threading ─────────────────────────────────────────────────────────────────
 *
 * `FFmpegKit.executeAsync` is used together with `suspendCancellableCoroutine`
 * so that coroutine cancellation propagates to FFmpeg's native cancel mechanism.
 * All heavy I/O (URI copy, file streaming) runs on Dispatchers.IO.
 */
class TrimRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : TrimRepository {

    /**
     * Returns a cold [Flow] that emits exactly one [TrimResult] and then completes.
     * The upstream [performTrim] is fully suspending — callers on any dispatcher
     * are safe; all blocking work is confined to [Dispatchers.IO] internally.
     */
    override suspend fun trimAudio(
        audioFile: AudioFile,
        startMs: Long,
        endMs: Long
    ): Flow<TrimResult> = flow {
        emit(performTrim(audioFile, startMs, endMs))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core trim logic
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun performTrim(
        audioFile: AudioFile,
        startMs: Long,
        endMs: Long
    ): TrimResult = withContext(Dispatchers.IO) {

        val durationMs = endMs - startMs
        if (durationMs <= 0L) {
            return@withContext TrimResult.Error("Invalid trim range: duration is zero or negative")
        }

        // Resolve the output container format from the source MIME type so that the
        // trimmed file is saved in the same format as the original (e.g. .flac → .flac).
        val sourceMimeType = context.contentResolver.getType(audioFile.uri) ?: "audio/mpeg"
        val extension      = mimeTypeToExtension(sourceMimeType)

        // FFmpeg requires real filesystem paths.  We copy the content:// URI into
        // the app's private cache directory, run FFmpeg there, then stream the result
        // back to MediaStore.  This temp-file approach is the most compatible across
        // Android versions and OEM storage implementations.
        val tempInput    = File(context.cacheDir, "trim_in_${System.currentTimeMillis()}.$extension")
        val tempOutput   = File(context.cacheDir, "trim_out_${System.currentTimeMillis()}.$extension")
        val tempFallback = File(context.cacheDir, "trim_fallback_${System.currentTimeMillis()}.m4a")

        try {
            // ── 1. Copy source URI → temp input ──────────────────────────────
            try {
                context.contentResolver.openInputStream(audioFile.uri)?.use { src ->
                    tempInput.outputStream().use { src.copyTo(it) }
                } ?: return@withContext TrimResult.Error("Cannot open source audio file")
            } catch (e: SecurityException) {
                return@withContext TrimResult.PermissionDenied
            } catch (e: IOException) {
                return@withContext TrimResult.Error("Failed to read source file: ${e.message}")
            }

            if (tempInput.length() == 0L) {
                return@withContext TrimResult.Error("Source file is empty or unreadable")
            }

            // ── 2. FFmpeg stream copy (lossless, ~200 ms) ─────────────────────
            //
            // Command breakdown:
            //   -y                    : overwrite output without prompt
            //   -ss {startSec}        : input seek to start time (fast — before -i)
            //   -i {input}            : source file (FFmpeg auto-detects format)
            //   -t {durSec}           : output duration
            //   -c copy               : stream copy — no decode/re-encode
            //   -map_metadata 0       : copy all tags from input to output
            //   -avoid_negative_ts make_zero : fix timestamp rebase after seek
            //
            // For audio there are no keyframe alignment concerns the way video has
            // them.  Audio frame sizes (26 ms for MP3) are small enough that the
            // result is effectively sample-accurate.
            val startSec = startMs / 1000.0
            val durSec   = durationMs / 1000.0

            val streamCopyCommand = buildString {
                append("-y ")
                append("-ss $startSec ")
                append("-i \"${tempInput.absolutePath}\" ")
                append("-t $durSec ")
                append("-c copy ")
                append("-map_metadata 0 ")
                append("-avoid_negative_ts make_zero ")
                append("\"${tempOutput.absolutePath}\"")
            }

            val streamCopyOk = executeFFmpeg(streamCopyCommand)

            // ── 3. Fallback: re-encode to AAC/M4A ────────────────────────────
            // This path is taken only when stream copy fails, which happens on
            // formats where the source codec cannot be remuxed into the same
            // container (e.g. certain ADTS-wrapped AAC files, or raw PCM in WAV).
            // Re-encode is slower but universally safe.
            val (finalOutput, finalMimeType) = if (streamCopyOk && tempOutput.exists() && tempOutput.length() > 0L) {
                tempOutput to extensionToMimeType(extension)
            } else {
                if (!streamCopyOk) {
                    Log.w(TAG, "[TRIM] Stream copy failed — falling back to AAC re-encode")
                }
                tempOutput.delete()

                val reencodeCommand = buildString {
                    append("-y ")
                    append("-ss $startSec ")
                    append("-i \"${tempInput.absolutePath}\" ")
                    append("-t $durSec ")
                    append("-c:a aac -b:a 192k ")
                    append("-map_metadata 0 ")
                    append("-avoid_negative_ts make_zero ")
                    append("\"${tempFallback.absolutePath}\"")
                }

                val reencodeOk = executeFFmpeg(reencodeCommand)

                if (!reencodeOk || !tempFallback.exists() || tempFallback.length() == 0L) {
                    return@withContext TrimResult.Error(
                        "FFmpeg trim failed for .$extension format. The file may be corrupted or DRM-protected."
                    )
                }
                tempFallback to "audio/mp4"
            }

            val outputExtension = if (finalMimeType == "audio/mp4") "m4a" else extension

            // ── 4. Insert into MediaStore ─────────────────────────────────────
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${audioFile.title}.$outputExtension")
                put(MediaStore.Audio.Media.MIME_TYPE, finalMimeType)
                put(MediaStore.Audio.Media.TITLE, audioFile.title)
                put(MediaStore.Audio.Media.ARTIST, audioFile.artist ?: "Unknown Artist")
                put(MediaStore.Audio.Media.ALBUM, audioFile.album ?: "Unknown Album")
                put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val newUri: Uri = try {
                context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return@withContext TrimResult.Error("Failed to create a MediaStore entry for the trimmed file")
            } catch (e: SecurityException) {
                return@withContext TrimResult.PermissionDenied
            }

            // ── 5. Stream FFmpeg output → MediaStore URI ──────────────────────
            try {
                context.contentResolver.openOutputStream(newUri)?.use { out ->
                    finalOutput.inputStream().use { it.copyTo(out) }
                } ?: run {
                    context.contentResolver.delete(newUri, null, null)
                    return@withContext TrimResult.Error("Cannot write trimmed audio to storage")
                }
            } catch (e: SecurityException) {
                context.contentResolver.delete(newUri, null, null)
                return@withContext TrimResult.PermissionDenied
            } catch (e: IOException) {
                context.contentResolver.delete(newUri, null, null)
                return@withContext TrimResult.Error("IO error writing output: ${e.message}")
            }

            // ── 6. Finalize pending (Android 10+) ─────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    newUri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null, null
                )
            }

            // ── 7. Media scan so other apps see the new file immediately ───────
            val savedPath = getFilePath(newUri)
            if (savedPath != null) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(savedPath),
                    arrayOf(finalMimeType),
                    null
                )
            }

            Log.i(TAG, "[TRIM] Success — ${durationMs}ms clip saved to $newUri")
            TrimResult.Success

        } catch (e: SecurityException) {
            Log.e(TAG, "[TRIM] SecurityException", e)
            TrimResult.PermissionDenied
        } catch (e: IOException) {
            Log.e(TAG, "[TRIM] IOException", e)
            TrimResult.Error(e.message ?: "IO error during trim")
        } catch (e: Exception) {
            Log.e(TAG, "[TRIM] Unexpected error", e)
            TrimResult.Error(e.message ?: "Unexpected error during trim")
        } finally {
            // Always clean up every temp file regardless of outcome.
            tempInput.delete()
            tempOutput.delete()
            tempFallback.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FFmpeg execution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes an FFmpeg command asynchronously and suspends until it completes.
     *
     * Uses [suspendCancellableCoroutine] so that if the calling coroutine is
     * cancelled (e.g. user presses Cancel in the UI), [FFmpegKit.cancel] is
     * immediately invoked on the running FFmpeg session — preventing orphaned
     * native processes from continuing in the background.
     *
     * Returns `true` if FFmpeg exited with return code 0 (success).
     */
    private suspend fun executeFFmpeg(command: String): Boolean =
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "[FFmpeg] $command")

            val session = FFmpegKit.executeAsync(
                command,
                { completedSession ->
                    // Completion callback — fires on FFmpegKit's internal thread.
                    if (cont.isActive) {
                        val success = ReturnCode.isSuccess(completedSession.returnCode)
                        if (!success) {
                            Log.e(
                                TAG,
                                "[FFmpeg] FAILED rc=${completedSession.returnCode} " +
                                        "— ${completedSession.allLogsAsString?.take(600)}"
                            )
                        }
                        cont.resume(success)
                    }
                },
                null, // log callback (null = use default FFmpegKit logger)
                null  // statistics callback (not needed for trim)
            )

            // When the coroutine is cancelled, propagate to FFmpeg immediately.
            cont.invokeOnCancellation {
                Log.d(TAG, "[FFmpeg] Cancelling session ${session.sessionId}")
                FFmpegKit.cancel(session.sessionId)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Format helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun getFilePath(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /**
     * Maps an audio MIME type to a file extension used for temp files and the
     * MediaStore DISPLAY_NAME.  Defaults to "m4a" for any unknown type so the
     * fallback re-encode path (which always outputs AAC/M4A) still works.
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
        else                                             -> "m4a"
    }

    private fun extensionToMimeType(ext: String): String = when (ext) {
        "mp3"  -> "audio/mpeg"
        "m4a"  -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg"  -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav"  -> "audio/wav"
        "wma"  -> "audio/x-ms-wma"
        else   -> "audio/mp4"
    }
}