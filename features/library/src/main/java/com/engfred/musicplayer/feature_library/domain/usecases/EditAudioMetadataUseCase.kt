package com.engfred.musicplayer.feature_library.domain.usecases

import android.app.RecoverableSecurityException
import android.content.Context
import android.os.Build
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
            // Checking if the exception is RecoverableSecurityException (Android 10/Q)
            // If so, rethrow it so the ViewModel can catch it and launch the IntentSender.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                throw e
            }

            // Also checking if it was wrapped in a RuntimeException or similar
            val cause = e.cause
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cause is RecoverableSecurityException) {
                throw cause
            }

            Resource.Error(e.message ?: "Unknown error")
        }
    }
}