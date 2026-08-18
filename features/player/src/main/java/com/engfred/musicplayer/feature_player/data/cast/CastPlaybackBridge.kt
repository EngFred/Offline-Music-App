package com.engfred.musicplayer.feature_player.data.cast

import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Google Cast playback state and events back into the app's [PlaybackState] Flow.
 */
@Singleton
class CastPlaybackBridge @Inject constructor(
    private val castSessionManager: CastSessionManager,
    private val sharedAudioDataSource: SharedAudioDataSource
) {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressTrackingJob: Job? = null
    private var targetPlaybackState: MutableStateFlow<PlaybackState>? = null

    init {
        castSessionManager.onRemoteStatusChanged = { client ->
            updateFromRemoteStatus(client)
        }

        bridgeScope.launch {
            castSessionManager.castState.collectLatest { state ->
                targetPlaybackState?.update { it.copy(castState = state) }
                if (state == CastState.CONNECTED) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                }
            }
        }
    }

    /**
     * Attaches the app's central [playbackState] StateFlow to be updated during cast sessions.
     */
    fun attachPlaybackState(playbackState: MutableStateFlow<PlaybackState>) {
        targetPlaybackState = playbackState
        playbackState.update { it.copy(castState = castSessionManager.castState.value) }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressTrackingJob = bridgeScope.launch {
            while (isActive) {
                if (castSessionManager.isConnected()) {
                    val pos = castSessionManager.getRemotePositionMs()
                    val dur = castSessionManager.getRemoteDurationMs()
                    val playing = castSessionManager.isRemotePlaying()

                    targetPlaybackState?.update { current ->
                        current.copy(
                            playbackPositionMs = pos,
                            totalDurationMs = if (dur > 0) dur else current.totalDurationMs,
                            isPlaying = playing,
                            castState = CastState.CONNECTED
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = null
    }

    private fun updateFromRemoteStatus(client: RemoteMediaClient) {
        val mediaStatus = client.mediaStatus ?: return
        val currentMediaInfo = mediaStatus.mediaInfo
        val queue = sharedAudioDataSource.playingQueueAudioFiles.value

        val currentAudioFile: AudioFile? = currentMediaInfo?.let { info ->
            val title = info.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
            val artist = info.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST)
            queue.find { it.title == title && (artist == null || it.artist == artist) }
                ?: targetPlaybackState?.value?.currentAudioFile
        }

        val isPlaying = mediaStatus.playerState == MediaStatus.PLAYER_STATE_PLAYING
        val isLoading = mediaStatus.playerState == MediaStatus.PLAYER_STATE_BUFFERING
        val duration = currentMediaInfo?.streamDuration?.coerceAtLeast(0L) ?: 0L
        val position = client.approximateStreamPosition.coerceAtLeast(0L)

        val currentItemIndex = mediaStatus.currentItemId.let { itemId ->
            val queueItems = mediaStatus.queueItems ?: emptyList()
            queueItems.indexOfFirst { it.itemId == itemId }.coerceAtLeast(0)
        }

        targetPlaybackState?.update { current ->
            current.copy(
                currentAudioFile = currentAudioFile ?: current.currentAudioFile,
                isPlaying = isPlaying,
                isLoading = isLoading,
                totalDurationMs = if (duration > 0) duration else current.totalDurationMs,
                playbackPositionMs = position,
                playingSongIndex = currentItemIndex,
                castState = CastState.CONNECTED
            )
        }
    }
}
