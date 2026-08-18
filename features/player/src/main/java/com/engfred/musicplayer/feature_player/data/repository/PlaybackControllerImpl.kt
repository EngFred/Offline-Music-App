package com.engfred.musicplayer.feature_player.data.repository

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.core.mapper.AudioFileMapper
import com.engfred.musicplayer.feature_player.data.repository.controller.ControllerCallback
import com.engfred.musicplayer.feature_player.data.repository.controller.MediaControllerBuilder
import com.engfred.musicplayer.feature_player.data.repository.controller.PlaybackProgressTracker
import com.engfred.musicplayer.feature_player.data.repository.controller.PlaybackStateUpdater
import com.engfred.musicplayer.feature_player.data.repository.controller.QueueManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerControllerImpl"

/**
 * Implementation of [PlaybackController] backed by a Media3 [MediaController].
 */
@UnstableApi
@Singleton
class PlaybackControllerImpl @Inject constructor(
    private val sharedAudioDataSource: SharedAudioDataSource,
    audioFileMapper: AudioFileMapper,
    permissionHandlerUseCase: PermissionHandlerUseCase,
    playlistRepository: PlaylistRepository,
    @param:ApplicationContext private val context: Context,
    sessionToken: SessionToken,
    private val settingsRepository: SettingsRepository,
    /** Injected for cross-feature coordination. Lives in :core — no circular dep. */
    private val activePlayerRegistry: ActivePlayerRegistry,
    private val castSessionManager: com.engfred.musicplayer.feature_player.data.cast.CastSessionManager,
    private val castPlaybackBridge: com.engfred.musicplayer.feature_player.data.cast.CastPlaybackBridge
) : PlaybackController {

    private val mediaController    = MutableStateFlow<MediaController?>(null)
    private val _playbackState     = MutableStateFlow(PlaybackState())
    override fun getPlaybackState() = _playbackState.asStateFlow()
    private val repositoryScope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var attachedController: MediaController? = null
    private val pendingPlayNextMediaId = MutableStateFlow<String?>(null)
    @Volatile private var intendedRepeatMode: RepeatMode = RepeatMode.OFF

    private val stateUpdater = PlaybackStateUpdater(_playbackState, mediaController, sharedAudioDataSource, audioFileMapper)
    private val progressTracker = PlaybackProgressTracker(mediaController, stateUpdater)
    private val controllerCallback = ControllerCallback(
        repositoryScope, playlistRepository, stateUpdater,
        progressTracker, pendingPlayNextMediaId, sharedAudioDataSource, _playbackState
    )
    private val mediaControllerBuilder = MediaControllerBuilder(context, sessionToken, mediaController, _playbackState)
    private val queueManager = QueueManager(
        sharedAudioDataSource, audioFileMapper, permissionHandlerUseCase, context,
        mediaController, _playbackState, stateUpdater, progressTracker,
        setRepeatCallback = ::setRepeatMode,
        pendingPlayNextMediaId = pendingPlayNextMediaId
    )

    init {
        Log.d(TAG, "Initializing PlaybackControllerImpl")
        castPlaybackBridge.attachPlaybackState(_playbackState)

        repositoryScope.launch {
            settingsRepository.getAppSettings()
                .map { it.repeatMode }
                .distinctUntilChanged()
                .collectLatest { mode ->
                    intendedRepeatMode = mode
                    setRepeatMode(mode)
                }
        }

        repositoryScope.launch {
            mediaControllerBuilder.buildAndConnectController()
        }

        repositoryScope.launch {
            mediaController.collectLatest { newController ->
                withContext(Dispatchers.Main) {
                    attachedController?.removeListener(controllerCallback)
                    if (newController != null) {
                        newController.addListener(controllerCallback)
                        attachedController = newController
                        setRepeatMode(intendedRepeatMode)
                        stateUpdater.updatePlaybackState()
                        progressTracker.updateCurrentAudioFilePlaybackProgress(newController)
                    } else {
                        attachedController = null
                        _playbackState.update { PlaybackState() }
                        controllerCallback.resetTracking()
                        progressTracker.resetProgress()
                    }
                }
            }
        }

        repositoryScope.launch {
            progressTracker.playEventRecorder = controllerCallback
            progressTracker.startPlaybackPositionUpdates()
        }

        repositoryScope.launch {
            activePlayerRegistry.pauseNormalPlayerSignal.collect {
                withContext(Dispatchers.Main) {
                    mediaController.value?.pause()
                    Log.d(TAG, "Transient audio focus requested (e.g. Trimmer) — normal player paused.")
                }
            }
        }
    }

    /**
     * Starts normal playback from [initialAudioFileUri].
     */
    override suspend fun initiatePlayback(
        initialAudioFileUri: android.net.Uri,
        startPositionMs: Long
    ) {
        if (castSessionManager.isConnected()) {
            val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
            val targetAudio = playingQueue.find { it.uri == initialAudioFileUri }
                ?: sharedAudioDataSource.deviceAudioFiles.value.find { it.uri == initialAudioFileUri }
            if (targetAudio != null) {
                val startIndex = playingQueue.indexOfFirst { it.uri == initialAudioFileUri }.coerceAtLeast(0)
                val pos = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
                if (playingQueue.isNotEmpty()) {
                    castSessionManager.loadQueue(playingQueue, startIndex, pos, intendedRepeatMode)
                } else {
                    castSessionManager.loadMedia(targetAudio, pos)
                }
                return
            }
        }
        queueManager.initiatePlayback(initialAudioFileUri, intendedRepeatMode, startPositionMs)
    }

    override suspend fun initiateShufflePlayback(playingQueue: List<AudioFile>) {
        if (playingQueue.isEmpty()) {
            Log.w(TAG, "Cannot initiate shuffle playback: empty queue.")
            return
        }
        val shuffledQueue = playingQueue.shuffled()
        sharedAudioDataSource.setPlayingQueue(shuffledQueue)
        initiatePlayback(shuffledQueue.first().uri, C.TIME_UNSET)
    }

    override suspend fun playPause() {
        if (castSessionManager.isConnected()) {
            castSessionManager.togglePlayPause()
            return
        }
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller != null) {
                if (controller.mediaItemCount == 0) {
                    val lastState = settingsRepository.getLastPlaybackState().first()
                    val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
                    val startAudio = lastState.audioId?.let { id -> playingQueue.find { it.id == id } }
                    val startUri = startAudio?.uri ?: playingQueue.firstOrNull()?.uri
                    if (startUri != null) {
                        val resumePosition = if (startAudio != null && lastState.positionMs > 0) lastState.positionMs else C.TIME_UNSET
                        initiatePlayback(startUri, resumePosition)
                    }
                } else {
                    if (controller.isPlaying) controller.pause() else controller.play()
                }
            } else {
                Log.w(TAG, "MediaController not set when trying to play/pause.")
            }
        }
    }

    override suspend fun skipToNext() {
        if (castSessionManager.isConnected()) {
            castSessionManager.skipToNext()
            return
        }
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller != null) {
                if (controller.mediaItemCount == 0) {
                    val lastState = settingsRepository.getLastPlaybackState().first()
                    val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
                    val currentIndex = lastState.audioId?.let { id -> playingQueue.indexOfFirst { it.id == id } }?.takeIf { it != -1 } ?: 0
                    val nextAudio = playingQueue.getOrNull(currentIndex + 1) ?: playingQueue.firstOrNull()
                    if (nextAudio != null) {
                        initiatePlayback(nextAudio.uri, C.TIME_UNSET)
                    }
                } else {
                    controller.seekToNextMediaItem()
                }
            } else {
                Log.w(TAG, "MediaController not set when trying to skip next.")
            }
        }
    }

    override suspend fun skipToPrevious() {
        if (castSessionManager.isConnected()) {
            castSessionManager.skipToPrevious()
            return
        }
        withContext(Dispatchers.Main) {
            val controller = mediaController.value
            if (controller != null) {
                if (controller.mediaItemCount == 0) {
                    val lastState = settingsRepository.getLastPlaybackState().first()
                    val playingQueue = sharedAudioDataSource.playingQueueAudioFiles.value
                    val currentIndex = lastState.audioId?.let { id -> playingQueue.indexOfFirst { it.id == id } }?.takeIf { it != -1 } ?: 0
                    val prevIndex = if (currentIndex > 0) currentIndex - 1 else (playingQueue.size - 1).coerceAtLeast(0)
                    val prevAudio = playingQueue.getOrNull(prevIndex)
                    if (prevAudio != null) {
                        initiatePlayback(prevAudio.uri, C.TIME_UNSET)
                    }
                } else {
                    controller.seekToPreviousMediaItem()
                }
            } else {
                Log.w(TAG, "MediaController not set when trying to skip previous.")
            }
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        if (castSessionManager.isConnected()) {
            castSessionManager.seekTo(positionMs)
            return
        }
        withContext(Dispatchers.Main) {
            mediaController.value?.let { controller ->
                controller.seekTo(positionMs)
                stateUpdater.updatePlaybackState()
                progressTracker.updateCurrentAudioFilePlaybackProgress(controller)
            } ?: Log.w(TAG, "MediaController not set when trying to seek.")
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        intendedRepeatMode = mode
        withContext(Dispatchers.Main) {
            if (castSessionManager.isConnected()) {
                castSessionManager.setRepeatMode(mode)
            }
            mediaController.value?.let { controller ->
                controller.repeatMode = when (mode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                }
            } ?: Log.w(TAG, "MediaController not available for setRepeatMode.")
            _playbackState.update { it.copy(repeatMode = mode) }
        }
    }

    override suspend fun setShuffleMode(mode: ShuffleMode) {
        withContext(Dispatchers.Main) {
            if (castSessionManager.isConnected()) {
                val queue = sharedAudioDataSource.playingQueueAudioFiles.value
                val currentAudio = _playbackState.value.currentAudioFile
                castSessionManager.setShuffleMode(mode, queue, currentAudio)
            }
            mediaController.value?.let {
                it.shuffleModeEnabled = mode == ShuffleMode.ON
            } ?: Log.w(TAG, "MediaController not available for setShuffleMode.")
            _playbackState.update { it.copy(shuffleMode = mode) }
        }
    }

    override suspend fun addAudioToQueueNext(audioFile: AudioFile) {
        queueManager.addAudioToQueueNext(audioFile)
    }

    override suspend fun releasePlayer() {
        val controllerToRelease = mediaController.value
        repositoryScope.cancel()
        withContext(Dispatchers.Main) {
            attachedController?.removeListener(controllerCallback)
            attachedController = null
            controllerCallback.resetTracking()
            progressTracker.resetProgress()
            try { controllerToRelease?.release() } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaController: ${e.message}")
            } finally {
                mediaController.value = null
            }
        }
    }

    override fun clearPlaybackError() {
        _playbackState.update { it.copy(error = null) }
    }

    override suspend fun onAudioFileRemoved(deletedAudioFile: AudioFile) {
        queueManager.onAudioFileRemoved(deletedAudioFile)
    }

    override suspend fun removeFromQueue(audioFile: AudioFile) {
        queueManager.removeFromQueue(audioFile)
    }

    override suspend fun waitUntilReady(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (mediaController.value == null && System.currentTimeMillis() - start < timeoutMs) {
            delay(100)
        }
        return mediaController.value != null
    }

    override suspend fun updateAudioMetadata(updatedAudio: AudioFile) {
        queueManager.updateAudioFileInQueue(updatedAudio)
    }

    override fun toggleStopAfterCurrent() {
        _playbackState.update {
            val newState = !it.stopAfterCurrent
            Log.d(TAG, "StopAfterCurrent toggled to: $newState")
            it.copy(stopAfterCurrent = newState)
        }
    }
}