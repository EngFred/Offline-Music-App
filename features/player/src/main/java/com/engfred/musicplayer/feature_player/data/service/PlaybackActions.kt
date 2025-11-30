package com.engfred.musicplayer.feature_player.data.service

import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "PlaybackActions"

/**
 * Extracted widget-play/pause and repeat toggle logic.
 * These are pure helpers that match the original behavior exactly.
 */
object PlaybackActions {

    fun handleRepeatToggle(exoPlayer: ExoPlayer, settingsRepository: SettingsRepository, scope: CoroutineScope) {
        val current = exoPlayer.repeatMode
        val next = when (current) {
            Player.REPEAT_MODE_OFF -> {
                scope.launch { settingsRepository.updateRepeatMode(RepeatMode.ALL) }
                Player.REPEAT_MODE_ALL
            }
            Player.REPEAT_MODE_ALL -> {
                scope.launch { settingsRepository.updateRepeatMode(RepeatMode.ONE) }
                Player.REPEAT_MODE_ONE
            }
            Player.REPEAT_MODE_ONE -> {
                scope.launch { settingsRepository.updateRepeatMode(RepeatMode.OFF) }
                Player.REPEAT_MODE_OFF
            }
            else -> Player.REPEAT_MODE_OFF
        }
        exoPlayer.repeatMode = next
    }
}