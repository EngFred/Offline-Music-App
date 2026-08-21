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

private const val MIN_RESUMABLE_VIDEO_DURATION_MS = 60_000L
private const val MIN_RESUME_POSITION_MS = 5_000L
private const val MAX_RESUME_FRACTION = 0.95

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
    data class SelectVideo(val video: VideoFile) : VideoPlayerEvent
    data class SetFullscreen(val isFullscreen: Boolean) : VideoPlayerEvent
    data object ClearResumeMessage : VideoPlayerEvent
}

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val settingsRepository: com.engfred.musicplayer.core.domain.repository.SettingsRepository,
    val videoPlaybackController: VideoPlaybackController,
    private val musicPlaybackController: PlaybackController,
    private val videoCastManager: VideoCastManager,
    private val activePlayerRegistry: ActivePlayerRegistry,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoPlayerState())
    val uiState: StateFlow<VideoPlayerState> = _uiState.asStateFlow()

    private var autoHideControlsJob: Job? = null
    private var progressSavingJob: Job? = null

    init {
        // Set active media to VIDEO and request normal audio player to pause
        activePlayerRegistry.setActiveMediaType(ActiveMediaType.VIDEO)
        activePlayerRegistry.requestPauseNormalPlayer()

        // Set cast state immediately so the UI reflects it from the first frame
        val initiallyConnected = videoCastManager.isConnected()
        _uiState.update { it.copy(
            isCastConnected = initiallyConnected,
            castDeviceName = videoCastManager.connectedDeviceName.value,
            castState = if (initiallyConnected) CastState.CONNECTED else CastState.DISCONNECTED
        ) }

        observePlaybackState()
        observeCastState()
        observeCastVideoPlaybackState()
        observeDeviceVideos()
        startPeriodicProgressSaving()

        val rawVideoId = savedStateHandle.get<Long>("videoId")
        val rawVideoUriStr = savedStateHandle.get<String>("videoUri")
        val rawVideoMimeType = savedStateHandle.get<String>("videoMimeType")
        val videoUri = if (!rawVideoUriStr.isNullOrBlank()) Uri.parse(Uri.decode(rawVideoUriStr)) else null

        if (rawVideoId != null && rawVideoId != -1L) {
            loadVideoById(rawVideoId)
        } else if (videoUri != null) {
            loadVideoByUri(videoUri, rawVideoMimeType)
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
            is VideoPlayerEvent.SelectVideo -> {
                saveCurrentPosition()
                _uiState.update { current ->
                    current.copy(
                        videoFile = event.video,
                        relatedVideos = current.relatedVideos.filter { it.id != event.video.id }
                    )
                }
                viewModelScope.launch {
                    val savedPos = settingsRepository.getVideoPlaybackPosition(event.video.id)
                    val resumePos = resolveResumePosition(event.video, savedPos)
                    if (resumePos > 0) {
                        _uiState.update { it.copy(resumeMessage = "Resuming from ${formatTime(resumePos)}...") }
                    }
                    startVideoPlayback(event.video, resumePos)
                    resetControlsTimer()
                }
            }
            is VideoPlayerEvent.SetFullscreen -> {
                _uiState.update { it.copy(isFullscreen = event.isFullscreen) }
            }
            is VideoPlayerEvent.ClearResumeMessage -> {
                _uiState.update { it.copy(resumeMessage = null) }
            }
        }
    }

    private fun observeDeviceVideos() {
        viewModelScope.launch {
            videoRepository.getAllVideoFiles().collect { videos ->
                val currentId = _uiState.value.videoFile?.id
                val filtered = videos.filter { it.id != currentId }
                _uiState.update { it.copy(relatedVideos = filtered) }
            }
        }
    }

    private fun startPeriodicProgressSaving() {
        progressSavingJob?.cancel()
        progressSavingJob = viewModelScope.launch {
            while (true) {
                delay(4000)
                saveCurrentPosition()
            }
        }
    }

    private fun saveCurrentPosition() {
        val video = _uiState.value.videoFile ?: return
        if (video.duration in 1 until MIN_RESUMABLE_VIDEO_DURATION_MS) return
        val pos = _uiState.value.playbackState.currentPositionMs
        if (pos > 3000L) {
            viewModelScope.launch {
                settingsRepository.saveVideoPlaybackPosition(video.id, pos)
            }
        }
    }

    private fun resolveResumePosition(video: VideoFile, savedPositionMs: Long): Long {
        if (video.duration in 1 until MIN_RESUMABLE_VIDEO_DURATION_MS) return 0L
        val hasUsefulPosition = savedPositionMs > MIN_RESUME_POSITION_MS
        val isBeforeEnding = video.duration <= 0 ||
            savedPositionMs < (video.duration * MAX_RESUME_FRACTION).toLong()
        return if (hasUsefulPosition && isBeforeEnding) savedPositionMs else 0L
    }

    private fun loadVideoById(id: Long) {
        viewModelScope.launch {
            when (val res = videoRepository.getVideoById(id)) {
                is Resource.Success -> {
                    val video = res.data
                    if (video != null) {
                        _uiState.update { current ->
                            current.copy(
                                videoFile = video,
                                relatedVideos = current.relatedVideos.filter { it.id != video.id }
                            )
                        }
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
                            val savedPos = settingsRepository.getVideoPlaybackPosition(video.id)
                            val resumePos = resolveResumePosition(video, savedPos)
                            if (resumePos > 0) {
                                _uiState.update { it.copy(resumeMessage = "Resuming from ${formatTime(resumePos)}...") }
                            }
                            startVideoPlayback(video, resumePos)
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

    private fun loadVideoByUri(uri: Uri, sharedMimeType: String? = null) {
        viewModelScope.launch {
            when (val res = videoRepository.getVideoFileByUri(uri)) {
                is Resource.Success -> {
                    val video = res.data
                    if (video != null) {
                        _uiState.update { current ->
                            current.copy(
                                videoFile = video,
                                relatedVideos = current.relatedVideos.filter { it.id != video.id }
                            )
                        }
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
                            val savedPos = settingsRepository.getVideoPlaybackPosition(video.id)
                            val resumePos = resolveResumePosition(video, savedPos)
                            if (resumePos > 0) {
                                _uiState.update { it.copy(resumeMessage = "Resuming from ${formatTime(resumePos)}...") }
                            }
                            startVideoPlayback(video, resumePos)
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
                mimeType = sharedMimeType?.takeIf { it.isNotBlank() } ?: inferVideoMimeType(uri)
            )
            _uiState.update { current ->
                current.copy(
                    videoFile = fallbackVideo,
                    relatedVideos = current.relatedVideos.filter { it.id != fallbackVideo.id }
                )
            }
            startVideoPlayback(fallbackVideo, 0L)
            resetControlsTimer()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun startVideoPlayback(video: VideoFile, startPos: Long) {
        if (videoCastManager.isConnected()) {
            videoPlaybackController.pause()
            videoCastManager.loadVideo(video, startPos)
        } else if (videoPlaybackController.currentMediaUri != video.uri) {
            // Keep the existing player through configuration changes. Re-preparing
            // the same source would restart it and can surface an older player.
            videoPlaybackController.prepare(video.uri, startPos, true)
        }
    }

    private fun inferVideoMimeType(uri: Uri): String = when {
        uri.toString().contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
        uri.toString().contains(".mpd", ignoreCase = true) -> "application/dash+xml"
        uri.toString().contains(".webm", ignoreCase = true) -> "video/webm"
        else -> "video/mp4"
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
                        castDeviceName = videoCastManager.connectedDeviceName.value,
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
        progressSavingJob?.cancel()
        saveCurrentPosition()
        videoPlaybackController.release()
        if (!videoCastManager.isConnected() || !videoCastManager.isCurrentMediaVideo()) {
            activePlayerRegistry.setActiveMediaType(ActiveMediaType.NONE)
        }
    }
}
