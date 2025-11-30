package com.engfred.musicplayer.feature_player.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.ui.components.LoadingIndicator
import com.engfred.musicplayer.feature_player.presentation.layouts.EtherealFlowLayout
import com.engfred.musicplayer.feature_player.presentation.layouts.ImmersiveCanvasLayout
import com.engfred.musicplayer.feature_player.presentation.layouts.MinimalistGrooveLayout
import com.engfred.musicplayer.feature_player.presentation.viewmodel.NowPlayingViewModel
import com.engfred.musicplayer.feature_player.presentation.viewmodel.PlayerEvent
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit
) {
    val uiState: PlaybackState by viewModel.uiState.collectAsState()
    val selectedLayout: PlayerLayout? by viewModel.playerLayoutState.collectAsState()

    val context = LocalContext.current
    // Keep the latest lambda reference safe for use inside LaunchedEffect
    val currentOnNavigateUp by rememberUpdatedState(onNavigateUp)

    // When we are in "loading" (no currentAudioFile), start a 2s timer.
    // If after 2s still loading, show a toast and navigate up.
    LaunchedEffect(key1 = uiState.currentAudioFile) {
        if (uiState.currentAudioFile == null) {
            // wait 2 seconds
            delay(3000L)
            // If still null after delay, show toast and navigate up
            if (uiState.currentAudioFile == null) {
//                Toast.makeText(context, "Unable to load track — returning.", Toast.LENGTH_SHORT).show()
                currentOnNavigateUp()
            }
        } else {
            // audio file became available, no action needed (timer automatically cancelled)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (uiState.currentAudioFile == null) {
            LoadingIndicator()
        } else {
            when (selectedLayout) {
                PlayerLayout.ETHEREAL_FLOW -> selectedLayout?.let {
                    EtherealFlowLayout(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        onLayoutSelected = { newLayout ->
                            viewModel.onEvent(PlayerEvent.SelectPlayerLayout(newLayout))
                        },
                        playingQueue = uiState.playingQueue,
                        currentSongIndex = uiState.playingSongIndex,
                        onPlayQueueItem = {
                            viewModel.onEvent(PlayerEvent.PlayAudioFile(it))
                        },
                        onNavigateUp = onNavigateUp,
                        playingAudio = uiState.currentAudioFile,
                        selectedLayout = it,
                        onRemoveQueueItem = { audio ->
                            if (uiState.playingQueue.size > 1) {
                                viewModel.onEvent(PlayerEvent.RemovedFromQueue(audio))
                            }
                        },
                        repeatMode = uiState.repeatMode,
                    )
                }
                PlayerLayout.IMMERSIVE_CANVAS -> selectedLayout?.let {
                    ImmersiveCanvasLayout(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        onLayoutSelected = { newLayout ->
                            viewModel.onEvent(PlayerEvent.SelectPlayerLayout(newLayout))
                        },
                        playingQueue = uiState.playingQueue,
                        currentSongIndex = uiState.playingSongIndex,
                        onPlayQueueItem = { audio ->
                            viewModel.onEvent(PlayerEvent.PlayAudioFile(audio))
                        },
                        onNavigateUp = onNavigateUp,
                        playingAudio = uiState.currentAudioFile,
                        selectedLayout = it,
                        onRemoveQueueItem = { audio ->
                            if (uiState.playingQueue.size > 1) {
                                viewModel.onEvent(PlayerEvent.RemovedFromQueue(audio))
                            }
                        },
                        repeatMode = uiState.repeatMode,
                    )
                }
                PlayerLayout.MINIMALIST_GROOVE -> selectedLayout?.let {
                    MinimalistGrooveLayout(
                        uiState = uiState,
                        onEvent = viewModel::onEvent,
                        onLayoutSelected = { newLayout ->
                            viewModel.onEvent(PlayerEvent.SelectPlayerLayout(newLayout))
                        },
                        totalSongsInQueue = uiState.playingQueue.size,
                        currentSongIndex = uiState.playingSongIndex,
                        onNavigateUp = onNavigateUp,
                        selectedLayout = it,
                        playingQueue = uiState.playingQueue,
                        onPlayQueueItem = { audio ->
                            viewModel.onEvent(PlayerEvent.PlayAudioFile(audio))
                        },
                        repeatMode = uiState.repeatMode,
                        onRemoveQueueItem = { audio ->
                            if (uiState.playingQueue.size > 1) {
                                viewModel.onEvent(PlayerEvent.RemovedFromQueue(audio))
                            }
                        },
                    )
                }
                null -> {}
            }
        }
    }
}
