package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.model.AppSettings
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case to get the current application settings.
 */
class GetAppSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> {
        return settingsRepository.getAppSettings()
    }
}