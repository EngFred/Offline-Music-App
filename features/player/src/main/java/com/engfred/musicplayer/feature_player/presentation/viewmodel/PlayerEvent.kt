package com.engfred.musicplayer.feature_player.presentation.viewmodel


import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.domain.model.PlayerLayout

/**
 * Sealed class representing all possible events that can occur on the Player Screen.
 */
sealed class PlayerEvent {
    data class PlayAudioFile(val audioFile: AudioFile) : PlayerEvent()
    data object PlayPause : PlayerEvent()
    data object SkipToNext : PlayerEvent()
    data object SkipToPrevious : PlayerEvent()
    data class SeekTo(val positionMs: Long) : PlayerEvent()
    data class SetRepeatMode(val mode: RepeatMode) : PlayerEvent()
    data class SetShuffleMode(val mode: ShuffleMode) : PlayerEvent()
    data object ReleasePlayer : PlayerEvent()
    data class AddToFavorites(val audioFile: AudioFile) : PlayerEvent()
    data class RemoveFromFavorites(val audioFileId: Long) : PlayerEvent()
    data class SetSeeking(val seeking: Boolean) : PlayerEvent()
    data class SelectPlayerLayout(val layout: PlayerLayout) : PlayerEvent()
    data class RemovedFromQueue(val audioFile: AudioFile) : PlayerEvent()
}