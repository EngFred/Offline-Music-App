package com.engfred.musicplayer.feature_player.data.repository

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlayerControllerImpl"

/**
 * Implementation of [PlaybackController] backed by a Media3 [MediaController].
 *
 * ── What changed and why ─────────────────────────────────────────────────────
 * BUG FIX: Playing a song from the home screen while the DJ Mix was active caused
 * both audio systems to play simultaneously. Two-way coordination is now handled
 * via [ActivePlayerRegistry]:
 *
 * 1. When normal playback starts (initiatePlayback / initiateShufflePlayback),
 *    [ActivePlayerRegistry.requestStopDjMix] is called. This emits a signal that
 *    [DjMixService] observes — it releases the CrossfadeEngine and stops itself.
 *    There is no direct class reference to DjMixService here; the registry in
 *    :core acts as a decoupled message bus between the two feature modules.
 *
 * 2. When the DJ Mix starts, DjMixViewModel calls [ActivePlayerRegistry.onDjMixStarted].
 *    This controller observes [isDjMixActive] becoming true and pauses the normal
 *    ExoPlayer so both systems never hold audio focus simultaneously.
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
    private val activePlayerRegistry: ActivePlayerRegistry
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

        // FIX: Pause the normal ExoPlayer whenever the DJ Mix becomes active.
        // This prevents both systems from playing simultaneously when the user
        // starts the DJ Mix while a normal song is already playing.
        repositoryScope.launch {
            activePlayerRegistry.isDjMixActive
                .collect { isDjActive ->
                    if (isDjActive) {
                        withContext(Dispatchers.Main) {
                            mediaController.value?.pause()
                            Log.d(TAG, "DJ Mix started — normal player paused.")
                        }
                    }
                }
        }
    }

    /**
     * Starts normal playback from [initialAudioFileUri].
     *
     * FIX: Calls requestStopDjMix() before initiating playback. If DjMixService is
     * running, it will receive the stop signal and release the CrossfadeEngine,
     * preventing both audio systems from holding audio focus simultaneously.
     */
    override suspend fun initiatePlayback(
        initialAudioFileUri: android.net.Uri,
        startPositionMs: Long
    ) {
        // Stop the DJ Mix if it is active. DjMixService observes this signal.
        activePlayerRegistry.requestStopDjMix()
        queueManager.initiatePlayback(initialAudioFileUri, intendedRepeatMode, startPositionMs)
    }

    override suspend fun initiateShufflePlayback(playingQueue: List<AudioFile>) {
        if (playingQueue.isEmpty()) {
            Log.w(TAG, "Cannot initiate shuffle playback: empty queue.")
            return
        }
        // Stop the DJ Mix before taking over audio focus.
        activePlayerRegistry.requestStopDjMix()
        val shuffledQueue = playingQueue.shuffled()
        sharedAudioDataSource.setPlayingQueue(shuffledQueue)
        initiatePlayback(shuffledQueue.first().uri, C.TIME_UNSET)
    }

    override suspend fun playPause() {
        withContext(Dispatchers.Main) {
            mediaController.value?.run {
                if (isPlaying) pause() else play()
            } ?: Log.w(TAG, "MediaController not set when trying to play/pause.")
        }
    }

    override suspend fun skipToNext() {
        withContext(Dispatchers.Main) {
            mediaController.value?.seekToNextMediaItem()
                ?: Log.w(TAG, "MediaController not set when trying to skip next.")
        }
    }

    override suspend fun skipToPrevious() {
        withContext(Dispatchers.Main) {
            mediaController.value?.seekToPreviousMediaItem()
                ?: Log.w(TAG, "MediaController not set when trying to skip previous.")
        }
    }

    override suspend fun seekTo(positionMs: Long) {
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
            mediaController.value?.let { controller ->
                controller.repeatMode = when (mode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                }
                _playbackState.update { it.copy(repeatMode = mode) }
            } ?: Log.w(TAG, "MediaController not available for setRepeatMode.")
        }
    }

    override suspend fun setShuffleMode(mode: ShuffleMode) {
        withContext(Dispatchers.Main) {
            mediaController.value?.let {
                it.shuffleModeEnabled = mode == ShuffleMode.ON
                _playbackState.update { it.copy(shuffleMode = mode) }
            } ?: Log.w(TAG, "MediaController not available for setShuffleMode.")
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