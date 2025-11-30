package com.engfred.musicplayer.feature_player.data.repository.controller

import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

private const val TAG = "PlayerControllerImpl"

class MediaControllerBuilder(
    private val context: Context,
    private val sessionToken: SessionToken,
    private val mediaController: MutableStateFlow<MediaController?>,
    private val playbackState: MutableStateFlow<PlaybackState>,
) {
    /**
     * Uses coroutines-guava 'await' to suspend until the controller is ready.
     * Includes a small retry loop for resilience if the service isn't up yet.
     */
    suspend fun buildAndConnectController() {
        withContext(Dispatchers.Main) {
            var attempts = 0
            val maxAttempts = 6
            var lastError: Throwable? = null

            while (attempts < maxAttempts) {
                try {
                    val controller = MediaController.Builder(context, sessionToken)
                        .buildAsync()
                        .await()
                    Log.d(TAG, "MediaController connected (attempt ${attempts + 1}).")
                    mediaController.value = controller
                    return@withContext
                } catch (e: Exception) {
                    lastError = e
                    attempts++
                    Log.w(TAG, "Failed to connect MediaController (attempt $attempts/$maxAttempts): ${e.message}")
                    delay(600L * attempts) // backoff
                }
            }

            Log.e(TAG, "Unable to connect MediaController after $maxAttempts attempts.", lastError)
            mediaController.value = null
            playbackState.update { it.copy(error = "Player not initialized.") }
        }
    }
}