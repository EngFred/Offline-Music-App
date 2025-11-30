package com.engfred.musicplayer.feature_library.data.model

import android.net.Uri

/**
 * Data Transfer Object (DTO) for an audio file as retrieved directly from the Android MediaStore.
 * This class is specific to the data layer and may contain Android-specific types (like Uri).
 */
data class AudioFileDto(
    val id: Long,
    val title: String?,
    val artist: String?,
    val artistId: Long?,
    val album: String?,
    val duration: Long,
    val data: String?,
    val uri: Uri,
    val albumId: Long?,
    val albumArtUri: Uri?,
    val dateAdded: Long,
    val mimeType: String?, // NEW: For checking during edit
    val size: Long? = null
)