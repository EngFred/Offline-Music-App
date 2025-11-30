package com.engfred.musicplayer.feature_audio_trim.domain.repository

import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_audio_trim.domain.model.TrimResult
import kotlinx.coroutines.flow.Flow

interface TrimRepository {
    suspend fun trimAudio(
        audioFile: AudioFile,
        startMs: Long,
        endMs: Long
    ): Flow<TrimResult>
}