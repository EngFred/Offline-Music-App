package com.engfred.musicplayer.feature_video.data.model

import android.net.Uri

/**
 * Data Transfer Object for raw video rows returned from Android MediaStore.
 */
data class VideoFileDto(
    val id: Long,
    val title: String,
    val duration: Long,
    val uri: Uri,
    val thumbnailUri: Uri? = null,
    val resolution: String? = null,
    val size: Long? = null,
    val dateAdded: Long = 0L,
    val dateModified: Long = 0L,
    val mimeType: String = "video/*",
    val dataPath: String? = null,
    val bucketDisplayName: String? = null
)
