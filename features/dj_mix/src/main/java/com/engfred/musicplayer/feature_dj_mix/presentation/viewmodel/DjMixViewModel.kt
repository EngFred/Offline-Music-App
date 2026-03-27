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
        observeCanSkipBack()
        observePlayedTracks()
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
                    syncBeatGridForTrack(firstTrack.id)
                    crossfadeEngine.startPlayback(firstTrack)
                    _uiState.update { it.copy(currentTrack = firstTrack) }
                    viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
                    updateNextTrackPreview()
                    Log.d(TAG, "PlayPause: started session with '${firstTrack.title}'")
                } else {
                    crossfadeEngine.playPause()
                }
            }

            DjMixEvent.MixNow -> crossfadeEngine.triggerMixNow()

            DjMixEvent.SkipBack -> {
                val previous = djSessionManager.popPreviousTrack()
                if (previous != null) {
                    djSessionManager.markTrackPlayed(previous.id)
                    syncBeatGridForTrack(previous.id)
                    crossfadeEngine.startPlayback(previous)
                    _uiState.update { it.copy(currentTrack = previous) }
                    viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
                    updateNextTrackPreview()
                    Log.d(TAG, "SkipBack: now playing '${previous.title}'")
                } else {
                    Log.d(TAG, "SkipBack: no history — ignored")
                    viewModelScope.launch { _uiEvent.emit("SKIP_BACK_UNAVAILABLE") }
                }
            }

            // ── Custom Cue Point UI Actions ───────────────────────────────────

            is DjMixEvent.SetCustomCueIn -> {
                val currentTrackId = _uiState.value.currentTrack?.id ?: return
                val positionMs = crossfadeEngine.state.value.currentPositionMs
                viewModelScope.launch {
                    djMixRepository.updateCustomCueIn(currentTrackId, positionMs)
                }
                Log.d(TAG, "Set Custom Cue In for track $currentTrackId at ${positionMs}ms")
            }

            is DjMixEvent.SetCustomMixOut -> {
                val currentTrackId = _uiState.value.currentTrack?.id ?: return
                val positionMs = crossfadeEngine.state.value.currentPositionMs
                viewModelScope.launch {
                    djMixRepository.updateCustomMixOut(currentTrackId, positionMs)
                }
                Log.d(TAG, "Set Custom Mix Out for track $currentTrackId at ${positionMs}ms")
            }

            is DjMixEvent.ClearCustomCues -> {
                val currentTrackId = _uiState.value.currentTrack?.id ?: return
                viewModelScope.launch {
                    djMixRepository.clearCustomCues(currentTrackId)
                }
                Log.d(TAG, "Cleared Custom Cues for track $currentTrackId")
            }

            // ── Queue ordering — all gated while BPM analysis is in progress ──

            DjMixEvent.ShuffleQueue -> {
                if (_uiState.value.isAnalyzing) {
                    Log.d(TAG, "ShuffleQueue: ignored — analysis in progress")
                    return
                }
                val shuffled = _uiState.value.smartQueue.shuffled()
                _uiState.update { it.copy(smartQueue = shuffled, isQueueUserOrdered = true) }
                djSessionManager.updateSmartQueue(shuffled)
                updateNextTrackPreview()
                Log.d(TAG, "ShuffleQueue: queue shuffled")
            }

            DjMixEvent.SortByBpm -> {
                if (_uiState.value.isAnalyzing) {
                    Log.d(TAG, "SortByBpm: ignored — analysis in progress")
                    return
                }
                val cache = _uiState.value.bpmCache
                val sorted = _uiState.value.smartQueue.sortedWith(
                    compareBy(
                        { cache[it.id]?.analysisFailed == true },
                        { if (cache[it.id]?.analysisFailed == true) 0f else cache[it.id]?.bpm ?: Float.MAX_VALUE }
                    )
                )
                _uiState.update { it.copy(smartQueue = sorted, isQueueUserOrdered = true) }
                djSessionManager.updateSmartQueue(sorted)
                updateNextTrackPreview()
                Log.d(TAG, "SortByBpm: queue sorted")
            }

            is DjMixEvent.MoveTrack -> {
                if (_uiState.value.isAnalyzing) {
                    Log.d(TAG, "MoveTrack: ignored — analysis in progress")
                    return
                }
                val current = _uiState.value.smartQueue.toMutableList()
                if (event.fromIndex !in current.indices || event.toIndex !in current.indices) return
                val item = current.removeAt(event.fromIndex)
                current.add(event.toIndex, item)
                _uiState.update { it.copy(smartQueue = current, isQueueUserOrdered = true) }
                djSessionManager.updateSmartQueue(current)
                updateNextTrackPreview()
            }

            is DjMixEvent.RemoveTrack -> {
                val id = event.audioFile.id
                // Don't allow removing the currently playing track
                if (id == _uiState.value.currentTrack?.id) {
                    Log.d(TAG, "RemoveTrack: ignored — cannot remove currently playing track")
                    return
                }
                rawPlaylistSongs = rawPlaylistSongs.filter { it.id != id }
                val updated = _uiState.value.smartQueue.filter { it.id != id }
                _uiState.update { it.copy(smartQueue = updated, isQueueUserOrdered = true) }
                djSessionManager.updateSmartQueue(updated)
                updateNextTrackPreview()
                Log.d(TAG, "RemoveTrack: removed '${event.audioFile.title}' from session queue")
            }

            // ── Existing events (unchanged) ───────────────────────────────────
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
                syncBeatGridForTrack(event.audioFile.id)
                crossfadeEngine.startPlayback(event.audioFile)
                _uiState.update { it.copy(currentTrack = event.audioFile) }
                if (!djSessionManager.isSessionActive.value) {
                    djSessionManager.startSession(playlistId)
                    activePlayerRegistry.onDjMixStarted()
                }
                viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
                updateNextTrackPreview()
            }

            DjMixEvent.AbortCrossfade -> crossfadeEngine.abortCurrentCrossfade()
        }
    }

    // ── Next track preview ────────────────────────────────────────────────────

    private fun updateNextTrackPreview() {
        val currentId = _uiState.value.currentTrack?.id
        if (currentId == null) {
            _uiState.update { it.copy(nextTrack = null) }
            return
        }
        val next = djSessionManager.selectNextTrack(currentId)
        _uiState.update { it.copy(nextTrack = next) }
        Log.d(TAG, "nextTrack preview: '${next?.title}'")
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
                    it.copy(playlistName = playlist.name, totalSongs = songs.size, isLoading = false)
                }
                observeBpmCache(songs)
            }
            .launchIn(viewModelScope)
    }

    private fun observeBpmCache(songs: List<AudioFile>) {
        djMixRepository.getBpmCacheFlow()
            .onEach { bpmCache ->
                val doneCount    = songs.count { bpmCache.containsKey(it.id) }
                val failedCount  = bpmCache.values.count { it.analysisFailed }
                val progress     = if (songs.isEmpty()) 1f else doneCount.toFloat() / songs.size
                val stillAnalysing = progress < 1f

                val currentTrackId = _uiState.value.currentTrack?.id
                if (currentTrackId != null) {
                    val freshBpmInfo  = bpmCache[currentTrackId]
                    val cachedBpmInfo = _uiState.value.bpmCache[currentTrackId]

                    // Detect if new analysis arrived OR if the user just updated the cue points
                    val isNewlyAnalyzed = freshBpmInfo != null && cachedBpmInfo == null && !freshBpmInfo.analysisFailed
                    val cuePointsChanged = freshBpmInfo != null && cachedBpmInfo != null &&
                            (freshBpmInfo.customCueInMs != cachedBpmInfo.customCueInMs ||
                                    freshBpmInfo.customMixOutMs != cachedBpmInfo.customMixOutMs)

                    if (isNewlyAnalyzed || cuePointsChanged) {
                        crossfadeEngine.updateCurrentBpmInfo(
                            bpm              = freshBpmInfo!!.bpm,
                            firstBeatMs      = freshBpmInfo.customCueInMs ?: freshBpmInfo.firstBeatMs,
                            amplitude        = freshBpmInfo.amplitude,
                            waveformEnvelope = freshBpmInfo.waveformEnvelope,
                            mixOutMs         = freshBpmInfo.customMixOutMs
                        )
                        if (cuePointsChanged) Log.d(TAG, "Beat grid dynamically updated due to Custom Cue change.")
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

                // Refresh next-track preview whenever the current track changes
                if (newTrackId != null && newTrackId != prevTrackId) {
                    updateNextTrackPreview()
                }
            }
            .launchIn(viewModelScope)
    }

    private fun syncBeatGridForTrack(trackId: Long) {
        val bpmInfo = _uiState.value.bpmCache[trackId] ?: return
        if (bpmInfo.analysisFailed) return

        // Pass the custom overrides into the engine
        crossfadeEngine.updateCurrentBpmInfo(
            bpm              = bpmInfo.bpm,
            firstBeatMs      = bpmInfo.customCueInMs ?: bpmInfo.firstBeatMs,
            amplitude        = bpmInfo.amplitude,
            waveformEnvelope = bpmInfo.waveformEnvelope,
            mixOutMs         = bpmInfo.customMixOutMs
        )
        Log.d(TAG, "Beat grid synced: trackId=$trackId BPM=${bpmInfo.bpm} MixOut=${bpmInfo.customMixOutMs}")
    }

    private fun rebuildSmartQueue(
        bpmCache: Map<Long, BpmInfo> = _uiState.value.bpmCache
    ) {
        if (_uiState.value.isQueueUserOrdered) {
            Log.d(TAG, "rebuildSmartQueue: skipped — user has manually ordered the queue")
            return
        }
        if (rawPlaylistSongs.isEmpty()) return
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(300L)
            performRebuild(bpmCache)
            // Refresh next-track preview after every rebuild since queue order changed
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
        Log.d(TAG, "performRebuild: Opener '${opener.title}' @ ${openerBpm.fmt()} BPM")

        val recentBpms = ArrayDeque<Float>(4)
        if (openerBpm > 0f) recentBpms.addLast(openerBpm)

        while (remaining.isNotEmpty()) {
            val lastInfo = bpmCache[result.last().id]
            val lastBpm = if (lastInfo?.analysisFailed == true) 120f else lastInfo?.bpm ?: 120f

            val next = getSmartNextTrackUseCase(
                currentBpm          = lastBpm,
                remainingQueue      = remaining,
                bpmCache            = bpmCache.mapValues {
                    if (it.value.analysisFailed) 120f else it.value.bpm
                },
                tolerance           = tolerance,
                recentBpms          = recentBpms.toList()
                // Removed setProgressFraction to sync with updated use case
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
        Log.d(TAG, "performRebuild: ${result.size} tracks. Arc: ${result.joinToString(" → ") {
            if (bpmCache[it.id]?.analysisFailed == true) "FAIL"
            else bpmCache[it.id]?.bpm?.fmt() ?: "??"
        }}")
    }

    private fun selectOpener(songs: List<AudioFile>, bpmCache: Map<Long, BpmInfo>): AudioFile {
        val withBpm = songs.filter { bpmCache.containsKey(it.id) && bpmCache[it.id]?.analysisFailed != true }
        if (withBpm.isEmpty()) return songs.first()

        val sorted   = withBpm.sortedBy { bpmCache[it.id]!!.bpm }
        val n        = sorted.size
        val lowerIdx = (n * 0.20).toInt().coerceIn(0, n - 1)
        val upperIdx = (n * 0.40).toInt().coerceIn(lowerIdx, n - 1)
        val targetIdx = (lowerIdx + upperIdx) / 2

        return sorted.getOrNull(targetIdx)
            ?: sorted.getOrNull(n / 2)
            ?: songs.filterNot { bpmCache.containsKey(it.id) }.firstOrNull()
            ?: songs.first()
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared — engine and session running in background")
    }

    private fun Float.fmt() = String.format("%.1f", this)
}