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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var rebuildJob: Job? = null

    init {
        crossfadeEngine.initialize()
        observeSettings()
        observeCrossfadeEngineState()
        loadPlaylist()
    }

    fun onEvent(event: DjMixEvent) {
        when (event) {
            DjMixEvent.PlayPause -> {
                if (!crossfadeEngine.isActive || crossfadeEngine.state.value.currentTrack == null) {
                    val firstTrack = _uiState.value.smartQueue.firstOrNull() ?: return
                    crossfadeEngine.initialize()
                    djSessionManager.startSession(playlistId)
                    djSessionManager.markTrackPlayed(firstTrack.id)
                    activePlayerRegistry.onDjMixStarted()
                    // Sync BPM info BEFORE startPlayback so the waveform loop has
                    // valid currentTrackBpm the moment it starts. Without this,
                    // the waveform stays blank on a fresh session start because the
                    // ViewModel's observeCrossfadeEngineState flow hasn't fired yet
                    // when the waveform loop makes its first check.
                    syncBeatGridForTrack(firstTrack.id)
                    crossfadeEngine.startPlayback(firstTrack)
                    _uiState.update { it.copy(currentTrack = firstTrack) }
                    viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
                    Log.d(TAG, "PlayPause: started session with '${firstTrack.title}'")
                } else {
                    crossfadeEngine.playPause()
                }
            }

            DjMixEvent.MixNow -> crossfadeEngine.triggerMixNow()

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

            is DjMixEvent.ToggleManualMaxDuration -> {
                val s = _uiState.value.settings.copy(useManualMaxDuration = event.enabled)
                crossfadeEngine.useHalfwayMix = !event.enabled
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjManualMaxDuration(event.enabled) }
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
                djSessionManager.resetPlayHistory()
                djSessionManager.markTrackPlayed(event.audioFile.id)
                // Sync before startPlayback for the same reason as PlayPause above
                syncBeatGridForTrack(event.audioFile.id)
                crossfadeEngine.startPlayback(event.audioFile)
                _uiState.update { it.copy(currentTrack = event.audioFile) }
                if (!djSessionManager.isSessionActive.value) {
                    djSessionManager.startSession(playlistId)
                    activePlayerRegistry.onDjMixStarted()
                }
                viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
            }
        }
    }

    // ── Private observers ─────────────────────────────────────────────────────

    private fun observeSettings() {
        settingsRepository.getAppSettings()
            .onEach { appSettings ->
                val currentSettings = _uiState.value.settings
                val newSettings = DjMixSettings(
                    crossfadeDurationSec = appSettings.crossfadeDurationSec,
                    bpmTolerance         = appSettings.bpmTolerance,
                    isRealMixMode        = appSettings.isRealMixMode,
                    maxTrackDurationSec  = appSettings.maxTrackDurationSec,
                    loopQueue            = appSettings.loopQueue,
                    useManualMaxDuration = appSettings.useManualMaxDuration
                )
                crossfadeEngine.crossfadeDurationMs = newSettings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = newSettings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = newSettings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix       = !newSettings.useManualMaxDuration

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

                val sessionAlreadyActive = djSessionManager.isSessionActive.value
                        && djSessionManager.activePlaylistId == playlistId

                if (songs.isNotEmpty() && !sessionAlreadyActive) {
                    _uiState.update { it.copy(isAnalyzing = true) }
                    analyzeBpmUseCase(playlistId, songs)
                }

                _uiState.update {
                    it.copy(
                        playlistName = playlist.name,
                        totalSongs   = songs.size,
                        isLoading    = false
                    )
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
                        bpmCache         = bpmCache,
                        analysisProgress = progress,
                        isAnalyzing      = stillAnalysing
                    )
                }
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
                        waveform                  = engineState.waveform,
                        error                     = engineState.error
                    )
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
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(300L)
            performRebuild(bpmCache)
        }
    }

    private fun performRebuild(bpmCache: Map<Long, BpmInfo>) {
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
        djSessionManager.updateSmartQueue(result)
        Log.d(TAG, "performRebuild: queue rebuilt with ${result.size} tracks")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared — engine and session left running in background.")
    }
}