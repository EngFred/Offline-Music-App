package com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.PlaylistRepository
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
import com.engfred.musicplayer.feature_dj_mix.domain.model.DjMixSettings
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

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
    val crossfadeEngine: CrossfadeEngine        // exposed so the Screen can init on Main thread
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

            is DjMixEvent.JumpToTrack -> {
                crossfadeEngine.startPlayback(event.audioFile)
                _uiState.update { it.copy(currentTrack = event.audioFile) }
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

                _uiState.update { it.copy(
                    playlistName = playlist.name,
                    totalSongs = songs.size,
                    isLoading = false
                )}

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

                _uiState.update { it.copy(
                    bpmCache = bpmCache,
                    analysisProgress = progress,
                    isAnalyzing = stillAnalysing
                )}

                rebuildSmartQueue(bpmCache = bpmCache)
            }
            .launchIn(viewModelScope)
    }

    private fun observeCrossfadeEngineState() {
        crossfadeEngine.state
            .onEach { engineState ->
                _uiState.update { it.copy(
                    currentTrack = engineState.currentTrack,
                    isPlaying = engineState.isPlaying,
                    isCrossfading = engineState.isCrossfading,
                    currentPositionMs = engineState.currentPositionMs,
                    currentDurationMs = engineState.currentDurationMs,
                    crossfadeProgressFraction = engineState.crossfadeProgressFraction,
                    error = engineState.error
                )}
            }
            .launchIn(viewModelScope)
    }

    /**
     * When the engine signals that it is approaching the end of the current track, this
     * observer uses [GetSmartNextTrackUseCase] to pick the best remaining track and feeds
     * it back to the engine via [CrossfadeEngine.queueNextTrack].
     */
    private fun observeNextTrackRequests() {
        crossfadeEngine.nextTrackRequest
            .onEach { currentTrackId ->
                val state = _uiState.value
                val currentBpm = state.bpmCache[currentTrackId] ?: 120f

                // Remaining = smart queue minus the current track
                val remaining = state.smartQueue.filter { it.id != currentTrackId }

                val nextTrack = getSmartNextTrackUseCase(
                    currentBpm     = currentBpm,
                    remainingQueue = remaining,
                    bpmCache       = state.bpmCache,
                    tolerance      = state.settings.bpmTolerance
                )

                if (nextTrack != null) {
                    Log.d(TAG, "Next track selected: ${nextTrack.title} " +
                            "(Δ BPM = ${abs((state.bpmCache[nextTrack.id] ?: 0f) - currentBpm)})")
                    crossfadeEngine.queueNextTrack(nextTrack)
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
     * Rebuilds [DjMixUiState.smartQueue] by greedily ordering songs from [rawPlaylistSongs]
     * using BPM proximity. Songs without BPM data are appended at the end in natural order.
     *
     * Starts from the song closest to the median BPM so the mix begins at a "middle ground"
     * rather than an extreme tempo.
     */
    private fun rebuildSmartQueue(
        bpmCache: Map<Long, Float> = _uiState.value.bpmCache
    ) {
        if (rawPlaylistSongs.isEmpty()) return

        val tolerance = _uiState.value.settings.bpmTolerance
        val remaining = rawPlaylistSongs.toMutableList()
        val result    = mutableListOf<AudioFile>()

        // Pick median-BPM song as the starting point
        val sortedBpms = rawPlaylistSongs.mapNotNull { bpmCache[it.id] }.sorted()
        val medianBpm  = sortedBpms.getOrNull(sortedBpms.size / 2) ?: 120f
        val first = remaining.minByOrNull { abs((bpmCache[it.id] ?: Float.MAX_VALUE) - medianBpm) }
            ?: remaining.first()

        result.add(first)
        remaining.remove(first)

        while (remaining.isNotEmpty()) {
            val lastBpm = bpmCache[result.last().id] ?: medianBpm
            val next    = getSmartNextTrackUseCase(lastBpm, remaining, bpmCache, tolerance)
                ?: remaining.first()
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