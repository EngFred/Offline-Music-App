package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import javax.inject.Inject

/**
 * Use case to update the application's player layout setting.
 */
class UpdatePlayerLayoutUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(layout: PlayerLayout) {
        settingsRepository.updatePlayerLayout(layout)
    }
}