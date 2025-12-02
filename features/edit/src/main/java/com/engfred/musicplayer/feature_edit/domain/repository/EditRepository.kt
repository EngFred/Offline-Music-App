package com.engfred.musicplayer.feature_edit.domain.repository

import android.content.Context
import com.engfred.musicplayer.core.common.Resource

interface EditRepository {
    suspend fun editAudioMetadata(
        id: Long,
        newTitle: String?,
        newArtist: String?,
        newAlbumArt: ByteArray?,
        context: Context
    ): Resource<Unit>
}