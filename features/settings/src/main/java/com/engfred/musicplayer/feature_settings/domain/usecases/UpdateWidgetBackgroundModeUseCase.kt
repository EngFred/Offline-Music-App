package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import javax.inject.Inject

class UpdateWidgetBackgroundModeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(mode: WidgetBackgroundMode) {
        settingsRepository.updateWidgetBackgroundMode(mode)
    }
}
