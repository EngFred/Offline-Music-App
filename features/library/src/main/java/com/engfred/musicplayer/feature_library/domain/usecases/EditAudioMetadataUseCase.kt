package com.engfred.musicplayer.feature_library.domain.usecases

import android.content.Context
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import java.lang.Exception
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EditAudioMetadataUseCase @Inject constructor(
    private val repository: LibraryRepository
) {
    suspend operator fun invoke(
        id: Long,
        title: String?,
        artist: String?,
        albumArt: ByteArray?,
        context: Context
    ): Resource<Unit> {
        return try {
            repository.editAudioMetadata(
                id = id,
                newTitle = title,
                newArtist = artist,
                newAlbumArt = albumArt,
                context = context
            )
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Unknown error")
        }
    }
}