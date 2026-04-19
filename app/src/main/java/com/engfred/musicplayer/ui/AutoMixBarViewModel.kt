package com.engfred.musicplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.CrossfadeEngine
import com.engfred.musicplayer.feature_dj_mix.domain.DjSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [AutoMixBar] without coupling it to [MixStudioViewModel].
 *
 * Both [CrossfadeEngine] and [DjSessionManager] are @Singleton, so reading
 * them here is safe and always up-to-date, even while the user is on a
 * different screen.
 */
@UnstableApi
@HiltViewModel
class AutoMixBarViewModel @Inject constructor(
    private val crossfadeEngine: CrossfadeEngine,
    private val djSessionManager: DjSessionManager,
) : ViewModel() {

    data class BarState(
        val currentTrack:  AudioFile? = null,
        val isPlaying:     Boolean    = false,
        val isCrossfading: Boolean    = false,
        val positionMs:    Long       = 0L,
        val durationMs:    Long       = 0L,
        val isRealMixMode: Boolean    = true,
        val bpm:           Float?     = null,
    )

    val barState: StateFlow<BarState> = combine(
        crossfadeEngine.state,
        djSessionManager.settings,
        djSessionManager.bpmCache
    ) { engine, settings, bpmCache ->
        val bpmInfo = engine.currentTrack?.id?.let { bpmCache[it] }
        BarState(
            currentTrack  = engine.currentTrack,
            isPlaying     = engine.isPlaying,
            isCrossfading = engine.isCrossfading,
            positionMs    = engine.currentPositionMs,
            durationMs    = engine.currentDurationMs,
            isRealMixMode = settings.isRealMixMode,
            bpm           = bpmInfo?.takeIf { !it.analysisFailed }?.bpm,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = BarState()
    )

    /** Toggle playback on the currently-active DJ engine. */
    fun playPause() = crossfadeEngine.playPause()

    /**
     * Trigger an immediate crossfade to the next track.
     * The engine's own [CrossfadeEngine.triggerMixNow] already guards against
     * double-calls while crossfading; this guard is an extra UI-layer fast-path.
     */
    fun mixNow() {
        if (!crossfadeEngine.state.value.isCrossfading) {
            crossfadeEngine.triggerMixNow()
        }
    }
}