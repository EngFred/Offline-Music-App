package com.engfred.musicplayer.feature_player.data.service

import com.engfred.musicplayer.core.domain.model.LastPlaybackState
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log

private const val TAG = "PlaybackStateSaver"

/**
 * Provides small helpers to save current playback state. Kept separate for clarity.
 */
fun savePlaybackStateAsync(scope: CoroutineScope, settingsRepository: SettingsRepository, exoPlayer: ExoPlayer) {
    scope.launch {
        val currentItem = exoPlayer.currentMediaItem
        if (currentItem != null) {
            val audioId = currentItem.mediaId.toLongOrNull()
            val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val queueIds = (0 until exoPlayer.mediaItemCount).mapNotNull { exoPlayer.getMediaItemAt(it).mediaId.toLongOrNull() }
            val state = if (audioId != null && queueIds.isNotEmpty()) {
                LastPlaybackState(audioId, positionMs, queueIds)
            } else {
                LastPlaybackState(null)
            }
            settingsRepository.saveLastPlaybackState(state)
            if (positionMs > 0) {
                Log.d(TAG, "Periodic save: ID=$audioId, pos=${positionMs}ms, queue size=${queueIds.size}")
            }
        }
    }
}

/**
 * Blocking save used in onDestroy (keeps original behavior).
 */
fun saveLastPlaybackStateBlocking(settingsRepository: SettingsRepository, exoPlayer: ExoPlayer) {
    runBlocking {
        val currentItem = exoPlayer.currentMediaItem
        val audioId = currentItem?.mediaId?.toLongOrNull()
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val queueIds = (0 until exoPlayer.mediaItemCount).mapNotNull { exoPlayer.getMediaItemAt(it).mediaId.toLongOrNull() }
        val state = if (audioId != null && queueIds.isNotEmpty()) LastPlaybackState(audioId, positionMs, queueIds) else LastPlaybackState(null)
        settingsRepository.saveLastPlaybackState(state)
        Log.d(TAG, "Saved last playback state: ID=${state.audioId}, pos=${state.positionMs}ms, queue size=${state.queueIds?.size ?: 0}")
    }
}
