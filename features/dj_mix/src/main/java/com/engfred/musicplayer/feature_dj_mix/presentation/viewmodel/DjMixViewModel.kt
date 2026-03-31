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
import com.engfred.musicplayer.feature_dj_mix.data.sampler.SamplerEngine
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

/**
 * 🚨 ARCHITECTURE NOTE 🚨
 * This ViewModel acts as the supreme orchestrator for the DJ Mix session.
 * It coordinates the Hardware (CrossfadeEngine), the Audio FX (SamplerEngine),
 * and the State/History (DjSessionManager).
 * * - Handles the "Dead End" queue restart logic.
 * - Handles "Cross-Playlist Navigation" auto-starts.
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
    val crossfadeEngine: CrossfadeEngine,
    private val samplerEngine: SamplerEngine
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

    // Flag to trigger an auto-start once the queue finishes building after a playlist swap
    private var pendingAutoStart = false

    init {
        crossfadeEngine.initialize()
        observeSettings()
        observeCrossfadeEngineState()
        observeCanSkipBack()
        observePlayedTracks()
        loadPlaylist()
    }

    fun onEvent(event: DjMixEvent) {
        when (event) {
            DjMixEvent.PlayPause -> {
                val engineState = crossfadeEngine.state.value

                // ── DEAD END DETECTION ──
                // Detect if the mix naturally reached the end of the final track without looping.
                val isAtEndOfTrack = engineState.currentDurationMs > 0L &&
                        (engineState.currentDurationMs - engineState.currentPositionMs) <= 500L
                val isQueueExhausted = engineState.currentTrack != null &&
                        djSessionManager.selectNextTrack(engineState.currentTrack.id) == null
                val isMixFinished = !engineState.isPlaying && isAtEndOfTrack && isQueueExhausted

                if (!crossfadeEngine.isActive || engineState.currentTrack == null || isMixFinished) {
                    // ── INITIAL START OR RESTART AFTER FINISH ──
                    Log.i(TAG, "[PLAYBACK] Starting fresh mix session (isMixFinished=$isMixFinished)")

                    crossfadeEngine.initialize()
                    djSessionManager.startSession(playlistId)
                    djSessionManager.resetPlayHistory() // Wipe history for a completely fresh start

                    val firstTrack = _uiState.value.smartQueue.firstOrNull() ?: return
                    djSessionManager.markTrackPlayed(firstTrack.id)
                    activePlayerRegistry.onDjMixStarted()

                    syncBeatGridForTrack(firstTrack.id)
                    crossfadeEngine.startPlayback(firstTrack)

                    // Fire the session Air Horn locally! Fixes the "Missing Horn" bug on new playlists.
                    samplerEngine.onSessionStarted()

                    _uiState.update { it.copy(currentTrack = firstTrack) }
                    viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
                    updateNextTrackPreview()
                } else {
                    // ── NORMAL PAUSE / RESUME ──
                    crossfadeEngine.playPause()
                }
            }

            is DjMixEvent.ToggleAutoSampler -> {
                val s = _uiState.value.settings.copy(autoSamplerEnabled = event.enabled)
                samplerEngine.isAutoSamplerEnabled = event.enabled
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjAutoSampler(event.enabled) }
            }

            is DjMixEvent.UpdateSampleVolume -> {
                val s = _uiState.value.settings.copy(sampleVolume = event.volume)
                samplerEngine.sampleVolume = event.volume
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjSampleVolume(event.volume) }
            }
        }
    }

    private fun updateNextTrackPreview() {
        val currentId = _uiState.value.currentTrack?.id
        if (currentId == null) {
            _uiState.update { it.copy(nextTrack = null) }
            return
        }
        val next = djSessionManager.selectNextTrack(currentId)
        _uiState.update { it.copy(nextTrack = next) }
    }

    // ── Private observers ─────────────────────────────────────────────────────

    private fun observeCanSkipBack() {
        djSessionManager.canSkipBack
            .onEach { can -> _uiState.update { it.copy(canSkipBack = can) } }
            .launchIn(viewModelScope)
    }

    private fun observePlayedTracks() {
        djSessionManager.playedTrackIds
            .onEach { ids -> _uiState.update { it.copy(playedTrackIds = ids) } }
            .launchIn(viewModelScope)
    }

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
                    useManualMaxDuration = appSettings.useManualMaxDuration,
                    autoSamplerEnabled   = appSettings.autoSamplerEnabled,
                    sampleVolume         = appSettings.sampleVolume
                )
                crossfadeEngine.crossfadeDurationMs = newSettings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = newSettings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = newSettings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix       = !newSettings.useManualMaxDuration
                samplerEngine.isAutoSamplerEnabled  = newSettings.autoSamplerEnabled
                samplerEngine.sampleVolume          = newSettings.sampleVolume

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

                // ── CROSS-PLAYLIST NAVIGATION FIX ──
                // If the user taps a different playlist from the home screen, kill the old session entirely.
                val activeId = djSessionManager.activePlaylistId
                if (djSessionManager.isSessionActive.value && activeId != null && activeId != playlistId) {
                    Log.i(TAG, "[LIFECYCLE] Different playlist detected ($playlistId). Killing old session ($activeId) & Auto-Starting new.")
                    samplerEngine.stopAllSamples()
                    crossfadeEngine.release()
                    djSessionManager.endSession()
                    crossfadeEngine.initialize()
                    pendingAutoStart = true // Signals the queue builder to trigger playback automatically
                }

                val songs = playlist.songs
                rawPlaylistSongs = songs

                val sessionAlreadyActive = djSessionManager.isSessionActive.value && djSessionManager.activePlaylistId == playlistId

                if (songs.isNotEmpty() && !sessionAlreadyActive) {
                    _uiState.update { it.copy(isAnalyzing = true) }
                    analyzeBpmUseCase(playlistId, songs)
                }

                _uiState.update { it.copy(playlistName = playlist.name, totalSongs = songs.size, isLoading = false) }
                observeBpmCache(songs)
            }
            .launchIn(viewModelScope)
    }

    private fun observeBpmCache(songs: List<AudioFile>) {
        djMixRepository.getBpmCacheFlow()
            .onEach { bpmCache ->
                val doneCount    = songs.count { bpmCache.containsKey(it.id) }
                val failedCount  = songs.count { bpmCache[it.id]?.analysisFailed == true }
                val progress     = if (songs.isEmpty()) 1f else doneCount.toFloat() / songs.size
                val stillAnalysing = progress < 1f

                val currentTrackId = _uiState.value.currentTrack?.id
                if (currentTrackId != null) {
                    val freshBpmInfo  = bpmCache[currentTrackId]
                    val cachedBpmInfo = _uiState.value.bpmCache[currentTrackId]

                    val isNewlyAnalyzed = freshBpmInfo != null && cachedBpmInfo == null && !freshBpmInfo.analysisFailed

                    if (isNewlyAnalyzed) {
                        crossfadeEngine.updateCurrentBpmInfo(
                            bpm              = freshBpmInfo!!.bpm,
                            firstBeatMs      = freshBpmInfo.firstBeatMs,
                            amplitude        = freshBpmInfo.amplitude,
                            waveformEnvelope = freshBpmInfo.waveformEnvelope,
                            mixOutMs         = null
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        bpmCache            = bpmCache,
                        analysisProgress    = progress,
                        isAnalyzing         = stillAnalysing,
                        analysisFailedCount = failedCount
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
                        currentMixStrategy        = engineState.currentMixStrategy,
                        waveform                  = engineState.waveform,
                        error                     = engineState.error,
                        timeToNextMixMs           = engineState.timeToNextMixMs
                    )
                }

                if (newTrackId != null && newTrackId != prevTrackId) {
                    updateNextTrackPreview()
                }
            }
            .launchIn(viewModelScope)
    }

    private fun syncBeatGridForTrack(trackId: Long) {
        val bpmInfo = _uiState.value.bpmCache[trackId] ?: return
        if (bpmInfo.analysisFailed) return

        crossfadeEngine.updateCurrentBpmInfo(
            bpm              = bpmInfo.bpm,
            firstBeatMs      = bpmInfo.firstBeatMs,
            amplitude        = bpmInfo.amplitude,
            waveformEnvelope = bpmInfo.waveformEnvelope,
            mixOutMs         = null
        )
    }

    private fun rebuildSmartQueue(
        bpmCache: Map<Long, BpmInfo> = _uiState.value.bpmCache
    ) {
        // Removed early return for isQueueUserOrdered — the algorithm is fully in charge now.
        if (rawPlaylistSongs.isEmpty()) return
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(300L)
            performRebuild(bpmCache)
            updateNextTrackPreview()
        }
    }

    private fun performRebuild(bpmCache: Map<Long, BpmInfo>) {
        if (rawPlaylistSongs.isEmpty()) return

        val tolerance = _uiState.value.settings.bpmTolerance
        val remaining = rawPlaylistSongs.toMutableList()
        val result    = mutableListOf<AudioFile>()

        val opener = selectOpener(remaining, bpmCache)
        result.add(opener)
        remaining.remove(opener)

        val openerBpm = bpmCache[opener.id]?.bpm?.takeIf { bpmCache[opener.id]?.analysisFailed != true } ?: 120f
        val recentBpms = ArrayDeque<Float>(4)
        if (openerBpm > 0f) recentBpms.addLast(openerBpm)

        while (remaining.isNotEmpty()) {
            val lastInfo = bpmCache[result.last().id]
            val lastBpm = if (lastInfo?.analysisFailed == true) 120f else lastInfo?.bpm ?: 120f

            val next = getSmartNextTrackUseCase(
                currentBpm          = lastBpm,
                remainingQueue      = remaining,
                bpmCache            = bpmCache.mapValues { if (it.value.analysisFailed) 120f else it.value.bpm },
                tolerance           = tolerance,
                recentBpms          = recentBpms.toList()
            ) ?: remaining.first()

            result.add(next)
            remaining.remove(next)

            val nextBpm = bpmCache[next.id]?.bpm?.takeIf { bpmCache[next.id]?.analysisFailed != true }
            if (nextBpm != null && nextBpm > 0f) {
                recentBpms.addLast(nextBpm)
                if (recentBpms.size > 3) recentBpms.removeFirst()
            }
        }

        _uiState.update { it.copy(smartQueue = result) }
        djSessionManager.updateSmartQueue(result)

        // ── AUTO-START HANDOFF ──
        if (pendingAutoStart && result.isNotEmpty()) {
            Log.i(TAG, "[LIFECYCLE] Queue built. Auto-starting new playlist mix.")
            pendingAutoStart = false
            onEvent(DjMixEvent.PlayPause) // Triggers the fresh-start flow
        }
    }

    private fun selectOpener(songs: List<AudioFile>, bpmCache: Map<Long, BpmInfo>): AudioFile {
        val withBpm = songs.filter { bpmCache.containsKey(it.id) && bpmCache[it.id]?.analysisFailed != true }
        if (withBpm.isEmpty()) return songs.first()

        val sorted   = withBpm.sortedBy { bpmCache[it.id]!!.bpm }
        val n        = sorted.size
        val lowerIdx = (n * 0.20).toInt().coerceIn(0, n - 1)
        val upperIdx = (n * 0.40).toInt().coerceIn(lowerIdx, n - 1)
        val targetIdx = (lowerIdx + upperIdx) / 2

        return sorted.getOrNull(targetIdx) ?: sorted.getOrNull(n / 2) ?: songs.first()
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "[LIFECYCLE] ViewModel Cleared — Service keeps engine alive in background")
    }
}