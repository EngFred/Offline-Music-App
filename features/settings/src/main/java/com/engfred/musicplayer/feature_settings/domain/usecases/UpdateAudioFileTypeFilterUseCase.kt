package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import javax.inject.Inject

class UpdateAudioFileTypeFilterUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(filter: AudioFileTypeFilter) {
        settingsRepository.updateAudioFileTypeFilter(filter)
    }
}