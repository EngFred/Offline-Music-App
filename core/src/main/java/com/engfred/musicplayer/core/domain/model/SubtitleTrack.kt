package com.engfred.musicplayer.core.domain.model

/**
 * Represents a subtitle track available for a video.
 * Can be either embedded in the video file or an external file.
 */
data class SubtitleTrack(
    val id: String,
    val label: String,
    val language: String? = null,
    val type: SubtitleType,
    val mimeType: String? = null,
    val uri: String? = null, // For external subtitles
    val isSelected: Boolean = false
)

/**
 * Type of subtitle track
 */
enum class SubtitleType {
    EMBEDDED,   // Subtitles embedded in the video file (e.g., MKV tracks)
    EXTERNAL    // External subtitle files (e.g., .srt, .vtt)
}

/**
 * Supported subtitle file formats
 */
enum class SubtitleFormat(val mimeType: String, val extension: String) {
    SRT("application/x-subrip", ".srt"),
    VTT("text/vtt", ".vtt"),
    TTML("application/ttml+xml", ".ttml"),
    SSA("text/x-ssa", ".ssa"),
    ASS("text/x-ssa", ".ass");

    companion object {
        fun fromExtension(extension: String): SubtitleFormat? {
            return entries.find { it.extension.equals(extension, ignoreCase = true) }
        }

        fun fromMimeType(mimeType: String): SubtitleFormat? {
            return entries.find { it.mimeType == mimeType }
        }
    }
}