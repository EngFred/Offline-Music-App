package com.engfred.musicplayer.feature_trim.domain.usecase

import android.content.Context
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_trim.domain.model.TrimResult
import com.engfred.musicplayer.feature_trim.domain.repository.TrimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TrimAudioUseCase @Inject constructor(
    private val trimRepository: TrimRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun execute(
        audioFile: AudioFile,
        startMs: Long,
        endMs: Long
    ): Flow<TrimResult> = withContext(Dispatchers.IO) {
        trimRepository.trimAudio(audioFile, startMs, endMs)
    }
}