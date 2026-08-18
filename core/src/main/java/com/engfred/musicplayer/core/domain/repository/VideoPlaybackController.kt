package com.engfred.musicplayer.core.domain.repository

import android.net.Uri
import com.engfred.musicplayer.core.domain.model.VideoPlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for managing local video playback via ExoPlayer.
 */
interface VideoPlaybackController {
    val playbackState: StateFlow<VideoPlaybackState>
    fun prepare(uri: Uri, startPositionMs: Long = 0L, playWhenReady: Boolean = true)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}
