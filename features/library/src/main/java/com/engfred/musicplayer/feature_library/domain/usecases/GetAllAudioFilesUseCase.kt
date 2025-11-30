package com.engfred.musicplayer.feature_library.domain.usecases

import android.util.Log
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/**
 * Use case class to fetch all audio files from the [LibraryRepository].
 * Wraps the result in a [Resource] for better UI handling (loading, success, error).
 */
class GetAllAudioFilesUseCase @Inject constructor(
    private val repository: LibraryRepository
) {
    private val TAG = "GetAllAudioFilesUseCase"

    operator fun invoke(): Flow<Resource<List<AudioFile>>> {
        return repository.getAllAudioFiles()
            .map { audioFiles ->
                if (audioFiles.isEmpty()) {
                    Resource.Error("No audio files found on device.")
                } else {
                    Resource.Success(audioFiles)
                }
            }
            .onStart {
                emit(Resource.Loading())
            }
            .catch { e ->
                Log.e(TAG, "Error loading audio files", e)
                emit(Resource.Error("Could not load audio files: ${e.localizedMessage ?: "Unknown error"}"))
            }
    }
}
