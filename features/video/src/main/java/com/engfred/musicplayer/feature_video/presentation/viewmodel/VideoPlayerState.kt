package com.engfred.musicplayer.feature_video.presentation.viewmodel

import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.domain.model.VideoFile
import com.engfred.musicplayer.core.domain.model.VideoPlaybackState

enum class VideoResizeMode {
    FIT,
    FILL,
    ZOOM
}

data class VideoPlayerState(
    val videoFile: VideoFile? = null,
    val playbackState: VideoPlaybackState = VideoPlaybackState(),
    val areControlsVisible: Boolean = true,
    val isLocked: Boolean = false,
    val resizeMode: VideoResizeMode = VideoResizeMode.FIT,
    val playbackSpeed: Float = 1.0f,
    val showSpeedDialog: Boolean = false,
    val isCastConnected: Boolean = false,
    val castDeviceName: String? = null,
    val castState: CastState = CastState.DISCONNECTED,
    val error: String? = null,
    val relatedVideos: List<VideoFile> = emptyList(),
    val isFullscreen: Boolean = false,
    val resumeMessage: String? = null
)
