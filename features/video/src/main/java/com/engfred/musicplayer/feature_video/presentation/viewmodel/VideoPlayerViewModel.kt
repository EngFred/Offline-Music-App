package com.engfred.musicplayer.feature_video.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.domain.ActiveMediaType
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry
import com.engfred.musicplayer.core.domain.cast.VideoCastManager
import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.domain.model.VideoFile
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.VideoPlaybackController
import com.engfred.musicplayer.core.domain.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VideoPlayerEvent {
    data object TogglePlayPause : VideoPlayerEvent
    data class SeekTo(val positionMs: Long) : VideoPlayerEvent
    data class SeekBy(val deltaMs: Long) : VideoPlayerEvent
    data class SetPlaybackSpeed(val speed: Float) : VideoPlayerEvent
    data object ToggleControls : VideoPlayerEvent
    data object ToggleLock : VideoPlayerEvent
    data object ToggleResizeMode : VideoPlayerEvent
    data class ShowSpeedDialog(val show: Boolean) : VideoPlayerEvent
    data class LoadVideo(val videoId: Long?, val uri: Uri?) : VideoPlayerEvent
}

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    val videoPlaybackController: VideoPlaybackController,
    private val musicPlaybackController: PlaybackController,
    private val videoCastManager: VideoCastManager,
    private val activePlayerRegistry: ActivePlayerRegistry,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoPlayerState())
    val uiState: StateFlow<VideoPlayerState> = _uiState.asStateFlow()

    private var autoHideControlsJob: Job? = null

    init {
        // Set active media to VIDEO and request normal audio player to pause
        activePlayerRegistry.setActiveMediaType(ActiveMediaType.VIDEO)
        activePlayerRegistry.requestPauseNormalPlayer()

        // Set cast state immediately so the UI reflects it from the first frame
        val initiallyConnected = videoCastManager.isConnected()
        _uiState.update { it.copy(
            isCastConnected = initiallyConnected,
            castState = if (initiallyConnected) CastState.CONNECTED else CastState.DISCONNECTED
        ) }

        observePlaybackState()
        observeCastState()
        observeCastVideoPlaybackState()

        val rawVideoId = savedStateHandle.get<Long>("videoId")
        val rawVideoUriStr = savedStateHandle.get<String>("videoUri")
        val videoUri = if (!rawVideoUriStr.isNullOrBlank()) Uri.parse(Uri.decode(rawVideoUriStr)) else null

        if (rawVideoId != null && rawVideoId != -1L) {
            loadVideoById(rawVideoId)
        } else if (videoUri != null) {
            loadVideoByUri(videoUri)
        }
    }

    fun onEvent(event: VideoPlayerEvent) {
        when (event) {
            is VideoPlayerEvent.TogglePlayPause -> {
                if (videoCastManager.isConnected()) {
                    val isEnded = _uiState.value.playbackState.isEnded
                    val currentVideo = _uiState.value.videoFile
                    if (isEnded && currentVideo != null) {
                        videoCastManager.loadVideo(currentVideo, 0L)
                    } else {
                        videoCastManager.togglePlayPause()
                    }
                } else {
                    val isEnded = _uiState.value.playbackState.isEnded
                    val currentVideo = _uiState.value.videoFile
                    if (isEnded && currentVideo != null) {
                        videoPlaybackController.prepare(currentVideo.uri, 0L, true)
                    } else {
                        videoPlaybackController.togglePlayPause()
                    }
                }
                resetControlsTimer()
            }
            is VideoPlayerEvent.SeekTo -> {
                if (videoCastManager.isConnected()) {
                    videoCastManager.seekTo(event.positionMs)
                } else {
                    videoPlaybackController.seekTo(event.positionMs)
                }
                resetControlsTimer()
            }
            is VideoPlayerEvent.SeekBy -> {
                val current = _uiState.value.playbackState.currentPositionMs
                val duration = _uiState.value.playbackState.totalDurationMs
                val target = (current + event.deltaMs).coerceIn(0L, duration)
                if (videoCastManager.isConnected()) {
                    videoCastManager.seekTo(target)
                } else {
                    videoPlaybackController.seekTo(target)
                }
                resetControlsTimer()
            }
            is VideoPlayerEvent.SetPlaybackSpeed -> {
                videoPlaybackController.setPlaybackSpeed(event.speed)
                _uiState.update { it.copy(playbackSpeed = event.speed, showSpeedDialog = false) }
                resetControlsTimer()
            }
            is VideoPlayerEvent.ToggleControls -> {
                if (_uiState.value.isLocked) {
                    _uiState.update { it.copy(areControlsVisible = !it.areControlsVisible) }
                    return
                }
                val newVisible = !_uiState.value.areControlsVisible
                _uiState.update { it.copy(areControlsVisible = newVisible) }
                if (newVisible) {
                    resetControlsTimer()
                } else {
                    autoHideControlsJob?.cancel()
                }
            }
            is VideoPlayerEvent.ToggleLock -> {
                _uiState.update { it.copy(isLocked = !it.isLocked) }
                resetControlsTimer()
            }
            is VideoPlayerEvent.ToggleResizeMode -> {
                val nextMode = when (_uiState.value.resizeMode) {
                    VideoResizeMode.FIT -> VideoResizeMode.FILL
                    VideoResizeMode.FILL -> VideoResizeMode.ZOOM
                    VideoResizeMode.ZOOM -> VideoResizeMode.FIT
                }
                _uiState.update { it.copy(resizeMode = nextMode) }
                resetControlsTimer()
            }
            is VideoPlayerEvent.ShowSpeedDialog -> {
                _uiState.update { it.copy(showSpeedDialog = event.show) }
            }
            is VideoPlayerEvent.LoadVideo -> {
                if (event.videoId != null && event.videoId != -1L) {
                    loadVideoById(event.videoId)
                } else if (event.uri != null) {
                    loadVideoByUri(event.uri)
                }
            }
        }
    }

    private fun loadVideoById(id: Long) {
        viewModelScope.launch {
            when (val res = videoRepository.getVideoById(id)) {
                is Resource.Success -> {
                    val video = res.data
                    if (video != null) {
                        _uiState.update { it.copy(videoFile = video) }
                        // If Cast is already playing THIS video, resume from its current position
                        // instead of reloading from 0 (which would annoyingly reset a 45-min movie)
                        val castState = videoCastManager.videoCastPlaybackState.value
                        val alreadyCasting = videoCastManager.isConnected()
                            && videoCastManager.isCurrentMediaVideo()
                            && videoCastManager.getCurrentVideo()?.id == video.id
                        if (alreadyCasting) {
                            // Just sync UI state from cast — don't reload
                            _uiState.update { state ->
                                state.copy(
                                    playbackState = state.playbackState.copy(
                                        isPlaying = castState.isPlaying,
                                        isLoading = castState.isBuffering,
                                        currentPositionMs = castState.currentPositionMs,
                                        totalDurationMs = if (castState.durationMs > 0) castState.durationMs else video.duration
                                    )
                                )
                            }
                        } else {
                            startVideoPlayback(video, 0L)
                        }
                        resetControlsTimer()
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(error = res.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    private fun loadVideoByUri(uri: Uri) {
        viewModelScope.launch {
            when (val res = videoRepository.getVideoFileByUri(uri)) {
                is Resource.Success -> {
                    val video = res.data
                    if (video != null) {
                        _uiState.update { it.copy(videoFile = video) }
                        val castState = videoCastManager.videoCastPlaybackState.value
                        val alreadyCasting = videoCastManager.isConnected()
                            && videoCastManager.isCurrentMediaVideo()
                            && videoCastManager.getCurrentVideo()?.id == video.id
                        if (alreadyCasting) {
                            _uiState.update { state ->
                                state.copy(
                                    playbackState = state.playbackState.copy(
                                        isPlaying = castState.isPlaying,
                                        isLoading = castState.isBuffering,
                                        currentPositionMs = castState.currentPositionMs,
                                        totalDurationMs = if (castState.durationMs > 0) castState.durationMs else video.duration
                                    )
                                )
                            }
                        } else {
                            startVideoPlayback(video, 0L)
                        }
                        resetControlsTimer()
                        return@launch
                    }
                }
                else -> {}
            }

            val fallbackVideo = VideoFile(
                id = uri.hashCode().toLong(),
                title = uri.lastPathSegment ?: "External Video",
                duration = 0L,
                uri = uri,
                thumbnailUri = null,
                mimeType = "video/*"
            )
            _uiState.update { it.copy(videoFile = fallbackVideo) }
            startVideoPlayback(fallbackVideo, 0L)
            resetControlsTimer()
        }
    }

    private fun startVideoPlayback(video: VideoFile, startPos: Long) {
        if (videoCastManager.isConnected()) {
            videoPlaybackController.pause()
            videoCastManager.loadVideo(video, startPos)
        } else {
            videoPlaybackController.prepare(video.uri, startPos, true)
        }
    }

    private fun observePlaybackState() {
        viewModelScope.launch {
            videoPlaybackController.playbackState.collect { pbState ->
                if (!videoCastManager.isConnected()) {
                    _uiState.update { it.copy(playbackState = pbState) }
                }
            }
        }
    }

    private fun observeCastState() {
        viewModelScope.launch {
            // Initialize to current state so we don't trigger the "just connected" branch
            // on the first emission (loadVideoById already handles initial playback)
            var previousCastConnected = videoCastManager.isConnected()
            videoCastManager.castStateFlow.collect { state ->
                val isConnected = state == CastState.CONNECTED
                _uiState.update {
                    it.copy(
                        isCastConnected = isConnected,
                        castState = state
                    )
                }

                // If Cast just connected while video is open, automatically switch playback to TV!
                if (isConnected && !previousCastConnected) {
                    val currentVideo = _uiState.value.videoFile
                    if (currentVideo != null) {
                        val currentPos = _uiState.value.playbackState.currentPositionMs
                        videoPlaybackController.pause()
                        videoCastManager.loadVideo(currentVideo, currentPos)
                    }
                }

                // If Cast disconnected while video is open, automatically resume on phone!
                if (!isConnected && previousCastConnected) {
                    val currentVideo = _uiState.value.videoFile
                    if (currentVideo != null) {
                        val currentPos = _uiState.value.playbackState.currentPositionMs
                        videoPlaybackController.prepare(currentVideo.uri, currentPos, true)
                    }
                }
                previousCastConnected = isConnected
            }
        }
    }

    private fun observeCastVideoPlaybackState() {
        viewModelScope.launch {
            videoCastManager.videoCastPlaybackState.collect { castPlayback ->
                if (videoCastManager.isConnected()) {
                    _uiState.update { current ->
                        current.copy(
                            playbackState = current.playbackState.copy(
                                isPlaying = castPlayback.isPlaying,
                                isLoading = castPlayback.isBuffering,
                                isEnded = castPlayback.isEnded,
                                currentPositionMs = castPlayback.currentPositionMs,
                                totalDurationMs = if (castPlayback.durationMs > 0) castPlayback.durationMs else current.playbackState.totalDurationMs
                            )
                        )
                    }
                }
            }
        }
    }

    private fun resetControlsTimer() {
        autoHideControlsJob?.cancel()
        autoHideControlsJob = viewModelScope.launch {
            delay(3500)
            if (_uiState.value.playbackState.isPlaying && !_uiState.value.isLocked) {
                _uiState.update { it.copy(areControlsVisible = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoHideControlsJob?.cancel()
        videoPlaybackController.release()
        if (!videoCastManager.isConnected() || !videoCastManager.isCurrentMediaVideo()) {
            activePlayerRegistry.setActiveMediaType(ActiveMediaType.NONE)
        }
    }
}
