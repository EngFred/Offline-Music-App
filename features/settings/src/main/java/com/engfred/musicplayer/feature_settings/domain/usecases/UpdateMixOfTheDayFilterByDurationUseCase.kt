package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import javax.inject.Inject

class UpdateMixOfTheDayFilterByDurationUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) {
        settingsRepository.updateMixOfTheDayFilterByDuration(enabled)
    }
}