package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetAudioFileTypeFilterUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AudioFileTypeFilter> = settingsRepository.getAudioFileTypeFilter()
}