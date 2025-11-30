package com.engfred.musicplayer.feature_player.data.repository.controller

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.mapper.AudioFileMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class PlaybackStateUpdater(
    private val playbackState: MutableStateFlow<PlaybackState>,
    private val mediaController: MutableStateFlow<MediaController?>,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val audioFileMapper: AudioFileMapper,
) {
    /**
     * Updates the internal [_playbackState] based on the current state of the [MediaController].
     * This method is called periodically and upon significant player events.
     */
    fun updatePlaybackState() {
        mediaController.value?.let { controller ->
            val currentMediaItem = controller.currentMediaItem
            val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value

            // Attempt to find the AudioFile in the shared queue using its ID or URI.
            // If not found (e.g., temporary item), map directly from MediaItem.
            val currentAudioFile = currentMediaItem?.let { mediaItem ->
                val mediaUri = mediaItem.localConfiguration?.uri
                val mediaId = mediaItem.mediaId

                playingQueue.find { it.id.toString() == mediaId || it.uri == mediaUri }
                    ?: audioFileMapper.mapMediaItemToAudioFile(mediaItem)
            }

            playbackState.update {
                it.copy(
                    currentAudioFile = currentAudioFile,
                    isPlaying = controller.isPlaying,
                    playbackPositionMs = controller.currentPosition,
                    totalDurationMs = controller.duration.coerceAtLeast(0L), // Ensure non-negative duration
                    bufferedPositionMs = controller.bufferedPosition,
                    repeatMode = when (controller.repeatMode) {
                        Player.REPEAT_MODE_OFF -> RepeatMode.OFF
                        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                        else -> RepeatMode.OFF // Default to OFF if unknown
                    },
                    shuffleMode = if (controller.shuffleModeEnabled) ShuffleMode.ON else ShuffleMode.OFF,
                    playbackSpeed = controller.playbackParameters.speed,
                    isLoading = controller.playbackState == Player.STATE_BUFFERING,
                    playingQueue = playingQueue,
                    playingSongIndex = controller.currentMediaItemIndex
                )
            }
        }
    }
}