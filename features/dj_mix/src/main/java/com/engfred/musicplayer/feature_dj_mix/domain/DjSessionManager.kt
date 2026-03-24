package com.engfred.musicplayer.feature_dj_mix.domain

import android.util.Log
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.model.DjMixSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DjSessionManager"

/**
 * Singleton that owns all DJ Mix session state that must survive [DjMixViewModel] destruction.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 * BUG FIX: Previously, [playedTrackIds] and [smartQueue] lived in DjMixViewModel.
 * When the user navigated away from the DJ screen the ViewModel was cleared, all
 * [crossfadeEngine.nextTrackRequest] emissions went unhandled, and playback stopped
 * at the end of the current track.
 *
 * By moving session state here, [DjMixService] can select and queue the next track
 * entirely without a ViewModel, and [DjMixViewModel] can reconstruct its UI state
 * accurately when the user returns to the screen.
 *
 * ── Who writes / reads ───────────────────────────────────────────────────────
 * DjMixViewModel  → writes smartQueue, bpmCache, settings as data loads.
 * DjMixService    → reads selectNextTrack() in response to nextTrackRequest.
 * DjMixViewModel  → reads isSessionActive on re-entry to skip auto-start.
 */
@Singleton
class DjSessionManager @Inject constructor(
    private val getSmartNextTrackUseCase: GetSmartNextTrackUseCase
) {

    // ── Session lifecycle ─────────────────────────────────────────────────────

    private val _isSessionActive = MutableStateFlow(false)
    /** True once a DJ session has started; survives ViewModel recreation. */
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    // ── Shared queue state ────────────────────────────────────────────────────

    private val _smartQueue = MutableStateFlow<List<AudioFile>>(emptyList())
    /** BPM-ordered playlist used by the engine. Updated by ViewModel. */
    val smartQueue: StateFlow<List<AudioFile>> = _smartQueue.asStateFlow()

    private val _bpmCache = MutableStateFlow<Map<Long, BpmInfo>>(emptyMap())
    /** audioFileId → BpmInfo. Updated by ViewModel as WorkManager analysis completes. */
    val bpmCache: StateFlow<Map<Long, BpmInfo>> = _bpmCache.asStateFlow()

    private val _settings = MutableStateFlow(DjMixSettings())
    /** Current user-configured DJ settings. Updated by ViewModel via DataStore. */
    val settings: StateFlow<DjMixSettings> = _settings.asStateFlow()

    /**
     * Thread-safe set of track IDs already played this session.
     * Prevents the engine from looping between the same two tracks and allows
     * the Service to pick a correct next track without ViewModel assistance.
     */
    private val playedTrackIds: MutableSet<Long> =
        Collections.synchronizedSet(mutableSetOf())

    // ── Write API (DjMixViewModel) ────────────────────────────────────────────

    fun updateSmartQueue(queue: List<AudioFile>) { _smartQueue.value = queue }
    fun updateBpmCache(cache: Map<Long, BpmInfo>) { _bpmCache.value = cache }
    fun updateSettings(settings: DjMixSettings)  { _settings.value = settings }

    fun markTrackPlayed(trackId: Long) {
        playedTrackIds.add(trackId)
        Log.d(TAG, "Marked $trackId as played. History: ${playedTrackIds.size} tracks.")
    }

    fun resetPlayHistory(keepCurrentId: Long? = null) {
        playedTrackIds.clear()
        keepCurrentId?.let { playedTrackIds.add(it) }
        Log.d(TAG, "Play history reset. Retained: $keepCurrentId")
    }

    fun startSession() {
        _isSessionActive.value = true
        Log.d(TAG, "Session started.")
    }

    /**
     * Tears down the session completely. Called by [DjMixService.onDestroy] so
     * returning to the DJ screen after a full stop starts a fresh session.
     */
    fun endSession() {
        _isSessionActive.value = false
        playedTrackIds.clear()
        _smartQueue.value = emptyList()
        Log.d(TAG, "Session ended — state cleared.")
    }

    // ── Next-track selection (DjMixService) ──────────────────────────────────

    /**
     * Selects the best next track for the engine to crossfade into.
     *
     * Called by [DjMixService] in response to [CrossfadeEngine.nextTrackRequest].
     * This is the core fix for the "queue stops in background" bug: track selection
     * now works regardless of whether [DjMixViewModel] is alive.
     *
     * @param currentTrackId ID of the track currently playing.
     * @return The next [AudioFile], or null if the queue is exhausted and looping is off.
     */
    fun selectNextTrack(currentTrackId: Long): AudioFile? {
        val queue    = _smartQueue.value
        val cache    = _bpmCache.value
        val cfg      = _settings.value
        val currentBpm = cache[currentTrackId]?.bpm ?: 120f

        var remaining = queue.filter { it.id != currentTrackId && !playedTrackIds.contains(it.id) }

        if (remaining.isEmpty() && queue.size > 1) {
            if (cfg.loopQueue) {
                Log.d(TAG, "Queue exhausted — looping: resetting history.")
                resetPlayHistory(keepCurrentId = currentTrackId)
                remaining = queue.filter { it.id != currentTrackId }
            } else {
                Log.d(TAG, "Queue exhausted — looping disabled.")
                return null
            }
        }

        if (remaining.isEmpty()) return null

        return getSmartNextTrackUseCase(
            currentBpm     = currentBpm,
            remainingQueue = remaining,
            bpmCache       = cache.mapValues { it.value.bpm },
            tolerance      = cfg.bpmTolerance
        ).also { Log.d(TAG, "Next track selected: '${it?.title}' for currentId=$currentTrackId") }
    }

    /**
     * Returns the (firstBeatMs, bpm, amplitude) tuple for [audioFile], ready to
     * pass directly to [CrossfadeEngine.queueNextTrack].
     */
    fun getTrackTransitionInfo(audioFile: AudioFile): Triple<Long, Float, Float> {
        val info = _bpmCache.value[audioFile.id]
        return Triple(info?.firstBeatMs ?: 0L, info?.bpm ?: 0f, info?.amplitude ?: 0f)
    }
}