package com.engfred.musicplayer.core.domain.cast

import com.engfred.musicplayer.core.domain.model.VideoFile
import kotlinx.coroutines.flow.StateFlow

data class VideoCastPlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * Interface allowing video feature and app shell to command Google Cast without depending on :features:player.
 */
interface VideoCastManager {
    val videoCastPlaybackState: StateFlow<VideoCastPlaybackState>
    val currentVideoFile: StateFlow<VideoFile?>
    fun getCurrentVideo(): VideoFile?
    fun isConnected(): Boolean
    fun isCurrentMediaVideo(): Boolean
    fun loadVideo(videoFile: VideoFile, startPositionMs: Long = 0L)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
}
