package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
import com.engfred.musicplayer.feature_dj_mix.domain.DjSessionManager
import com.engfred.musicplayer.feature_dj_mix.domain.model.DjMixSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo
import com.engfred.musicplayer.feature_dj_mix.domain.repository.DjMixRepository
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.AnalyzeBpmUseCase
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object DjMixArgs {
    const val PLAYLIST_ID = "playlistId"
}

private const val TAG = "DjMixViewModel"

/**
 * ViewModel for the DJ Mix screen.
 *
 * ── What changed and why ─────────────────────────────────────────────────────
 *
 * 1. observeNextTrackRequests() REMOVED.
 *    Track selection and queuing is now handled entirely by [DjMixService], which
 *    shares the same lifecycle as the [CrossfadeEngine]. The ViewModel was the wrong
 *    lifecycle owner — when cleared (user leaves screen), it cancelled the coroutine
 *    and the queue stopped.
 *
 * 2. playedTrackIds REMOVED from ViewModel.
 *    Moved to [DjSessionManager] which is a Singleton. Survives ViewModel recreation
 *    so the history is intact when the user returns to the screen.
 *
 * 3. DjSessionManager updated whenever local state changes.
 *    observeSettings / observeBpmCache / rebuildSmartQueue now push their results
 *    into DjSessionManager so the Service always has the latest data.
 *
 * 4. Auto-start moved from Screen to ViewModel.
 *    Three duplicate LaunchedEffect blocks in DjMixScreen (race condition) replaced
 *    by a single observeAutoStart() here.
 *
 * 5. ActivePlayerRegistry used to pause the normal player when DJ starts.
 *    onDjMixStarted() sets isDjMixActive=true; PlaybackControllerImpl observes this
 *    and pauses the ExoPlayer backing the normal player.
 *
 * 6. Re-entry handling: if the session is already active (user returns to screen
 *    while music is playing), autoStartTriggered = true skips re-initialisation.
 */
@UnstableApi
@HiltViewModel
class DjMixViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val djMixRepository: DjMixRepository,
    private val settingsRepository: SettingsRepository,
    private val analyzeBpmUseCase: AnalyzeBpmUseCase,
    private val getSmartNextTrackUseCase: GetSmartNextTrackUseCase,
    private val djSessionManager: DjSessionManager,
    private val activePlayerRegistry: ActivePlayerRegistry,
    val crossfadeEngine: CrossfadeEngine
) : ViewModel() {

    private val playlistId: Long =
        checkNotNull(savedStateHandle[DjMixArgs.PLAYLIST_ID]) {
            "DjMixViewModel requires a playlistId in SavedStateHandle"
        }

    private val _uiState = MutableStateFlow(DjMixUiState())
    val uiState: StateFlow<DjMixUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private var rawPlaylistSongs: List<AudioFile> = emptyList()

    /**
     * Prevents auto-start from firing more than once per session.
     * Set to true on first start, and also on re-entry when a session is already active.
     */
    private var autoStartTriggered = false

    init {
        crossfadeEngine.initialize()

        // FIX: Re-entry guard — if a session is already running, don't auto-start again.
        autoStartTriggered = djSessionManager.isSessionActive.value

        observeSettings()
        observeCrossfadeEngineState()
        // NOTE: observeNextTrackRequests() is intentionally absent.
        // DjMixService handles all nextTrackRequest emissions.
        loadPlaylist()
        observeAutoStart()
    }

    // ── Event handler ─────────────────────────────────────────────────────────

    fun onEvent(event: DjMixEvent) {
        when (event) {
            DjMixEvent.PlayPause -> crossfadeEngine.playPause()

            is DjMixEvent.UpdateCrossfadeDuration -> {
                val s = _uiState.value.settings.copy(crossfadeDurationSec = event.seconds)
                crossfadeEngine.crossfadeDurationMs = event.seconds * 1000L
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjCrossfadeDuration(event.seconds) }
            }

            is DjMixEvent.UpdateBpmTolerance -> {
                val s = _uiState.value.settings.copy(bpmTolerance = event.tolerance)
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                rebuildSmartQueue()
                viewModelScope.launch { settingsRepository.updateDjBpmTolerance(event.tolerance) }
            }

            is DjMixEvent.ToggleRealMixMode -> {
                val s = _uiState.value.settings.copy(isRealMixMode = event.enabled)
                crossfadeEngine.isRealMixMode = event.enabled
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjRealMixMode(event.enabled) }
            }

            is DjMixEvent.UpdateMaxTrackDuration -> {
                val s = _uiState.value.settings.copy(maxTrackDurationSec = event.seconds)
                crossfadeEngine.maxTrackDurationMs = event.seconds * 1000L
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjMaxTrackDuration(event.seconds) }
            }

            is DjMixEvent.ToggleLoopQueue -> {
                val s = _uiState.value.settings.copy(loopQueue = event.enabled)
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjLoopQueue(event.enabled) }
            }

            is DjMixEvent.JumpToTrack -> {
                // Reset play history so the manual selection starts a fresh arc
                djSessionManager.resetPlayHistory()
                djSessionManager.markTrackPlayed(event.audioFile.id)

                crossfadeEngine.startPlayback(event.audioFile)
                _uiState.update { it.copy(currentTrack = event.audioFile) }
                syncBeatGridForTrack(event.audioFile.id)

                if (!djSessionManager.isSessionActive.value) {
                    djSessionManager.startSession()
                    activePlayerRegistry.onDjMixStarted()
                }
                viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun observeSettings() {
        settingsRepository.getAppSettings()
            .onEach { appSettings ->
                val currentSettings = _uiState.value.settings
                val newSettings = DjMixSettings(
                    crossfadeDurationSec = appSettings.crossfadeDurationSec,
                    bpmTolerance         = appSettings.bpmTolerance,
                    isRealMixMode        = appSettings.isRealMixMode,
                    maxTrackDurationSec  = appSettings.maxTrackDurationSec,
                    loopQueue            = appSettings.loopQueue
                )

                crossfadeEngine.crossfadeDurationMs = newSettings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = newSettings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = newSettings.maxTrackDurationSec * 1000L

                val toleranceChanged = currentSettings.bpmTolerance != newSettings.bpmTolerance

                _uiState.update { it.copy(settings = newSettings) }
                djSessionManager.updateSettings(newSettings)

                if (toleranceChanged) rebuildSmartQueue()
            }
            .launchIn(viewModelScope)
    }

    private fun loadPlaylist() {
        playlistRepository.getPlaylistById(playlistId)
            .onEach { playlist ->
                if (playlist == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Playlist not found.") }
                    return@onEach
                }
                val songs = playlist.songs
                rawPlaylistSongs = songs

                if (songs.isNotEmpty()) {
                    _uiState.update { it.copy(isAnalyzing = true) }
                    analyzeBpmUseCase(playlistId, songs)
                }

                _uiState.update {
                    it.copy(playlistName = playlist.name, totalSongs = songs.size, isLoading = false)
                }
                observeBpmCache(songs)
            }
            .launchIn(viewModelScope)
    }

    private fun observeBpmCache(songs: List<AudioFile>) {
        djMixRepository.getBpmCacheFlow()
            .onEach { bpmCache ->
                val analysedCount  = songs.count { bpmCache.containsKey(it.id) }
                val progress       = if (songs.isEmpty()) 1f else analysedCount.toFloat() / songs.size
                val stillAnalysing = progress < 1f

                // If BPM data just arrived for the currently playing track mid-playback,
                // push the updated beat grid to the engine immediately.
                val currentTrackId = _uiState.value.currentTrack?.id
                if (currentTrackId != null) {
                    val freshBpmInfo  = bpmCache[currentTrackId]
                    val cachedBpmInfo = _uiState.value.bpmCache[currentTrackId]
                    if (freshBpmInfo != null && cachedBpmInfo == null) {
                        crossfadeEngine.updateCurrentBpmInfo(
                            freshBpmInfo.bpm, freshBpmInfo.firstBeatMs, freshBpmInfo.amplitude
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        bpmCache          = bpmCache,
                        analysisProgress  = progress,
                        isAnalyzing       = stillAnalysing
                    )
                }
                // Keep session manager in sync so Service can select the next track
                // with the latest BPM data.
                djSessionManager.updateBpmCache(bpmCache)
                rebuildSmartQueue(bpmCache = bpmCache)
            }
            .launchIn(viewModelScope)
    }

    private fun observeCrossfadeEngineState() {
        crossfadeEngine.state
            .onEach { engineState ->
                val prevTrackId = _uiState.value.currentTrack?.id
                val newTrackId  = engineState.currentTrack?.id
                if (newTrackId != null && newTrackId != prevTrackId) {
                    djSessionManager.markTrackPlayed(newTrackId)
                    syncBeatGridForTrack(newTrackId)
                }

                _uiState.update {
                    it.copy(
                        currentTrack              = engineState.currentTrack,
                        isPlaying                 = engineState.isPlaying,
                        isCrossfading             = engineState.isCrossfading,
                        currentPositionMs         = engineState.currentPositionMs,
                        currentDurationMs         = engineState.currentDurationMs,
                        crossfadeProgressFraction = engineState.crossfadeProgressFraction,
                        error                     = engineState.error
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * FIX: Auto-start logic moved from DjMixScreen's three duplicate LaunchedEffect
     * blocks into a single, race-condition-free observer here.
     *
     * Fires once when:
     * - The smart queue is populated for the first time
     * - No track is currently playing (engine currentTrack == null)
     * - The session hasn't already been started (autoStartTriggered == false)
     */
    private fun observeAutoStart() {
        _uiState
            .onEach { state ->
                if (!autoStartTriggered
                    && state.smartQueue.isNotEmpty()
                    && !state.isLoading
                    && crossfadeEngine.state.value.currentTrack == null
                ) {
                    autoStartTriggered = true
                    val firstTrack = state.smartQueue.first()

                    djSessionManager.startSession()
                    djSessionManager.markTrackPlayed(firstTrack.id)
                    activePlayerRegistry.onDjMixStarted() // pauses normal player

                    crossfadeEngine.startPlayback(firstTrack)
                    _uiEvent.emit("START_DJ_SERVICE")
                    Log.d(TAG, "Auto-start: '${firstTrack.title}'")
                }
            }
            .launchIn(viewModelScope)
    }

    private fun syncBeatGridForTrack(trackId: Long) {
        val bpmInfo = _uiState.value.bpmCache[trackId] ?: return
        crossfadeEngine.updateCurrentBpmInfo(bpmInfo.bpm, bpmInfo.firstBeatMs, bpmInfo.amplitude)
        Log.d(TAG, "Beat grid synced: trackId=$trackId BPM=${bpmInfo.bpm}")
    }

    private fun rebuildSmartQueue(
        bpmCache: Map<Long, BpmInfo> = _uiState.value.bpmCache
    ) {
        if (rawPlaylistSongs.isEmpty()) return

        val tolerance = _uiState.value.settings.bpmTolerance
        val remaining = rawPlaylistSongs.toMutableList()
        val result    = mutableListOf<AudioFile>()

        val first = remaining.minByOrNull { bpmCache[it.id]?.bpm ?: Float.MAX_VALUE }
            ?: remaining.first()
        result.add(first)
        remaining.remove(first)

        while (remaining.isNotEmpty()) {
            val lastBpm = bpmCache[result.last().id]?.bpm ?: 120f
            val next = getSmartNextTrackUseCase(
                currentBpm     = lastBpm,
                remainingQueue = remaining,
                bpmCache       = bpmCache.mapValues { it.value.bpm },
                tolerance      = tolerance
            ) ?: remaining.first()
            result.add(next)
            remaining.remove(next)
        }

        _uiState.update { it.copy(smartQueue = result) }
        // Keep session manager in sync for Service's selectNextTrack()
        djSessionManager.updateSmartQueue(result)
    }

    override fun onCleared() {
        super.onCleared()
        // Engine is intentionally NOT released here. Music continues in the background
        // via DjMixService. The session state is preserved in DjSessionManager.
        Log.d(TAG, "onCleared — engine and session left running in background.")
    }
}