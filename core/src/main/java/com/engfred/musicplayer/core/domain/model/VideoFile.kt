package com.engfred.musicplayer.core.domain.model

import android.net.Uri

/**
 * Represents a single video file in the domain layer.
 * Pure Kotlin data class, decoupled from Android ContentResolver/MediaStore specifics.
 */
data class VideoFile(
    val id: Long,
    val title: String,
    val duration: Long,
    val uri: Uri,
    val thumbnailUri: Uri? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val dateAdded: Long = 0L,
    val dateModified: Long = 0L,
    val mimeType: String = "video/*",
    val folderName: String? = null,
    val folderPath: String? = null
)
