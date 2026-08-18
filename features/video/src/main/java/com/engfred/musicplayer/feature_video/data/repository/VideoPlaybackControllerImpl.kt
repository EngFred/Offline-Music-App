package com.engfred.musicplayer.feature_video.data.repository

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.engfred.musicplayer.core.domain.model.VideoPlaybackState
import com.engfred.musicplayer.core.domain.repository.VideoPlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

class VideoPlaybackControllerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : VideoPlaybackController {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    private var exoPlayer: ExoPlayer? = null

    private val _playbackState = MutableStateFlow(VideoPlaybackState())
    override val playbackState: StateFlow<VideoPlaybackState> = _playbackState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    _playbackState.update { it.copy(isLoading = true, isEnded = false) }
                }
                Player.STATE_READY -> {
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isEnded = false,
                            totalDurationMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
                            bufferedPositionMs = exoPlayer?.bufferedPosition ?: 0L
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    _playbackState.update { it.copy(isPlaying = false, isEnded = true) }
                }
                Player.STATE_IDLE -> {
                    _playbackState.update { it.copy(isLoading = false) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.update {
                it.copy(
                    isPlaying = isPlaying,
                    currentPositionMs = exoPlayer?.currentPosition ?: 0L,
                    bufferedPositionMs = exoPlayer?.bufferedPosition ?: 0L
                )
            }
            if (isPlaying) {
                startProgressUpdates()
            } else {
                stopProgressUpdates()
            }
        }
    }

    fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)
                addListener(playerListener)
            }
        }
        return exoPlayer!!
    }

    override fun prepare(uri: Uri, startPositionMs: Long, playWhenReady: Boolean) {
        val player = getPlayer()
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.prepare()
        player.playWhenReady = playWhenReady
        _playbackState.update {
            it.copy(
                isLoading = true,
                currentPositionMs = startPositionMs,
                error = null,
                isEnded = false
            )
        }
    }

    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_playbackState.value.isEnded) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _playbackState.update { it.copy(currentPositionMs = positionMs) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    override fun release() {
        stopProgressUpdates()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        _playbackState.update { VideoPlaybackState() }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    _playbackState.update {
                        it.copy(
                            currentPositionMs = player.currentPosition,
                            bufferedPositionMs = player.bufferedPosition,
                            totalDurationMs = player.duration.coerceAtLeast(0L)
                        )
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }
}
