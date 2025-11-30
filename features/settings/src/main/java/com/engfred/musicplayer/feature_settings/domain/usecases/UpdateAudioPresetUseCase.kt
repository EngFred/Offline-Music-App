package com.engfred.musicplayer.feature_settings.domain.usecases

import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import javax.inject.Inject

class UpdateAudioPresetUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(preset: AudioPreset) {
        settingsRepository.updateAudioPreset(preset)
    }
}