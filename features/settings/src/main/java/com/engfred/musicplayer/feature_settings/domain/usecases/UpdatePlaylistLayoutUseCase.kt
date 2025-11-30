package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import javax.inject.Inject

/**
 * Use case to update the application's playlist layout setting.
 */
class UpdatePlaylistLayoutUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(layout: PlaylistLayoutType) {
        settingsRepository.updatePlaylistLayout(layout)
    }
}