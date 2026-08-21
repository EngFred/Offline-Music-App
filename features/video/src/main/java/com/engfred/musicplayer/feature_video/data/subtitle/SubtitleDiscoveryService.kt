package com.engfred.musicplayer.feature_video.data.subtitle

import android.content.Context
import android.net.Uri
import com.engfred.musicplayer.core.domain.model.SubtitleFormat
import com.engfred.musicplayer.core.domain.model.SubtitleTrack
import com.engfred.musicplayer.core.domain.model.SubtitleType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for discovering external subtitle files that match video files.
 * Implements the common UX pattern of auto-matching subtitle files by filename.
 */
@Singleton
class SubtitleDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * Finds external subtitle files that match the given video file.
     * Searches for files with the same base name but different subtitle extensions.
     * 
     * @param videoUri The URI of the video file
     * @return List of discovered subtitle tracks
     */
    suspend fun findMatchingSubtitles(videoUri: Uri): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val videoPath = getFilePathFromUri(videoUri) ?: return@withContext emptyList()
        val videoFile = File(videoPath)
        
        if (!videoFile.exists()) {
            return@withContext emptyList()
        }
        
        val videoBaseName = videoFile.nameWithoutExtension
        val videoDirectory = videoFile.parentFile ?: return@withContext emptyList()
        
        val subtitleTracks = mutableListOf<SubtitleTrack>()
        
        // Search for subtitle files with matching base name
        videoDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.nameWithoutExtension.equals(videoBaseName, ignoreCase = true)) {
                val format = SubtitleFormat.fromExtension(file.extension)
                if (format != null) {
                    val track = SubtitleTrack(
                        id = file.absolutePath,
                        label = format.name,
                        language = null, // Could extract from filename (e.g., video.en.srt)
                        type = SubtitleType.EXTERNAL,
                        mimeType = format.mimeType,
                        uri = file.absolutePath,
                        isSelected = false
                    )
                    subtitleTracks.add(track)
                }
            }
        }
        
        subtitleTracks
    }
    
    /**
     * Extracts file path from a content URI or file URI.
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                // For content URIs, we need to query the content resolver
                try {
                    val projection = arrayOf("_data")
                    val cursor = context.contentResolver.query(uri, projection, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val index = it.getColumnIndex("_data")
                            if (index >= 0) {
                                it.getString(index)
                            } else {
                                null
                            }
                        } else null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }
    
    /**
     * Enhanced subtitle discovery that can extract language from filename patterns.
     * Common patterns: video.en.srt, video.es.srt, video.spanish.srt, etc.
     */
    suspend fun findMatchingSubtitlesWithLanguage(videoUri: Uri): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val baseTracks = findMatchingSubtitles(videoUri)
        
        // Try to extract language from filename
        baseTracks.map { track ->
            val fileName = File(track.uri ?: "").nameWithoutExtension
            val language = extractLanguageFromFilename(fileName)
            track.copy(
                label = if (language != null) "${track.label} ($language)" else track.label,
                language = language
            )
        }
    }
    
    private fun extractLanguageFromFilename(fileName: String): String? {
        // Common language codes and names
        val languagePatterns = mapOf(
            "en" to "English",
            "es" to "Spanish", 
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "english" to "English",
            "spanish" to "Spanish",
            "french" to "French",
            "german" to "German"
        )
        
        // Extract the last part before the extension (e.g., "movie.en" -> "en")
        val parts = fileName.split(".")
        if (parts.size >= 2) {
            val lastPart = parts.last().lowercase()
            return languagePatterns[lastPart]
        }
        
        return null
    }
}