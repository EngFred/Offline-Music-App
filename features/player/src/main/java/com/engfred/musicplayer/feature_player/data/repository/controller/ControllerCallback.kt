package com.engfred.musicplayer.feature_player.data.repository.controller

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import kotlinx.coroutines.flow.update
import androidx.media3.common.MediaItem
import kotlin.math.min

private const val TAG = "PlayerControllerImpl"

interface PlayEventRecorder {
    fun resetRecordedFlag()
    fun checkAndRecordIfThresholdMet(progress: CurrentAudioFilePlaybackProgress)
}

@UnstableApi
class ControllerCallback(
    private val repositoryScope: CoroutineScope,
    private val playlistRepository: PlaylistRepository,
    private val stateUpdater: PlaybackStateUpdater,
    private val progressTracker: PlaybackProgressTracker,
    private val pendingPlayNextMediaId: MutableStateFlow<String?>,
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val playbackState: MutableStateFlow<PlaybackState>
) : Player.Listener, PlayEventRecorder {

    private var lastPlaybackState = Player.STATE_IDLE
    private var lastEventProcessedTimestamp: Long = 0L
    private var hasRecordedForCurrentPlay: Boolean = false

    fun resetTracking() {
        lastPlaybackState = Player.STATE_IDLE
        lastEventProcessedTimestamp = 0L
        hasRecordedForCurrentPlay = false
        Log.d(TAG, "ControllerCallback event tracking variables reset.")
    }

    override fun resetRecordedFlag() {
        hasRecordedForCurrentPlay = false
    }

    override fun checkAndRecordIfThresholdMet(progress: CurrentAudioFilePlaybackProgress) {
        if (hasRecordedForCurrentPlay) return
        val playedDurationMs = progress.playbackPositionMs
        val totalDurationMs = progress.totalDurationMs
        if (playedDurationMs != C.TIME_UNSET) {
            val playedPercentage = playedDurationMs.toFloat() / totalDurationMs
            if (playedPercentage >= 0.5f) {
                val audioFileId = progress.mediaId?.toLongOrNull()
                if (audioFileId != null) {
                    repositoryScope.launch {
                        playlistRepository.recordSongPlayEvent(audioFileId)
                        Log.d(TAG, "Recorded play event for song ID: $audioFileId (Played: ${playedDurationMs / 1000}s / ${totalDurationMs / 1000}s)")
                    }
                    hasRecordedForCurrentPlay = true
                } else {
                    Log.e(TAG, "Could not convert mediaId to AudioFile ID: ${progress.mediaId}")
                }
            } else {
                Log.d(TAG, "Skipped recording play event for song ID: ${progress.mediaId}")
            }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val isSongTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        val isPlaybackEnded = events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED &&
                lastPlaybackState != Player.STATE_ENDED

        if (isSongTransition || isPlaybackEnded) {
            val now = System.currentTimeMillis()
            if (now - lastEventProcessedTimestamp < 100) {
                Log.d(TAG, "Skipping play event processing due to rapid succession of events.")
                stateUpdater.updatePlaybackState()
                progressTracker.updateCurrentAudioFilePlaybackProgress(player as MediaController)
                lastPlaybackState = player.playbackState
                return
            }
            lastEventProcessedTimestamp = now
            val currentMediaItemAfterEvent = player.currentMediaItem

            if (isSongTransition && currentMediaItemAfterEvent != null) {
                if (pendingPlayNextMediaId.value == currentMediaItemAfterEvent.mediaId) {
                    (player as MediaController).shuffleModeEnabled = true
                    pendingPlayNextMediaId.value = null
                    Log.d(TAG, "Restored shuffle mode after transitioning to play next item.")
                }
            }
        }

        stateUpdater.updatePlaybackState()
        progressTracker.updateCurrentAudioFilePlaybackProgress(player as MediaController)
        lastPlaybackState = player.playbackState
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_INTERNAL &&
            (oldPosition.mediaItemIndex == newPosition.mediaItemIndex)
        ) {
            val now = System.currentTimeMillis()
            if (now - lastEventProcessedTimestamp < 100) {
                Log.d(TAG, "Skipping play event processing for discontinuity due to rapid events.")
                return
            }
            lastEventProcessedTimestamp = now
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // If the song changed AUTOMATICALLY (ended naturally), and the flag is set:
        if (playbackState.value.stopAfterCurrent && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            Log.d(TAG, "StopAfterCurrent triggered. Pausing playback.")
            // Pause the player
            progressTracker.mediaController.value?.pause()
            // Reset the flag (usually a one-time action)
            playbackState.update { it.copy(stopAfterCurrent = false, isPlaying = false) }
        }
    }

    /**
     * Robust handling of playback errors:
     * - Remove failing media item from ExoPlayer queue
     * - Remove inaccessible audio from shared data source (background IO)
     * - Immediately attempt to play the next item in queue (or stop/clear if none left)
     */
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Playback error: ${error.message}", error)
        val controller = progressTracker.mediaController.value ?: run {
            Log.w(TAG, "MediaController not available in onPlayerError.")
            return
        }

        val currentIndex = controller.currentMediaItemIndex
        if (currentIndex != C.INDEX_UNSET && controller.mediaItemCount > 0) {
            try {
                val failedMediaItem = controller.getMediaItemAt(currentIndex)
                val failedMediaId = failedMediaItem.mediaId
                playbackState.update { it.copy(error = "Playback error on song: ${error.message}. Removed from queue.") }

                // Remove the failing media item from the player queue to avoid repeated errors
                controller.removeMediaItem(currentIndex)
                Log.d(TAG, "Removed failing media item (ID: $failedMediaId) from queue due to playback error.")

                // Remove the inaccessible file from shared data source off the main thread
                val failedId = failedMediaId.toLongOrNull()
                if (failedId != null) {
                    repositoryScope.launch(Dispatchers.IO) {
                        try {
                            val dummyAudioFile = AudioFile(
                                id = failedId,
                                title = "",
                                artist = "",
                                album = "",
                                duration = 0L,
                                uri = Uri.EMPTY,
                                albumArtUri = null,
                                dateAdded = System.currentTimeMillis(),
                                artistId = null
                            )
                            // Prefer deleteAudioFile if available (cleans all internal structures), fallback to remove from playing queue.
                            try {
                                sharedAudioDataSource.deleteAudioFile(dummyAudioFile)
                                Log.d(TAG, "Deleted inaccessible audio file (ID: $failedId) from shared data source.")
                            } catch (_: Throwable) {
                                // fallback
                                try {
                                    sharedAudioDataSource.removeAudioFileFromPlayingQueue(dummyAudioFile)
                                    Log.d(TAG, "Removed inaccessible audio file (ID: $failedId) from playing queue (fallback).")
                                } catch (inner: Throwable) {
                                    Log.w(TAG, "Failed to remove inaccessible audio file from data source: ${inner.message}")
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error while cleaning up inaccessible audio file from shared data source: ${e.message}")
                        }
                    }
                }

                // After removing the faulty item, decide next action: play next item if available
                if (controller.mediaItemCount == 0) {
                    // Nothing left to play
                    controller.stop()
                    controller.clearMediaItems()
                    playbackState.update { it.copy(currentAudioFile = null, isPlaying = false) }
                    progressTracker.resetProgress()
                    Log.d(TAG, "Queue empty after removal. Stopped and cleared player.")
                } else {
                    // Next index to play: after removal, indices shift so the next item sits at `currentIndex`
                    val nextIndex = min(currentIndex, controller.mediaItemCount - 1)
                    try {
                        // Ensure the player seeks to the next item and resumes playing
                        controller.seekToDefaultPosition(nextIndex)
                        controller.prepare() // ensure prepared before playback
                        controller.play()
                        Log.d(TAG, "Resumed playback at index $nextIndex after removing invalid item.")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to resume playback automatically after removal: ${e.message}", e)
                        // As a last resort, try to prepare and start at index 0
                        try {
                            if (controller.mediaItemCount > 0) {
                                controller.seekToDefaultPosition(0)
                                controller.prepare()
                                controller.play()
                                Log.d(TAG, "Fallback: started playback at index 0.")
                            }
                        } catch (inner: Throwable) {
                            Log.e(TAG, "Fallback resume failed: ${inner.message}", inner)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Exception while handling player error / removing item: ${e.message}", e)
                // If removing throws for any reason, try stopping playback to avoid a stuck player
                try {
                    controller.stop()
                } catch (stopEx: Throwable) {
                    Log.w(TAG, "Failed to stop controller after error: ${stopEx.message}")
                }
            }
        } else {
            // No current index set - just stop
            try {
                controller.stop()
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping controller in onPlayerError: ${e.message}")
            }
        }
        // Ensure UI state reflects the latest
        stateUpdater.updatePlaybackState()
    }
}