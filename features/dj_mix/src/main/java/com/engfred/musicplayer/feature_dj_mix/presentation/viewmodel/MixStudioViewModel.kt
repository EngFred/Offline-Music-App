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
import com.engfred.musicplayer.feature_dj_mix.domain.model.CUE_POINT_OPTIONS_SEC
import com.engfred.musicplayer.feature_dj_mix.domain.model.MixStudioSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo
import com.engfred.musicplayer.feature_dj_mix.domain.repository.AutoMixRepository
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

private const val TAG = "MixStudioViewModel"

/**
 * ViewModel / orchestrator for the DJ Mix session.
 *
 * Coordinates the CrossfadeEngine (hardware), SamplerEngine (audio FX),
 * and DjSessionManager (state/history).
 *
 * ── Cue-point setting flow ────────────────────────────────────────────────────
 *
 * The cue-point offset (how far into the track the incoming beat is placed)
 * is user-configurable from 0–30 s in steps defined by [CUE_POINT_OPTIONS_SEC].
 *
 * 1. User selects a new cue point in the UI → dispatches [MixStudioEvent.UpdateCuePointOffset].
 * 2. ViewModel updates [CrossfadeEngine.cuePointOffsetMs] immediately (no re-analysis).
 * 3. Current track's beat grid is re-synced via [syncBeatGridForTrack] so the
 *    ongoing mix-trigger recalculates with the new offset.
 * 4. Setting is persisted via [SettingsRepository.updateDjCuePointOffset].
 *
 * On app restart, [observeSettings] reads the persisted value and pushes it into
 * the engine before any playback begins, so the correct offset is in effect from
 * the first track.
 *
 * ── Why no re-analysis is needed ─────────────────────────────────────────────
 * [BpmInfo.firstBeatMs] now stores the RAW aubio beat-0 (pre-guard).
 * [CrossfadeEngine.applyFirstBeatGuard] applies the offset dynamically every time
 * firstBeatMs enters the engine, so changing the setting takes effect for all
 * tracks instantly, even those already cached.
 *
 * ── Analysis-in-progress flow ────────────────────────────────────────────────
 * (unchanged from previous version — see inline comments below)
 *
 * ── Sampler suppression rule ──────────────────────────────────────────────────
 * samplerEngine.isAutoSamplerEnabled = settings.autoSamplerEnabled AND settings.isRealMixMode
 */
@UnstableApi
@HiltViewModel
class MixStudioViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val autoMixRepository: AutoMixRepository,
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

    private val _uiState = MutableStateFlow(MixStudioUiState())
    val uiState: StateFlow<MixStudioUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private var rawPlaylistSongs: List<AudioFile> = emptyList()
    private var rebuildJob: Job? = null

    private var pendingAutoStart = false

    init {
        crossfadeEngine.initialize()
        observeSettings()
        observeCrossfadeEngineState()
        observeCanSkipBack()
        observePlayedTracks()
        loadPlaylist()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EVENT HANDLER
    // ═════════════════════════════════════════════════════════════════════════

    fun onEvent(event: MixStudioEvent) {
        when (event) {

            // ── Play / Pause ──────────────────────────────────────────────────
            MixStudioEvent.PlayPause -> {
                val engineState = crossfadeEngine.state.value

                val isAtEndOfTrack = engineState.currentDurationMs > 0L &&
                        (engineState.currentDurationMs - engineState.currentPositionMs) <= 500L
                val isQueueExhausted = engineState.currentTrack != null &&
                        djSessionManager.selectNextTrack(engineState.currentTrack.id) == null
                val isMixFinished = !engineState.isPlaying && isAtEndOfTrack && isQueueExhausted

                if (!crossfadeEngine.isActive || engineState.currentTrack == null || isMixFinished) {

                    val analysisStillRunning = _uiState.value.isAnalyzing
                    val alreadyWaiting       = _uiState.value.pendingAutoStartAfterAnalysis

                    if (analysisStillRunning && !alreadyWaiting) {
                        Log.d(TAG, "[ANALYSIS] Analysis in progress — showing confirmation dialog")
                        _uiState.update { it.copy(showAnalysisDialog = true) }
                        return
                    }

                    if (alreadyWaiting) {
                        Log.d(TAG, "[ANALYSIS] User re-tapped while waiting — starting immediately")
                        _uiState.update { it.copy(pendingAutoStartAfterAnalysis = false) }
                    }

                    startFreshMixSession()

                } else {
                    crossfadeEngine.playPause()
                }
            }

            // ── Mix controls ──────────────────────────────────────────────────
            MixStudioEvent.MixStudioNow   -> crossfadeEngine.triggerMixNow()
            MixStudioEvent.AbortCrossfade -> crossfadeEngine.abortCurrentCrossfade()

            // ── Analysis-in-progress dialog ───────────────────────────────────
            MixStudioEvent.DismissAnalysisDialog -> {
                Log.d(TAG, "[ANALYSIS] Dialog dismissed — no action taken")
                _uiState.update { it.copy(showAnalysisDialog = false) }
            }

            MixStudioEvent.WaitAndAutoStart -> {
                Log.i(TAG, "[ANALYSIS] User chose to wait — auto-start armed for analysis completion")
                _uiState.update {
                    it.copy(showAnalysisDialog = false, pendingAutoStartAfterAnalysis = true)
                }
            }

            MixStudioEvent.StartAnywayDespiteAnalysis -> {
                Log.i(TAG, "[ANALYSIS] User chose Start Now — starting with partial BPM data")
                _uiState.update {
                    it.copy(showAnalysisDialog = false, pendingAutoStartAfterAnalysis = false)
                }
                startFreshMixSession()
            }

            // ── Settings ──────────────────────────────────────────────────────
            is MixStudioEvent.ToggleRealMixStudioMode -> {
                val s = _uiState.value.settings.copy(isRealMixMode = event.enabled)
                crossfadeEngine.isRealMixMode = event.enabled
                samplerEngine.isAutoSamplerEnabled = s.autoSamplerEnabled && event.enabled
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjRealMixMode(event.enabled) }
                Log.d(TAG, "[SETTINGS] ToggleRealMixMode=${event.enabled}")
            }

            is MixStudioEvent.ToggleAutoSampler -> {
                val s = _uiState.value.settings.copy(autoSamplerEnabled = event.enabled)
                samplerEngine.isAutoSamplerEnabled = event.enabled && s.isRealMixMode
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjAutoSampler(event.enabled) }
            }

            is MixStudioEvent.UpdateSampleVolume -> {
                val s = _uiState.value.settings.copy(sampleVolume = event.volume)
                samplerEngine.sampleVolume = event.volume
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjSampleVolume(event.volume) }
            }

            // ── Cue point ─────────────────────────────────────────────────────
            is MixStudioEvent.UpdateCuePointOffset -> {
                // Validate the incoming value against the approved options list.
                // Silently clamp to nearest valid option rather than throwing, so a
                // stale UI state or a bad deep-link can never put the engine into an
                // undefined state.
                val clampedSec = CUE_POINT_OPTIONS_SEC
                    .minByOrNull { kotlin.math.abs(it - event.sec) }
                    ?: event.sec

                if (clampedSec != event.sec) {
                    Log.w(TAG, "[SETTINGS] CuePointOffset ${event.sec}s not in options list — " +
                            "clamped to ${clampedSec}s")
                }

                val s = _uiState.value.settings.copy(cuePointOffsetSec = clampedSec)

                // ── 1. Push into engine immediately (takes effect on next guard call) ──
                crossfadeEngine.cuePointOffsetMs = clampedSec * 1000L

                // ── 2. Re-sync the current track's beat grid so the mix trigger
                //       recalculates with the new offset right away, without waiting
                //       for the next BPM-cache emission. ───────────────────────────────
                _uiState.value.currentTrack?.id?.let { syncBeatGridForTrack(it) }

                // ── 3. Update state and persist ──────────────────────────────────────
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch {
                    settingsRepository.updateDjCuePointOffset(clampedSec)
                }

                Log.d(TAG, "[SETTINGS] CuePointOffset → ${clampedSec}s " +
                        "(${clampedSec * 1000}ms). " +
                        "Mix trigger for current track recalculated.")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FRESH MIX SESSION
    // ═════════════════════════════════════════════════════════════════════════

    private fun startFreshMixSession() {
        Log.i(TAG, "[PLAYBACK] Starting fresh mix session")

        crossfadeEngine.initialize()
        djSessionManager.startSession(playlistId)
        djSessionManager.resetPlayHistory()

        val firstTrack = _uiState.value.smartQueue.firstOrNull() ?: run {
            Log.w(TAG, "[PLAYBACK] Smart queue is empty — cannot start session")
            return
        }

        djSessionManager.markTrackPlayed(firstTrack.id)
        activePlayerRegistry.onDjMixStarted()
        syncBeatGridForTrack(firstTrack.id)
        crossfadeEngine.startPlayback(firstTrack)

        samplerEngine.onSessionStarted()

        _uiState.update { it.copy(currentTrack = firstTrack) }
        viewModelScope.launch { _uiEvent.emit("START_DJ_SERVICE") }
        updateNextTrackPreview()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun updateNextTrackPreview() {
        val currentId = _uiState.value.currentTrack?.id
        if (currentId == null) {
            _uiState.update { it.copy(nextTrack = null) }
            return
        }
        val next = djSessionManager.selectNextTrack(currentId)
        _uiState.update { it.copy(nextTrack = next) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OBSERVERS
    // ═════════════════════════════════════════════════════════════════════════

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
                val newSettings = MixStudioSettings(
                    crossfadeDurationSec = appSettings.crossfadeDurationSec,
                    bpmTolerance         = appSettings.bpmTolerance,
                    isRealMixMode        = appSettings.isRealMixMode,
                    maxTrackDurationSec  = appSettings.maxTrackDurationSec,
                    loopQueue            = appSettings.loopQueue,
                    useManualMaxDuration = appSettings.useManualMaxDuration,
                    autoSamplerEnabled   = appSettings.autoSamplerEnabled,
                    sampleVolume         = appSettings.sampleVolume,
                    cuePointOffsetSec    = appSettings.cuePointOffsetSec,
                )

                crossfadeEngine.crossfadeDurationMs = newSettings.crossfadeDurationSec * 1000L
                crossfadeEngine.isRealMixMode       = newSettings.isRealMixMode
                crossfadeEngine.maxTrackDurationMs  = newSettings.maxTrackDurationSec * 1000L
                crossfadeEngine.useHalfwayMix       = !newSettings.useManualMaxDuration
                // Push the persisted cue-point offset into the engine on every settings
                // emission, including the very first one at startup. This ensures the
                // engine has the correct value before any playback begins.
                crossfadeEngine.cuePointOffsetMs    = newSettings.cuePointOffsetSec * 1000L
                samplerEngine.isAutoSamplerEnabled  = newSettings.autoSamplerEnabled && newSettings.isRealMixMode
                samplerEngine.sampleVolume          = newSettings.sampleVolume

                val toleranceChanged = _uiState.value.settings.bpmTolerance != newSettings.bpmTolerance
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

                val activeId = djSessionManager.activePlaylistId
                if (djSessionManager.isSessionActive.value && activeId != null && activeId != playlistId) {
                    Log.i(TAG, "[LIFECYCLE] Different playlist detected ($playlistId). " +
                            "Killing old session ($activeId) & Auto-Starting new.")
                    samplerEngine.stopAllSamples()
                    crossfadeEngine.release()
                    djSessionManager.endSession()
                    crossfadeEngine.initialize()
                    pendingAutoStart = true
                }

                val songs = playlist.songs
                rawPlaylistSongs = songs

                val sessionAlreadyActive = djSessionManager.isSessionActive.value &&
                        djSessionManager.activePlaylistId == playlistId

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
        autoMixRepository.getBpmCacheFlow()
            .onEach { bpmCache ->
                val doneCount      = songs.count { bpmCache.containsKey(it.id) }
                val failedCount    = songs.count { bpmCache[it.id]?.analysisFailed == true }
                val progress       = if (songs.isEmpty()) 1f else doneCount.toFloat() / songs.size
                val stillAnalysing = progress < 1f

                // Push fresh BPM data into the engine for the current track.
                // Only do this if the track was just newly analysed (cache miss → hit)
                // so we don't reset the guarded firstBeatMs unnecessarily.
                val currentTrackId = _uiState.value.currentTrack?.id
                if (currentTrackId != null) {
                    val freshBpmInfo  = bpmCache[currentTrackId]
                    val cachedBpmInfo = _uiState.value.bpmCache[currentTrackId]
                    val isNewlyAnalyzed = freshBpmInfo != null &&
                            cachedBpmInfo == null &&
                            !freshBpmInfo.analysisFailed

                    if (isNewlyAnalyzed) {
                        // Pass the raw firstBeatMs — CrossfadeEngine applies the guard.
                        crossfadeEngine.updateCurrentBpmInfo(
                            bpm              = freshBpmInfo!!.bpm,
                            rawFirstBeatMs   = freshBpmInfo.firstBeatMs,
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

    /**
     * Syncs the beat-grid metadata for [trackId] into the CrossfadeEngine.
     *
     * Passes the RAW [BpmInfo.firstBeatMs] — the engine applies the cue-point
     * guard via [CrossfadeEngine.applyFirstBeatGuard] using the current
     * [CrossfadeEngine.cuePointOffsetMs]. This is the correct call site for
     * guard application; do NOT pre-guard the value here.
     *
     * This is called:
     *   • When a new track starts playing (from observeCrossfadeEngineState).
     *   • When the cue-point setting changes (from onEvent/UpdateCuePointOffset)
     *     so the mix-trigger formula recalculates immediately.
     *   • When a track's BPM result arrives for the first time (observeBpmCache).
     */
    private fun syncBeatGridForTrack(trackId: Long) {
        val bpmInfo = _uiState.value.bpmCache[trackId] ?: return
        if (bpmInfo.analysisFailed) return
        crossfadeEngine.updateCurrentBpmInfo(
            bpm              = bpmInfo.bpm,
            rawFirstBeatMs   = bpmInfo.firstBeatMs, // raw — engine guards it
            amplitude        = bpmInfo.amplitude,
            waveformEnvelope = bpmInfo.waveformEnvelope,
            mixOutMs         = null
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // QUEUE REBUILD
    // ═════════════════════════════════════════════════════════════════════════

    private fun rebuildSmartQueue(bpmCache: Map<Long, BpmInfo> = _uiState.value.bpmCache) {
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

        val openerBpm = bpmCache[opener.id]
            ?.bpm?.takeIf { bpmCache[opener.id]?.analysisFailed != true } ?: 120f
        val recentBpms = ArrayDeque<Float>(4)
        if (openerBpm > 0f) recentBpms.addLast(openerBpm)

        while (remaining.isNotEmpty()) {
            val lastInfo = bpmCache[result.last().id]
            val lastBpm  = if (lastInfo?.analysisFailed == true) 120f else lastInfo?.bpm ?: 120f

            val next = getSmartNextTrackUseCase(
                currentBpm     = lastBpm,
                remainingQueue = remaining,
                bpmCache       = bpmCache.mapValues {
                    if (it.value.analysisFailed) 120f else it.value.bpm
                },
                tolerance      = tolerance,
                recentBpms     = recentBpms.toList()
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

        if (pendingAutoStart && result.isNotEmpty()) {
            pendingAutoStart = false
            Log.i(TAG, "[LIFECYCLE] Queue built — auto-starting new playlist mix.")
            onEvent(MixStudioEvent.PlayPause)
        }

        val state = _uiState.value
        if (state.pendingAutoStartAfterAnalysis && !state.isAnalyzing
            && state.currentTrack == null && result.isNotEmpty()) {
            Log.i(TAG, "[ANALYSIS] Analysis complete — auto-starting mix (user chose 'Wait')")
            _uiState.update { it.copy(pendingAutoStartAfterAnalysis = false) }
            startFreshMixSession()
        }
    }

    private fun selectOpener(songs: List<AudioFile>, bpmCache: Map<Long, BpmInfo>): AudioFile {
        val withBpm = songs.filter {
            bpmCache.containsKey(it.id) && bpmCache[it.id]?.analysisFailed != true
        }
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