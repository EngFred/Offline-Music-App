package com.engfred.musicplayer.feature_dj_mix.domain

import android.util.Log
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.domain.model.MixStudioSettings
import com.engfred.musicplayer.feature_dj_mix.domain.repository.BpmInfo
import com.engfred.musicplayer.feature_dj_mix.domain.usecases.GetSmartNextTrackUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DjSessionManager"

@Singleton
class DjSessionManager @Inject constructor(
    private val getSmartNextTrackUseCase: GetSmartNextTrackUseCase
) {
    // ── Session lifecycle ─────────────────────────────────────────────────────

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private var _activePlaylistId: Long? = null
    val activePlaylistId: Long? get() = _activePlaylistId

    // ── Shared queue state ────────────────────────────────────────────────────

    private val _smartQueue = MutableStateFlow<List<AudioFile>>(emptyList())
    val smartQueue: StateFlow<List<AudioFile>> = _smartQueue.asStateFlow()

    private val _bpmCache = MutableStateFlow<Map<Long, BpmInfo>>(emptyMap())
    val bpmCache: StateFlow<Map<Long, BpmInfo>> = _bpmCache.asStateFlow()

    private val _settings = MutableStateFlow(MixStudioSettings())
    val settings: StateFlow<MixStudioSettings> = _settings.asStateFlow()

    // ── Play history (ordered) ────────────────────────────────────────────────

    private val playHistory: ArrayDeque<Long> = ArrayDeque()

    private val _canSkipBack = MutableStateFlow(false)
    val canSkipBack: StateFlow<Boolean> = _canSkipBack.asStateFlow()

    private val _playedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val playedTrackIds: StateFlow<Set<Long>> = _playedTrackIds.asStateFlow()

    // ── Write API ─────────────────────────────────────────────────────────────

    fun updateSmartQueue(queue: List<AudioFile>) { _smartQueue.value = queue }
    fun updateBpmCache(cache: Map<Long, BpmInfo>) { _bpmCache.value = cache }
    fun updateSettings(settings: MixStudioSettings)  { _settings.value = settings }

    @Synchronized
    fun markTrackPlayed(trackId: Long) {
        // Avoid duplicate consecutive entries (e.g., fast double-tap on JumpToTrack)
        if (playHistory.lastOrNull() != trackId) {
            playHistory.addLast(trackId)
        }
        _canSkipBack.value = playHistory.size >= 2
        _playedTrackIds.value = playHistory.toSet()
        Log.d(TAG, "markTrackPlayed: $trackId | history=${playHistory.size} canSkipBack=${_canSkipBack.value}")
    }

    @Synchronized
    fun resetPlayHistory(keepCurrentId: Long? = null) {
        playHistory.clear()
        keepCurrentId?.let { playHistory.addLast(it) }
        _canSkipBack.value = false
        _playedTrackIds.value = if (keepCurrentId != null) setOf(keepCurrentId) else emptySet()
        Log.d(TAG, "resetPlayHistory. Retained: $keepCurrentId")
    }

    fun startSession(playlistId: Long) {
        _activePlaylistId = playlistId
        _isSessionActive.value = true
        Log.d(TAG, "Session started for playlist $playlistId")
    }

    fun endSession() {
        _activePlaylistId = null
        _isSessionActive.value = false
        synchronized(this) {
            playHistory.clear()
            _canSkipBack.value = false
            _playedTrackIds.value = emptySet()
        }
        _smartQueue.value = emptyList()
        Log.d(TAG, "Session ended — state cleared")
    }

    // ── Next-track selection ──────────────────────────────────────────────────

    fun selectNextTrack(currentTrackId: Long): AudioFile? {
        val queue    = _smartQueue.value
        val cache    = _bpmCache.value
        val cfg      = _settings.value
        val currentBpm = cache[currentTrackId]?.bpm?.takeIf { it > 0f } ?: 120f

        val playedIds = synchronized(this) { playHistory.toSet() }
        var remaining = queue.filter { it.id != currentTrackId && !playedIds.contains(it.id) }

        if (remaining.isEmpty() && queue.size > 1) {
            if (cfg.loopQueue) {
                Log.d(TAG, "Queue exhausted — looping: resetting history")
                resetPlayHistory(keepCurrentId = currentTrackId)
                remaining = queue.filter { it.id != currentTrackId }
            } else {
                Log.d(TAG, "Queue exhausted — looping disabled")
                return null
            }
        }

        if (remaining.isEmpty()) return null

        return getSmartNextTrackUseCase(
            currentBpm     = currentBpm,
            remainingQueue = remaining,
            bpmCache       = cache.mapValues { it.value.bpm },
            tolerance      = cfg.bpmTolerance
        ).also { Log.d(TAG, "selectNextTrack: '${it?.title}' for currentId=$currentTrackId") }
    }

    fun getTrackTransitionInfo(audioFile: AudioFile): Triple<Long, Float, Float> {
        val info = _bpmCache.value[audioFile.id]
        val cueInMs = info?.firstBeatMs ?: 0L
        return Triple(cueInMs, info?.bpm ?: 0f, info?.amplitude ?: 0f)
    }
}