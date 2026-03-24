package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
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
import kotlin.math.abs

// ── Nav argument key ──────────────────────────────────────────────────────────

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
    private val analyzeBpmUseCase: AnalyzeBpmUseCase,
    private val getSmartNextTrackUseCase: GetSmartNextTrackUseCase,
    val crossfadeEngine: CrossfadeEngine // exposed so the Screen can init on Main thread
) : ViewModel() {

    private val playlistId: Long =
        checkNotNull(savedStateHandle[DjMixArgs.PLAYLIST_ID]) {
            "DjMixViewModel requires a playlistId in SavedStateHandle"
        }

    private val _uiState = MutableStateFlow(DjMixUiState())
    val uiState: StateFlow<DjMixUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    /** Local copy of the original (unordered) playlist songs for queue rebuilding. */
    private var rawPlaylistSongs: List<AudioFile> = emptyList()

    /** * Keeps track of which songs have been played in the current DJ session to
     * prevent the engine from looping between the same two tracks.
     */
    private val playedTrackIds = mutableSetOf<Long>()

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        crossfadeEngine.initialize()
        observeCrossfadeEngineState()
        observeNextTrackRequests()
        loadPlaylist()
    }

    // ── Event handler ─────────────────────────────────────────────────────────

    fun onEvent(event: DjMixEvent) {
        when (event) {
            DjMixEvent.PlayPause -> crossfadeEngine.playPause()

            is DjMixEvent.UpdateCrossfadeDuration -> {
                val newSettings = _uiState.value.settings.copy(crossfadeDurationSec = event.seconds)
                crossfadeEngine.crossfadeDurationMs = event.seconds * 1000L
                _uiState.update { it.copy(settings = newSettings) }
            }

            is DjMixEvent.UpdateBpmTolerance -> {
                val newSettings = _uiState.value.settings.copy(bpmTolerance = event.tolerance)
                _uiState.update { it.copy(settings = newSettings) }
                rebuildSmartQueue()
            }

            is DjMixEvent.ToggleRealMixMode -> {
                val newSettings = _uiState.value.settings.copy(isRealMixMode = event.enabled)
                crossfadeEngine.isRealMixMode = event.enabled
                _uiState.update { it.copy(settings = newSettings) }
            }

            is DjMixEvent.UpdateMaxTrackDuration -> {
                val newSettings = _uiState.value.settings.copy(maxTrackDurationSec = event.seconds)
                crossfadeEngine.maxTrackDurationMs = event.seconds * 1000L
                _uiState.update { it.copy(settings = newSettings) }
            }

            is DjMixEvent.ToggleLoopQueue -> {
                val newSettings = _uiState.value.settings.copy(loopQueue = event.enabled)
                _uiState.update { it.copy(settings = newSettings) }
            }

            is DjMixEvent.JumpToTrack -> {
                playedTrackIds.clear() // Reset history when user manually picks a starting point
                playedTrackIds.add(event.audioFile.id)
                crossfadeEngine.startPlayback(event.audioFile)
                _uiState.update { it.copy(currentTrack = event.audioFile) }
                // Immediately sync the beat grid for the manually selected track
                syncBeatGridForTrack(event.audioFile.id)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadPlaylist() {
        playlistRepository.getPlaylistById(playlistId)
            .onEach { playlist ->
                if (playlist == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Playlist not found.") }
                    return@onEach
                }

                val songs = playlist.songs
                rawPlaylistSongs = songs

                // Start BPM analysis for any uncached songs
                if (songs.isNotEmpty()) {
                    _uiState.update { it.copy(isAnalyzing = true) }
                    analyzeBpmUseCase(playlistId, songs)
                }

                _uiState.update {
                    it.copy(
                        playlistName = playlist.name,
                        totalSongs = songs.size,
                        isLoading = false
                    )
                }

                // Observe BPM cache and merge with playlist songs
                observeBpmCache(songs)
            }
            .launchIn(viewModelScope)
    }

    private fun observeBpmCache(songs: List<AudioFile>) {
        djMixRepository.getBpmCacheFlow()
            .onEach { bpmCache ->
                val analysedCount = songs.count { bpmCache.containsKey(it.id) }
                val progress      = if (songs.isEmpty()) 1f else analysedCount.toFloat() / songs.size
                val stillAnalysing = progress < 1f

                // If BPM data just became available for the currently playing track
                // (i.e., analysis completed mid-playback), immediately push the new beat
                // grid to the engine so upcoming crossfades use accurate beat alignment.
                val currentTrackId = _uiState.value.currentTrack?.id
                if (currentTrackId != null) {
                    val freshBpmInfo   = bpmCache[currentTrackId]
                    val cachedBpmInfo  = _uiState.value.bpmCache[currentTrackId]
                    if (freshBpmInfo != null && cachedBpmInfo == null) {
                        crossfadeEngine.updateCurrentBpmInfo(
                            freshBpmInfo.bpm,
                            freshBpmInfo.firstBeatMs
                        )
                        Log.d(
                            TAG,
                            "Beat grid updated mid-playback for track $currentTrackId: " +
                                    "${freshBpmInfo.bpm} BPM firstBeat=${freshBpmInfo.firstBeatMs}ms"
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        bpmCache       = bpmCache,
                        analysisProgress = progress,
                        isAnalyzing    = stillAnalysing
                    )
                }

                rebuildSmartQueue(bpmCache = bpmCache)
            }
            .launchIn(viewModelScope)
    }

    private fun observeCrossfadeEngineState() {
        crossfadeEngine.state
            .onEach { engineState ->
                // When the current track changes, push its BPM data to the engine so
                // beat-aligned transitions use the correct beat grid going forward.
                val prevTrackId = _uiState.value.currentTrack?.id
                val newTrackId  = engineState.currentTrack?.id
                if (newTrackId != null && newTrackId != prevTrackId) {
                    playedTrackIds.add(newTrackId) // Track has officially started playing
                    syncBeatGridForTrack(newTrackId)
                }

                _uiState.update {
                    it.copy(
                        currentTrack             = engineState.currentTrack,
                        isPlaying                = engineState.isPlaying,
                        isCrossfading            = engineState.isCrossfading,
                        currentPositionMs        = engineState.currentPositionMs,
                        currentDurationMs        = engineState.currentDurationMs,
                        crossfadeProgressFraction = engineState.crossfadeProgressFraction,
                        error                    = engineState.error
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Pushes the BPM and first-beat data for [trackId] to [CrossfadeEngine.updateCurrentBpmInfo]
     * so the engine's beat grid is always in sync with whatever track is currently playing.
     *
     * Called from:
     * - [observeCrossfadeEngineState] when a crossfade completes and the track changes.
     * - [observeBpmCache] when BPM analysis finishes for the currently playing track.
     * - [onEvent] (JumpToTrack) when the user manually selects a track.
     */
    private fun syncBeatGridForTrack(trackId: Long) {
        val bpmInfo = _uiState.value.bpmCache[trackId] ?: return
        crossfadeEngine.updateCurrentBpmInfo(bpmInfo.bpm, bpmInfo.firstBeatMs)
        Log.d(
            TAG,
            "Beat grid synced for track $trackId: ${bpmInfo.bpm} BPM " +
                    "firstBeat=${bpmInfo.firstBeatMs}ms"
        )
    }

    /**
     * When the engine signals that it is approaching the end of the current track, this
     * observer uses [GetSmartNextTrackUseCase] to pick the best remaining track and feeds
     * it back to the engine via [CrossfadeEngine.queueNextTrack].
     *
     * Now passes [nextBpm] alongside [firstBeatMs] so the engine can apply Tempo Sync
     * (time-stretching) in addition to Smart Cue.
     */
    private fun observeNextTrackRequests() {
        crossfadeEngine.nextTrackRequest
            .onEach { currentTrackId ->
                val state      = _uiState.value
                val currentBpm = state.bpmCache[currentTrackId]?.bpm ?: 120f

                // Remaining = smart queue minus the current track AND anything already played
                var remaining = state.smartQueue.filter {
                    it.id != currentTrackId && !playedTrackIds.contains(it.id)
                }

                // If everyone has been played, check the user's looping preference.
                if (remaining.isEmpty() && state.smartQueue.size > 1) {
                    if (state.settings.loopQueue) {
                        Log.d(TAG, "Queue exhausted. Resetting session history (Looping).")
                        playedTrackIds.clear()
                        playedTrackIds.add(currentTrackId)
                        remaining = state.smartQueue.filter { it.id != currentTrackId }
                    } else {
                        Log.d(TAG, "Queue exhausted. Looping disabled. Letting track finish.")
                    }
                }

                val nextTrack = getSmartNextTrackUseCase(
                    currentBpm    = currentBpm,
                    remainingQueue = remaining,
                    bpmCache      = state.bpmCache.mapValues { it.value.bpm },
                    tolerance     = state.settings.bpmTolerance
                )

                if (nextTrack != null && remaining.isNotEmpty()) {
                    val nextBpmInfo   = state.bpmCache[nextTrack.id]
                    val firstBeatMs   = nextBpmInfo?.firstBeatMs ?: 0L
                    val nextBpm       = nextBpmInfo?.bpm ?: 0f
                    val deltaBpm      = abs((nextBpmInfo?.bpm ?: 0f) - currentBpm)
                    Log.d(
                        TAG,
                        "Next track selected: '${nextTrack.title}' " +
                                "(ΔBPM=%.1f firstBeatMs=${firstBeatMs}ms nextBpm=${nextBpm})".format(deltaBpm)
                    )
                    // Pass firstBeatMs (Smart Cue) AND nextBpm (Tempo Sync) to the engine
                    crossfadeEngine.queueNextTrack(nextTrack, firstBeatMs, nextBpm)
                } else {
                    Log.d(TAG, "Queue exhausted — DJ Mix will finish after current track.")
                    viewModelScope.launch {
                        _uiEvent.emit("End of DJ Mix queue.")
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Rebuilds [DjMixUiState.smartQueue] to create a "Steady Climb" Set Arc.
     * * It orders songs from lowest BPM to highest BPM to build energy, while using
     * [GetSmartNextTrackUseCase] logic to smoothly transition through double/half-time
     * relationships, ensuring zero awkward cliff-drops in tempo.
     */
    private fun rebuildSmartQueue(
        bpmCache: Map<Long, BpmInfo> = _uiState.value.bpmCache
    ) {
        if (rawPlaylistSongs.isEmpty()) return

        val tolerance = _uiState.value.settings.bpmTolerance
        val remaining = rawPlaylistSongs.toMutableList()
        val result    = mutableListOf<AudioFile>()

        // Find the absolute slowest track to start our "Steady Climb" warmup
        val first = remaining.minByOrNull { bpmCache[it.id]?.bpm ?: Float.MAX_VALUE }
            ?: remaining.first()

        result.add(first)
        remaining.remove(first)

        while (remaining.isNotEmpty()) {
            val lastBpm = bpmCache[result.last().id]?.bpm ?: 120f

            // Find the closest mathematical match moving forward
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
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        crossfadeEngine.release()
        Log.d(TAG, "DjMixViewModel cleared — CrossfadeEngine released.")
    }
}