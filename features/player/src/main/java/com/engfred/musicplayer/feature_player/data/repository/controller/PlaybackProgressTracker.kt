package com.engfred.musicplayer.feature_player.data.repository.controller

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Data class to hold the ID, current position, and total duration of the currently playing song.
 * Used internally for robust play event tracking.
 */
data class CurrentAudioFilePlaybackProgress(
    val mediaId: String? = null,
    val playbackPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L
)

class PlaybackProgressTracker(
    val mediaController: MutableStateFlow<MediaController?>,
    private val stateUpdater: PlaybackStateUpdater,
) {
    /**
     * A [MutableStateFlow] that tracks the playback progress of the currently playing audio file.
     * This is crucial for accurately capturing the played duration of a song even if it's skipped.
     */
    private val _currentAudioFilePlaybackProgress = MutableStateFlow(CurrentAudioFilePlaybackProgress())
    val currentAudioFilePlaybackProgress: StateFlow<CurrentAudioFilePlaybackProgress> = _currentAudioFilePlaybackProgress.asStateFlow()

    var playEventRecorder: PlayEventRecorder? = null

    private var lastMediaId: String? = null
    private var lastPlaybackPositionMs: Long = 0L

    /**
     * Updates the internal [_currentAudioFilePlaybackProgress] with the latest playback
     * information of the actively playing song.
     *
     * @param controller The [MediaController] instance.
     */
    fun updateCurrentAudioFilePlaybackProgress(controller: MediaController) {
        controller.currentMediaItem?.let { currentMediaItem ->
            _currentAudioFilePlaybackProgress.update {
                it.copy(
                    mediaId = currentMediaItem.mediaId,
                    playbackPositionMs = controller.currentPosition,
                    totalDurationMs = controller.duration.coerceAtLeast(0L)
                )
            }
        } ?: _currentAudioFilePlaybackProgress.update { CurrentAudioFilePlaybackProgress() }
    }

    /**
     * Starts a continuous loop to update the current playback position in the [_playbackState]
     * and [_currentAudioFilePlaybackProgress] at regular intervals (every 500ms).
     */
    suspend fun startPlaybackPositionUpdates() {
        while (true) {
            withContext(Dispatchers.Main) {
                mediaController.value?.let { actualController ->
                    // Only update if player is not idle or ended, to avoid unnecessary updates
                    if (actualController.playbackState != Player.STATE_IDLE && actualController.playbackState != Player.STATE_ENDED) {
                        stateUpdater.updatePlaybackState()
                        updateCurrentAudioFilePlaybackProgress(actualController) // Keep this updated frequently

                        val progress = _currentAudioFilePlaybackProgress.value
                        val currentId = progress.mediaId ?: return@let
                        if (currentId != lastMediaId) {
                            playEventRecorder?.resetRecordedFlag()
                            lastPlaybackPositionMs = progress.playbackPositionMs
                            lastMediaId = currentId
                        } else {
                            val total = progress.totalDurationMs
                            if (total > 0 && lastPlaybackPositionMs >= total - 5000 && progress.playbackPositionMs <= 5000) {
                                playEventRecorder?.resetRecordedFlag()
                            }
                            lastPlaybackPositionMs = progress.playbackPositionMs
                        }
                        if (actualController.isPlaying) {
                            playEventRecorder?.checkAndRecordIfThresholdMet(progress)
                        }
                    }
                }
            }
            delay(500) // Update every half second
        }
    }

    fun resetProgress() {
        _currentAudioFilePlaybackProgress.value = CurrentAudioFilePlaybackProgress()
        lastMediaId = null
        lastPlaybackPositionMs = 0L
        playEventRecorder?.resetRecordedFlag()
    }
}