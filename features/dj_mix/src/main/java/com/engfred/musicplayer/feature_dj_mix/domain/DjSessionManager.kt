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
 */
@Singleton
class DjSessionManager @Inject constructor(
    private val getSmartNextTrackUseCase: GetSmartNextTrackUseCase
) {

    // ── Session lifecycle ─────────────────────────────────────────────────────

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    // NEW: track which playlist is active
    private var _activePlaylistId: Long? = null
    val activePlaylistId: Long? get() = _activePlaylistId

    // ── Shared queue state ────────────────────────────────────────────────────

    private val _smartQueue = MutableStateFlow<List<AudioFile>>(emptyList())
    val smartQueue: StateFlow<List<AudioFile>> = _smartQueue.asStateFlow()

    private val _bpmCache = MutableStateFlow<Map<Long, BpmInfo>>(emptyMap())
    val bpmCache: StateFlow<Map<Long, BpmInfo>> = _bpmCache.asStateFlow()

    private val _settings = MutableStateFlow(DjMixSettings())
    val settings: StateFlow<DjMixSettings> = _settings.asStateFlow()

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

    // NEW: start session with playlist ID
    fun startSession(playlistId: Long) {
        _activePlaylistId = playlistId
        _isSessionActive.value = true
        Log.d(TAG, "Session started for playlist $playlistId.")
    }

    /**
     * Tears down the session completely. Called by [DjMixService.onDestroy] so
     * returning to the DJ screen after a full stop starts a fresh session.
     */
    fun endSession() {
        _activePlaylistId = null
        _isSessionActive.value = false
        playedTrackIds.clear()
        _smartQueue.value = emptyList()
        Log.d(TAG, "Session ended — state cleared.")
    }

    // ── Next-track selection (DjMixService) ──────────────────────────────────

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

    fun getTrackTransitionInfo(audioFile: AudioFile): Triple<Long, Float, Float> {
        val info = _bpmCache.value[audioFile.id]
        return Triple(info?.firstBeatMs ?: 0L, info?.bpm ?: 0f, info?.amplitude ?: 0f)
    }
}