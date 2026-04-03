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

private const val TAG = "DjMixViewModel"

/**
 * 🚨 ARCHITECTURE NOTE 🚨
 * This ViewModel acts as the supreme orchestrator for the DJ Mix session.
 * It coordinates the Hardware (CrossfadeEngine), the Audio FX (SamplerEngine),
 * and the State/History (DjSessionManager).
 *
 * - Handles the "Dead End" queue restart logic.
 * - Handles "Cross-Playlist Navigation" auto-starts.
 * - Handles "Analysis in Progress" dialog and deferred auto-start.
 *
 * ── Analysis-in-progress flow ─────────────────────────────────────────────────
 *
 * When the user taps START MIX while BPM analysis is still running:
 *
 * 1. [PlayPause] is intercepted → [showAnalysisDialog] = true (no mix start yet).
 * 2a. User picks "Auto-Start When Ready" → [pendingAutoStartAfterAnalysis] = true.
 *     As soon as [analysisProgress] hits 1.0, [performRebuild] fires [startFreshMixSession].
 * 2b. User picks "Start Now" → [startFreshMixSession] fires immediately.
 *     Unanalysed tracks fall back to natural playlist order in the queue.
 * 2c. User dismisses (back / outside tap) → dialog closes, nothing changes.
 *
 * If the user taps START MIX a second time while [pendingAutoStartAfterAnalysis] is true,
 * the pending wait is cancelled and the mix starts immediately — consistent with the
 * user signalling "I changed my mind, just go".
 *
 * ── Sampler suppression rule ──────────────────────────────────────────────────
 * samplerEngine.isAutoSamplerEnabled is driven by:
 *   settings.autoSamplerEnabled AND settings.isRealMixMode
 *
 * When Auto-Mix (isRealMixMode) is OFF the sampler is always silent — there are
 * no DJ-strategy lifecycle events to attach sound effects to in Continuous Play mode.
 * The user's autoSamplerEnabled preference is never mutated — it is restored as soon
 * as isRealMixMode is switched back ON.
 *
 * This logic is mirrored in DjMixService so the sampler stays suppressed even when
 * the user navigates away from the DJ screen and the ViewModel is cleared.
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

    /** Set to true when cross-playlist navigation requires an auto-start after queue rebuild. */
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

                // ── DEAD END DETECTION ────────────────────────────────────────
                val isAtEndOfTrack = engineState.currentDurationMs > 0L &&
                        (engineState.currentDurationMs - engineState.currentPositionMs) <= 500L
                val isQueueExhausted = engineState.currentTrack != null &&
                        djSessionManager.selectNextTrack(engineState.currentTrack.id) == null
                val isMixFinished = !engineState.isPlaying && isAtEndOfTrack && isQueueExhausted

                if (!crossfadeEngine.isActive || engineState.currentTrack == null || isMixFinished) {

                    // ── ANALYSIS GUARD ────────────────────────────────────────
                    // Analysis is still running AND the user hasn't already committed
                    // to waiting (pendingAutoStartAfterAnalysis) → intercept with dialog.
                    //
                    // If pendingAutoStartAfterAnalysis IS true and the user taps the FAB
                    // again, they're signalling "just go now" — clear the pending wait
                    // and fall through to startFreshMixSession immediately.
                    val analysisStillRunning = _uiState.value.isAnalyzing
                    val alreadyWaiting       = _uiState.value.pendingAutoStartAfterAnalysis

                    if (analysisStillRunning && !alreadyWaiting) {
                        Log.d(TAG, "[ANALYSIS] Analysis in progress — showing confirmation dialog")
                        _uiState.update { it.copy(showAnalysisDialog = true) }
                        return
                    }

                    // Cancel any pending auto-start (user tapped again while waiting)
                    if (alreadyWaiting) {
                        Log.d(TAG, "[ANALYSIS] User re-tapped while waiting — starting immediately")
                        _uiState.update { it.copy(pendingAutoStartAfterAnalysis = false) }
                    }

                    startFreshMixSession()

                } else {
                    // ── NORMAL PAUSE / RESUME ─────────────────────────────────
                    crossfadeEngine.playPause()
                }
            }

            // ── Mix controls ──────────────────────────────────────────────────
            MixStudioEvent.MixStudioNow        -> crossfadeEngine.triggerMixNow()
            MixStudioEvent.AbortCrossfade -> crossfadeEngine.abortCurrentCrossfade()

            // ── Analysis-in-progress dialog actions ───────────────────────────

            MixStudioEvent.DismissAnalysisDialog -> {
                // User dismissed via back/outside tap — close dialog, do nothing else.
                Log.d(TAG, "[ANALYSIS] Dialog dismissed — no action taken")
                _uiState.update { it.copy(showAnalysisDialog = false) }
            }

            MixStudioEvent.WaitAndAutoStart -> {
                // User wants to wait. Schedule auto-start for when analysis completes.
                // The trigger fires inside performRebuild once isAnalyzing becomes false.
                Log.i(TAG, "[ANALYSIS] User chose to wait — auto-start armed for analysis completion")
                _uiState.update {
                    it.copy(
                        showAnalysisDialog           = false,
                        pendingAutoStartAfterAnalysis = true
                    )
                }
            }

            MixStudioEvent.StartAnywayDespiteAnalysis -> {
                // User is happy to start with partial BPM data. Unanalysed tracks will
                // fall back to natural playlist order in the queue (already handled by
                // performRebuild / GetSmartNextTrackUseCase graceful degradation path).
                Log.i(TAG, "[ANALYSIS] User chose Start Now — starting with partial BPM data")
                _uiState.update {
                    it.copy(
                        showAnalysisDialog           = false,
                        pendingAutoStartAfterAnalysis = false
                    )
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
                Log.d(TAG, "[SETTINGS] ToggleRealMixMode=${event.enabled} " +
                        "effectiveSamplerEnabled=${s.autoSamplerEnabled && event.enabled}")
            }

            is MixStudioEvent.ToggleAutoSampler -> {
                val s = _uiState.value.settings.copy(autoSamplerEnabled = event.enabled)
                samplerEngine.isAutoSamplerEnabled = event.enabled && s.isRealMixMode
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjAutoSampler(event.enabled) }
                Log.d(TAG, "[SETTINGS] ToggleAutoSampler=${event.enabled} " +
                        "effectiveSamplerEnabled=${event.enabled && s.isRealMixMode}")
            }

            is MixStudioEvent.UpdateSampleVolume -> {
                val s = _uiState.value.settings.copy(sampleVolume = event.volume)
                samplerEngine.sampleVolume = event.volume
                _uiState.update { it.copy(settings = s) }
                djSessionManager.updateSettings(s)
                viewModelScope.launch { settingsRepository.updateDjSampleVolume(event.volume) }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FRESH MIX SESSION  (extracted so it can be called from multiple paths)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Starts a brand-new mix session from the top of the smart queue.
     *
     * Called from:
     * - [MixStudioEvent.PlayPause] (initial start or restart after finish)
     * - [MixStudioEvent.StartAnywayDespiteAnalysis] (user skips analysis wait)
     * - [performRebuild] (auto-start after analysis completes via "Wait" path)
     */
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

        // samplerEngine.isAutoSamplerEnabled already reflects the correct value
        // (false when Auto-Mix is OFF) so onSessionStarted is a safe no-op in that case.
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
                val currentSettings = _uiState.value.settings
                val newSettings = MixStudioSettings(
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
                samplerEngine.isAutoSamplerEnabled  = newSettings.autoSamplerEnabled && newSettings.isRealMixMode
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

                // ── CROSS-PLAYLIST NAVIGATION FIX ────────────────────────────
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
        autoMixRepository.getBpmCacheFlow()
            .onEach { bpmCache ->
                val doneCount      = songs.count { bpmCache.containsKey(it.id) }
                val failedCount    = songs.count { bpmCache[it.id]?.analysisFailed == true }
                val progress       = if (songs.isEmpty()) 1f else doneCount.toFloat() / songs.size
                val stillAnalysing = progress < 1f

                // ── Push fresh BPM data into the engine for the current track ─
                val currentTrackId = _uiState.value.currentTrack?.id
                if (currentTrackId != null) {
                    val freshBpmInfo  = bpmCache[currentTrackId]
                    val cachedBpmInfo = _uiState.value.bpmCache[currentTrackId]
                    val isNewlyAnalyzed = freshBpmInfo != null &&
                            cachedBpmInfo == null &&
                            !freshBpmInfo.analysisFailed

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

                // rebuildSmartQueue has a 300 ms debounce. The auto-start check inside
                // performRebuild fires only when !isAnalyzing — which is guaranteed to
                // be true by the state update above before rebuildSmartQueue is called.
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

    // ═════════════════════════════════════════════════════════════════════════
    // QUEUE REBUILD
    // ═════════════════════════════════════════════════════════════════════════

    private fun rebuildSmartQueue(
        bpmCache: Map<Long, BpmInfo> = _uiState.value.bpmCache
    ) {
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
            ?.bpm
            ?.takeIf { bpmCache[opener.id]?.analysisFailed != true }
            ?: 120f
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

            val nextBpm = bpmCache[next.id]
                ?.bpm
                ?.takeIf { bpmCache[next.id]?.analysisFailed != true }
            if (nextBpm != null && nextBpm > 0f) {
                recentBpms.addLast(nextBpm)
                if (recentBpms.size > 3) recentBpms.removeFirst()
            }
        }

        _uiState.update { it.copy(smartQueue = result) }
        djSessionManager.updateSmartQueue(result)

        // ── Cross-playlist navigation auto-start (existing) ───────────────────
        if (pendingAutoStart && result.isNotEmpty()) {
            pendingAutoStart = false
            Log.i(TAG, "[LIFECYCLE] Queue built — auto-starting new playlist mix.")
            onEvent(MixStudioEvent.PlayPause)
        }

        // ── Analysis-completion auto-start ("Wait & Auto-Start" path) ─────────
        //
        // Conditions checked in order:
        //  1. pendingAutoStartAfterAnalysis — user explicitly asked to wait
        //  2. !isAnalyzing — analysis is fully complete (set in observeBpmCache
        //     before rebuildSmartQueue is called, so always true here on the
        //     final cache emission)
        //  3. currentTrack == null — engine isn't already running (guard against
        //     double-start if a mix is somehow already in progress)
        //  4. result.isNotEmpty() — there's something to play
        //
        // We check _uiState.value rather than a local snapshot so we always read
        // the most recent state written by observeBpmCache's update call.
        val state = _uiState.value
        if (state.pendingAutoStartAfterAnalysis
            && !state.isAnalyzing
            && state.currentTrack == null
            && result.isNotEmpty()
        ) {
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