package com.engfred.musicplayer.feature_player.data.repository.controller

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.mapper.AudioFileMapper
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.core.util.MediaUtils.isAudioFileAccessible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "PlayerControllerImpl"

class QueueManager(
    private val sharedAudioDataSource: SharedAudioDataSource,
    private val audioFileMapper: AudioFileMapper,
    private val permissionHandlerUseCase: PermissionHandlerUseCase,
    private val context: Context,
    private val mediaController: MutableStateFlow<MediaController?>,
    private val playbackState: MutableStateFlow<PlaybackState>,
    private val stateUpdater: PlaybackStateUpdater,
    private val progressTracker: PlaybackProgressTracker,
    private val setRepeatCallback: suspend (RepeatMode) -> Unit,
    private val pendingPlayNextMediaId: MutableStateFlow<String?>,
) {
    /**
     * Initiates playback of a given audio file within the current playing queue.
     * If the queue doesn't match the controller's queue, it will be set.
     *
     * @param initialAudioFileUri The [android.net.Uri] of the audio file to start playback from.
     * @param intendedRepeat the repeat mode intended to be applied after setting media items.
     * @param startPositionMs position (ms) to start playback from, or C.TIME_UNSET to use default.
     */
    suspend fun initiatePlayback(initialAudioFileUri: android.net.Uri, intendedRepeat: RepeatMode, startPositionMs: Long = C.TIME_UNSET) {
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller == null) {
                Log.e(TAG, "MediaController not available for playback initiation.")
                playbackState.update { it.copy(error = "Player not initialized.") }
                return@withContext
            }

            val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
            if (playingQueue.isEmpty()) {
                Log.w(TAG, "Shared audio files are empty. Cannot initiate playback.")
                playbackState.update { it.copy(error = "No audio files available to play.") }
                return@withContext
            }

            val audioFileToPlay = playingQueue.find { it.uri == initialAudioFileUri } ?: run {
                Log.w(TAG, "Initial audio file not found in current playing queue for URI: $initialAudioFileUri")
                playbackState.update { it.copy(error = "Selected song not found in library.") }
                return@withContext
            }

            val desiredMediaId = audioFileToPlay.id.toString()
            val startIndex = playingQueue.indexOf(audioFileToPlay)

            // Quick accessibility check for the requested initial audio file.
            val isAccessibleInitial = withContext(Dispatchers.IO) {
                isAudioFileAccessible(context, audioFileToPlay.uri, permissionHandlerUseCase)
            }
            if (!isAccessibleInitial) {
                Log.e(TAG, "Initial audio file is not accessible: ${audioFileToPlay.uri}. Aborting playback.")
                playbackState.update {
                    it.copy(
                        currentAudioFile = null,
                        isPlaying = false,
                        error = "Cannot play '${audioFileToPlay.title}'. File not found or storage permission denied."
                    )
                }
                return@withContext
            }

            // Check if controller's queue already matches the shared queue (by mediaId list)
            val currentMediaIds = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
            val sharedMediaIds = playingQueue.map { it.id.toString() }
            val currentMediaItemsMatchSharedSource = controller.mediaItemCount == playingQueue.size &&
                    currentMediaIds == sharedMediaIds

            Log.d(TAG, "Initiating playback. startIndex=$startIndex startPositionMs=$startPositionMs queueMatch=$currentMediaItemsMatchSharedSource")

            if (currentMediaItemsMatchSharedSource) {
                val currentMediaId = controller.currentMediaItem?.mediaId
                if (currentMediaId == desiredMediaId && controller.isPlaying && (startPositionMs == C.TIME_UNSET || startPositionMs <= 0)) {
                    Log.d(TAG, "Already playing desired song and no seek needed. No action.")
                    return@withContext
                }

                // Temporarily disable shuffle to seek deterministically
                val wasShuffle = controller.shuffleModeEnabled
                controller.shuffleModeEnabled = false

                try {
                    if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) {
                        // seek to index + position
                        controller.seekTo(startIndex, startPositionMs)
                        Log.d(TAG, "seekTo(index=$startIndex, pos=$startPositionMs) on existing queue")
                    } else {
                        controller.seekToDefaultPosition(startIndex)
                        Log.d(TAG, "seekToDefaultPosition(index=$startIndex) on existing queue")
                    }
                } finally {
                    controller.shuffleModeEnabled = wasShuffle
                }

                if (!controller.isPlaying) {
                    controller.play()
                }
                stateUpdater.updatePlaybackState()
                progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                Log.d(TAG, "Repositioned playback within existing queue to index $startIndex with pos=$startPositionMs.")
            } else {
                try {
                    val mediaItems = playingQueue.map { audioFileMapper.mapAudioFileToMediaItem(it) }
                    // When setting media items you can pass a start position to begin playback from that offset
                    val startPosToUse = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else C.TIME_UNSET
                    controller.setMediaItems(mediaItems, startIndex, startPosToUse)
                    Log.d(TAG, "setMediaItems called. startIndex=$startIndex startPos=$startPosToUse count=${mediaItems.size}")

                    // Re-apply stored repeat and shuffle modes
                    setRepeatCallback(intendedRepeat)

                    controller.prepare()
                    controller.play()
                    Log.d(TAG, "Initiated playback with new queue. StartIndex=$startIndex pos=$startPosToUse")
                    progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting media items or playing during initiation: ${e.message}", e)
                    playbackState.update { it.copy(error = "Playback error: ${e.message}") }
                }
            }
        }
    }

    /**
     * Adds an [AudioFile] to the player's queue right after the currently playing song.
     */
    suspend fun addAudioToQueueNext(audioFile: AudioFile) {
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller == null) {
                Log.e(TAG, "MediaController not available. Cannot add to queue.")
                playbackState.update { it.copy(error = "Player not initialized. Cannot add to queue.") }
                return@withContext
            }

            val isAccessible = isAudioFileAccessible(context, audioFile.uri, permissionHandlerUseCase)
            if (!isAccessible) {
                Log.e(TAG, "Audio file is not accessible for 'Play Next': ${audioFile.uri}. Aborting add.")
                playbackState.update { it.copy(error = "Cannot add '${audioFile.title}'. File not found or storage permission denied.") }
                return@withContext
            }

            val mediaItemToAdd = audioFileMapper.mapAudioFileToMediaItem(audioFile)
            val newItemMediaId = mediaItemToAdd.mediaId
            Log.d(TAG, "Attempting to 'Play Next': Title='${audioFile.title}', AudioFile.ID='${audioFile.id}', NewItemMediaId='$newItemMediaId'")

            val currentMediaId = controller.currentMediaItem?.mediaId

            val wasShuffle = controller.shuffleModeEnabled
            controller.shuffleModeEnabled = false

            val currentMediaItemIndex = controller.currentMediaItemIndex
            val insertIndex = if (currentMediaItemIndex == C.INDEX_UNSET || controller.mediaItemCount == 0) {
                0
            } else {
                currentMediaItemIndex + 1
            }

            try {
                controller.addMediaItem(insertIndex, mediaItemToAdd)
                if (wasShuffle) {
                    pendingPlayNextMediaId.value = newItemMediaId
                }

                Log.d(TAG, "Added ${audioFile.title} (ID: ${audioFile.id}) to queue at index $insertIndex (Play Next).")

                if (controller.mediaItemCount == 1 && !controller.isPlaying && controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                    controller.play()
                    Log.d(TAG, "Started playback as it was the first item in the queue.")
                    progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                }

                // Sync the shared playing queue to reflect the insertion/move
                val newQueue = sharedAudioDataSource.playingQueueAudioFiles.value.toMutableList()

                // Remove if already exists (to handle move case)
                val existingIndex = newQueue.indexOfFirst { it.id.toString() == newItemMediaId }
                if (existingIndex != -1) {
                    newQueue.removeAt(existingIndex)
                    Log.d(TAG, "Moved existing song '${audioFile.title}' from index $existingIndex to play next.")
                } else {
                    Log.d(TAG, "Added new song '${audioFile.title}' to play next.")
                }

                // Find insert position based on current media ID
                val currentSharedIndex = if (currentMediaId != null) {
                    newQueue.indexOfFirst { it.id.toString() == currentMediaId }
                } else {
                    -1
                }
                val insertPos = if (currentSharedIndex == -1) 0 else currentSharedIndex + 1
                newQueue.add(insertPos, audioFile)

                // Update the shared data source
                sharedAudioDataSource.setPlayingQueue(newQueue)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding media item to queue: ${e.message}", e)
                playbackState.update { it.copy(error = "Error adding song to queue: ${e.message}") }
            } finally {
                // Do not re-enable shuffle here — restored in callback if needed.
            }
        }
    }

    /**
     * Handles when an audio file is removed from storage.
     */
    suspend fun onAudioFileRemoved(deletedAudioFile: AudioFile) {
        Log.d(TAG, "onAudioFileRemoved: Attempting to remove '${deletedAudioFile.title}' (ID: ${deletedAudioFile.id}) from player queue.")

        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller == null) {
                Log.e(TAG, "MediaController not available. Cannot remove audio file from player queue.")
                return@withContext
            }

            val deletedMediaId = deletedAudioFile.id.toString()
            var removedIndex: Int? = null
            for (i in 0 until controller.mediaItemCount) {
                val mediaItem = controller.getMediaItemAt(i)
                if (mediaItem.mediaId == deletedMediaId) {
                    removedIndex = i
                    break
                }
            }

            if (removedIndex != null) {
                try {
                    val wasPlayingDeletedSong = (controller.currentMediaItemIndex == removedIndex) && controller.isPlaying

                    controller.removeMediaItem(removedIndex)

                    Log.d(TAG, "Successfully removed '${deletedAudioFile.title}' from ExoPlayer queue at index $removedIndex.")

                    if (controller.mediaItemCount == 0) {
                        controller.stop()
                        controller.clearMediaItems()
                        playbackState.update { it.copy(currentAudioFile = null, isPlaying = false) }
                        progressTracker.resetProgress()
                        Log.d(TAG, "ExoPlayer queue is empty after deletion. Stopping playback.")
                    } else if (wasPlayingDeletedSong) {
                        stateUpdater.updatePlaybackState()
                        progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                        Log.d(TAG, "Currently playing song deleted. Player automatically transitioned.")
                    } else {
                        stateUpdater.updatePlaybackState()
                        progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing media item from ExoPlayer: ${e.message}", e)
                    playbackState.update { it.copy(error = "Error removing deleted song from player: ${e.message}") }
                }
            } else {
                Log.d(TAG, "Deleted audio file '${deletedAudioFile.title}' (ID: ${deletedAudioFile.id}) not found in active ExoPlayer queue. No action needed by PlayerController.")
            }
        }
    }

    suspend fun removeFromQueue(audioFile: AudioFile) {
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller == null) {
                Log.e(TAG, "MediaController not available. Cannot remove from queue.")
                playbackState.update { it.copy(error = "Player not initialized. Cannot remove from queue.") }
                return@withContext
            }

            val mediaIdToRemove = audioFile.id.toString()
            var removedIndex: Int? = null
            for (i in 0 until controller.mediaItemCount) {
                val mediaItem = controller.getMediaItemAt(i)
                if (mediaItem.mediaId == mediaIdToRemove) {
                    removedIndex = i
                    break
                }
            }

            if (removedIndex != null) {
                try {
                    val wasPlayingRemovedSong = (controller.currentMediaItemIndex == removedIndex) && controller.isPlaying
                    controller.removeMediaItem(removedIndex)
                    sharedAudioDataSource.removeAudioFileFromPlayingQueue(audioFile)

                    Log.d(TAG, "Removed '${audioFile.title}' from ExoPlayer queue at index $removedIndex.")

                    if (controller.mediaItemCount == 0) {
                        controller.stop()
                        controller.clearMediaItems()
                        playbackState.update { it.copy(currentAudioFile = null, isPlaying = false) }
                        progressTracker.resetProgress()
                        Log.d(TAG, "ExoPlayer queue is empty after removal. Stopping playback.")
                    } else if (wasPlayingRemovedSong) {
                        stateUpdater.updatePlaybackState()
                        progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                        Log.d(TAG, "Currently playing song removed. Player automatically transitioned.")
                    } else {
                        stateUpdater.updatePlaybackState()
                        progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing media item from ExoPlayer queue: ${e.message}", e)
                    playbackState.update { it.copy(error = "Error removing song from queue: ${e.message}") }
                }
            } else {
                Log.d(TAG, "Audio file '${audioFile.title}' (ID: ${audioFile.id}) not found in active ExoPlayer queue. No action needed.")
                playbackState.update { it.copy(error = "'${audioFile.title}' not found in queue to remove.") }
            }
        }
    }

    suspend fun updateAudioFileInQueue(updatedAudio: AudioFile) {
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller == null) {
                Log.e(TAG, "MediaController not available. Cannot update audio metadata.")
                return@withContext
            }

            val mediaId = updatedAudio.id.toString()
            (0 until controller.mediaItemCount).firstOrNull { controller.getMediaItemAt(it).mediaId == mediaId }?.let { index ->
                val newItem = audioFileMapper.mapAudioFileToMediaItem(updatedAudio)
                controller.replaceMediaItem(index, newItem)
                Log.d(TAG, "Updated metadata for audio ${updatedAudio.title} at queue index $index")
                if (index == controller.currentMediaItemIndex) {
                    stateUpdater.updatePlaybackState()
                }
            } ?: Log.d(TAG, "Audio ${updatedAudio.id} not found in current player queue, no update needed.")
        }
    }
}