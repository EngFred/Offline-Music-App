package com.engfred.musicplayer.feature_player.presentation.layouts

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.util.MediaUtils.shareAudioFile
import com.engfred.musicplayer.feature_player.presentation.components.ControlBar
import com.engfred.musicplayer.feature_player.presentation.components.QueueBottomSheet
//import com.engfred.musicplayer.feature_player.presentation.components.RotatingWaveAlbumArt
import com.engfred.musicplayer.feature_player.presentation.components.SeekBarSection
import com.engfred.musicplayer.feature_player.presentation.components.TopBar
import com.engfred.musicplayer.feature_player.presentation.components.VinylRecordView
import com.engfred.musicplayer.feature_player.presentation.viewmodel.PlayerEvent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimalistGrooveLayout(
    uiState: PlaybackState,
    onEvent: (PlayerEvent) -> Unit,
    onNavigateUp: () -> Unit,
    currentSongIndex: Int,
    totalSongsInQueue: Int,
    selectedLayout: PlayerLayout,
    onLayoutSelected: (PlayerLayout) -> Unit,
    playingQueue: List<AudioFile>,
    onPlayQueueItem: (AudioFile) -> Unit,
    onRemoveQueueItem: (AudioFile) -> Unit,
    repeatMode: RepeatMode
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = configuration.screenWidthDp

    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showQueueBottomSheet) {
        QueueBottomSheet(
            onDismissRequest = { showQueueBottomSheet = false },
            sheetState = sheetState,
            playingQueue = playingQueue,
            onPlayQueueItem = onPlayQueueItem,
            onRemoveQueueItem = onRemoveQueueItem,
            playingAudio = uiState.currentAudioFile,
            isPlaying = uiState.isPlaying
        )
    }
    var verticalDragCumulative by remember { mutableFloatStateOf(0f) }
    val dragThreshold = 100f

    // Track seeking state
    var isSeeking by remember { mutableStateOf(false) }

    // responsive breakpoint
    val isTablet = screenWidthDp >= 900

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize().systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (verticalDragCumulative < -dragThreshold) {
                                coroutineScope.launch { sheetState.show() }
                                showQueueBottomSheet = true
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            } else if (verticalDragCumulative > dragThreshold) {
                                onNavigateUp()
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            verticalDragCumulative = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            verticalDragCumulative += dragAmount
                            true
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            TopBar(
                onNavigateUp = onNavigateUp,
                currentSongIndex = currentSongIndex + 1,
                totalQueueSize = totalSongsInQueue,
                onOpenQueue = { },
                selectedLayout = selectedLayout,
                onLayoutSelected = onLayoutSelected,
                isFavorite = uiState.isFavorite,
                onToggleFavorite = {
                    uiState.currentAudioFile?.let {
                        if (uiState.isFavorite) {
                            onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                        } else {
                            onEvent(PlayerEvent.AddToFavorites(it))
                        }
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                },
                modifier = Modifier.fillMaxWidth(),
                onShareAudio = {
                    uiState.currentAudioFile?.let {
                        shareAudioFile(context, it)
                    }
                }
            )

            if (isLandscape) {
                // Landscape: adjust spacing/sizes for tablet
                val artAndControlsSpacing = if (isTablet) 96.dp else 76.dp
                val controlsWeight = if (isTablet) 1.6f else 1f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isTablet) 32.dp else 24.dp)
                        .weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VinylRecordView(
                        albumArtUri = uiState.currentAudioFile?.albumArtUri,
                        isPlaying = uiState.isPlaying,
                        currentSongId = uiState.currentAudioFile?.id,
                        isSeeking = isSeeking,
                        onPlayPauseToggle = { onEvent(PlayerEvent.PlayPause) },
                        onPlay = {
                            if (!uiState.isPlaying) onEvent(PlayerEvent.PlayPause)
                        },
                        onPause = {
                            if (uiState.isPlaying) onEvent(PlayerEvent.PlayPause)
                        },
                        modifier = Modifier
                            .aspectRatio(1f)
                            .weight(1f)
                    )
                    Spacer(modifier = Modifier.width(artAndControlsSpacing))
                    Column(
                        modifier = Modifier
                            .weight(controlsWeight)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        SongInfoSection(
                            title = uiState.currentAudioFile?.title,
                            artist = uiState.currentAudioFile?.artist
                        )
                        Spacer(modifier = Modifier.height(if (isTablet) 40.dp else 32.dp))
                        SeekBarSection(
                            sliderValue = uiState.playbackPositionMs.toFloat(),
                            totalDurationMs = uiState.totalDurationMs,
                            playbackPositionMs = uiState.playbackPositionMs,
                            onSliderValueChange = { newValue ->
                                isSeeking = true
                                onEvent(PlayerEvent.SetSeeking(true))
                                onEvent(PlayerEvent.SeekTo(newValue.toLong()))
                            },
                            onSliderValueChangeFinished = {
                                isSeeking = false
                                onEvent(PlayerEvent.SetSeeking(false))
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            },
                            playerLayout = PlayerLayout.MINIMALIST_GROOVE,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ControlBar(
                            shuffleMode = uiState.shuffleMode,
                            isPlaying = uiState.isPlaying,
                            repeatMode = repeatMode,
                            onPlayPauseClick = {
                                onEvent(PlayerEvent.PlayPause)
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            },
                            onSkipPreviousClick = {
                                onEvent(PlayerEvent.SkipToPrevious)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            },
                            onSkipNextClick = {
                                onEvent(PlayerEvent.SkipToNext)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            },
                            onSetShuffleMode = { newMode -> onEvent(PlayerEvent.SetShuffleMode(newMode)) },
                            onSetRepeatMode = { newMode -> onEvent(PlayerEvent.SetRepeatMode(newMode)) },
                            playerLayout = PlayerLayout.MINIMALIST_GROOVE,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                // Portrait: scale album art size on tablets
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = if (isTablet) 32.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceAround
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceAround
                    ) {
                        VinylRecordView(
                            albumArtUri = uiState.currentAudioFile?.albumArtUri,
                            isPlaying = uiState.isPlaying,
                            currentSongId = uiState.currentAudioFile?.id,
                            isSeeking = isSeeking, // Pass seeking state
                            onPlayPauseToggle = { onEvent(PlayerEvent.PlayPause) },
                            onPlay = {
                                if (!uiState.isPlaying) onEvent(PlayerEvent.PlayPause)
                            },
                            onPause = {
                                if (uiState.isPlaying) onEvent(PlayerEvent.PlayPause)
                            },
                            modifier = Modifier.size(if (isTablet) 360.dp else 280.dp) // Adjusted size for Vinyl aesthetics
                        )
                        Spacer(modifier = Modifier.height( if (isTablet) 36.dp else 28.dp))
                        SongInfoSection(
                            title = uiState.currentAudioFile?.title,
                            artist = uiState.currentAudioFile?.artist,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height( if (isTablet) 36.dp else 32.dp))
                        SeekBarSection(
                            sliderValue = uiState.playbackPositionMs.toFloat(),
                            totalDurationMs = uiState.totalDurationMs,
                            playbackPositionMs = uiState.playbackPositionMs,
                            onSliderValueChange = { newValue ->
                                isSeeking = true // Start seeking
                                onEvent(PlayerEvent.SetSeeking(true))
                                onEvent(PlayerEvent.SeekTo(newValue.toLong()))
                            },
                            onSliderValueChangeFinished = {
                                isSeeking = false // Stop seeking
                                onEvent(PlayerEvent.SetSeeking(false))
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            },
                            playerLayout = PlayerLayout.MINIMALIST_GROOVE,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ControlBar(
                            shuffleMode = uiState.shuffleMode,
                            isPlaying = uiState.isPlaying,
                            repeatMode = repeatMode,
                            onPlayPauseClick = {
                                onEvent(PlayerEvent.PlayPause)
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            },
                            onSkipPreviousClick = {
                                onEvent(PlayerEvent.SkipToPrevious)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            },
                            onSkipNextClick = {
                                onEvent(PlayerEvent.SkipToNext)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            },
                            onSetShuffleMode = { newMode -> onEvent(PlayerEvent.SetShuffleMode(newMode)) },
                            onSetRepeatMode = { newMode -> onEvent(PlayerEvent.SetRepeatMode(newMode)) },
                            playerLayout = PlayerLayout.MINIMALIST_GROOVE,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 13.dp)
                    .align(Alignment.CenterHorizontally)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                coroutineScope.launch { sheetState.show() }
                                showQueueBottomSheet = true
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        )
                    }
            ) {
                val currentSongI = currentSongIndex + 1
                val currentSongNumText = if (currentSongI > totalSongsInQueue) "" else currentSongI.toString()
                Text(
                    text = "${currentSongNumText}/${totalSongsInQueue}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Open Queue",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun SongInfoSection(
    title: String?,
    artist: String?,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = title ?: "No Song Playing",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = artist ?: "Select a song",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}