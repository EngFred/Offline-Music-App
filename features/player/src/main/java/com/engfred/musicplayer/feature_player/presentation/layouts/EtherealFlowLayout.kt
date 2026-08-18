package com.engfred.musicplayer.feature_player.presentation.layouts

import android.app.Activity
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.RepeatMode as RM
import com.engfred.musicplayer.core.util.MediaUtils.shareAudioFile
import com.engfred.musicplayer.feature_player.presentation.components.AlbumArtDisplay
import com.engfred.musicplayer.feature_player.presentation.components.AnimatedBlobBackground
import com.engfred.musicplayer.feature_player.presentation.components.ControlBar
import com.engfred.musicplayer.feature_player.presentation.components.FavoriteButton
import com.engfred.musicplayer.feature_player.presentation.components.QueueBottomSheet
import com.engfred.musicplayer.feature_player.presentation.components.SeekBarSection
import com.engfred.musicplayer.feature_player.presentation.components.TopBar
import com.engfred.musicplayer.feature_player.presentation.components.TrackInfo
import com.engfred.musicplayer.feature_player.presentation.viewmodel.PlayerEvent
import com.engfred.musicplayer.feature_player.utils.getDynamicGradientColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtherealFlowLayout(
    uiState: PlaybackState,
    onEvent: (PlayerEvent) -> Unit,
    onNavigateUp: () -> Unit,
    playingQueue: List<AudioFile>,
    currentSongIndex: Int,
    onPlayQueueItem: (AudioFile) -> Unit,
    onRemoveQueueItem: (AudioFile) -> Unit = {},
    selectedLayout: PlayerLayout,
    onLayoutSelected: (PlayerLayout) -> Unit,
    playingAudio: AudioFile?,
    repeatMode: RM
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var isSeeking by remember { mutableStateOf(false) }

    // Status bar icons — dark background compatibility
    DisposableEffect(isLandscape, selectedLayout) {
        val window = (context as? Activity)?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, view) }

        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        onDispose {
            insetsController?.isAppearanceLightStatusBars =
                colorScheme.background.luminance() > 0.5f
        }
    }

    // Dynamic gradient colors from artwork
    val gradientColors by produceState(
        initialValue = listOf(
            Color(0xFF1A0A2E),
            Color(0xFF0A142E),
            Color(0xFF0A2A1A),
            Color(0xFF2E1A0A),
        ),
        uiState.currentAudioFile?.albumArtUri
    ) {
        value = getDynamicGradientColors(
            context,
            uiState.currentAudioFile?.albumArtUri?.toString()
        )
    }

    val dynamicContentColor by remember(gradientColors) {
        val primary = gradientColors.firstOrNull() ?: Color.Black
        val chosen = if (primary.luminance() > 0.45f) Color.Black else Color.White
        mutableStateOf(chosen)
    }

    var showQueueBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showQueueBottomSheet && !isLandscape) {
        QueueBottomSheet(
            onDismissRequest = { showQueueBottomSheet = false },
            sheetState = sheetState,
            playingQueue = playingQueue,
            onPlayQueueItem = onPlayQueueItem,
            onRemoveQueueItem = onRemoveQueueItem,
            playingAudio = playingAudio,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            isPlaying = uiState.isPlaying
        )
    }

    var verticalDragCumulative by remember { mutableFloatStateOf(0f) }
    var horizontalDragCumulative by remember { mutableFloatStateOf(0f) }
    val dragThreshold = 100f
    val horizontalThreshold = 100f

    CompositionLocalProvider(LocalContentColor provides dynamicContentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            uiState.currentAudioFile?.let {
                                if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                else onEvent(PlayerEvent.AddToFavorites(it))
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (verticalDragCumulative > dragThreshold) {
                                onNavigateUp()
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            } else if (verticalDragCumulative < -dragThreshold) {
                                coroutineScope.launch { sheetState.show() }
                                showQueueBottomSheet = true
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            verticalDragCumulative = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            verticalDragCumulative += dragAmount
                            true
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                horizontalDragCumulative > horizontalThreshold -> {
                                    onEvent(PlayerEvent.SkipToPrevious)
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                                horizontalDragCumulative < -horizontalThreshold -> {
                                    onEvent(PlayerEvent.SkipToNext)
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                            horizontalDragCumulative = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            horizontalDragCumulative += dragAmount
                            true
                        }
                    )
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Skip to previous song") {
                            onEvent(PlayerEvent.SkipToPrevious)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            true
                        },
                        CustomAccessibilityAction("Skip to next song") {
                            onEvent(PlayerEvent.SkipToNext)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            true
                        }
                    )
                }
        ) {
            // Layer 1: Dynamic Blob Background
            AnimatedBlobBackground(
                colors = gradientColors,
                modifier = Modifier.fillMaxSize()
            )

            // Layer 2: Main Layout Content
            if (!isLandscape) {
                // Portrait Layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    TopBar(
                        onNavigateUp = onNavigateUp,
                        currentSongIndex = currentSongIndex,
                        totalQueueSize = playingQueue.size,
                        onOpenQueue = {
                            coroutineScope.launch { sheetState.show() }
                            showQueueBottomSheet = true
                        },
                        selectedLayout = selectedLayout,
                        onLayoutSelected = onLayoutSelected,
                        isFavorite = uiState.isFavorite,
                        onToggleFavorite = {
                            uiState.currentAudioFile?.let {
                                if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                else onEvent(PlayerEvent.AddToFavorites(it))
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onShareAudio = {
                            uiState.currentAudioFile?.let { shareAudioFile(context, it) }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AlbumArtDisplay(
                        albumArtUri = uiState.currentAudioFile?.albumArtUri,
                        isPlaying = uiState.isPlaying,
                        currentSongId = uiState.currentAudioFile?.id,
                        isSeeking = isSeeking,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    TrackInfo(
                        title = uiState.currentAudioFile?.title,
                        artist = uiState.currentAudioFile?.artist,
                        playerLayout = PlayerLayout.ETHEREAL_FLOW,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FavoriteButton(
                        isFavorite = uiState.isFavorite,
                        onToggleFavorite = {
                            uiState.currentAudioFile?.let {
                                if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                else onEvent(PlayerEvent.AddToFavorites(it))
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        },
                        playerLayout = PlayerLayout.ETHEREAL_FLOW
                    )

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
                        playerLayout = PlayerLayout.ETHEREAL_FLOW,
                        modifier = Modifier.padding(horizontal = 24.dp)
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
                        playerLayout = PlayerLayout.ETHEREAL_FLOW,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 8.dp)
                    )

                    // Subtle Bottom Swipe-Up Queue Handle
                    Box(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                color = LocalContentColor.current.copy(alpha = 0.25f),
                                shape = CircleShape
                            )
                    )
                }

            } else {
                // Landscape Layout
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtDisplay(
                        albumArtUri = uiState.currentAudioFile?.albumArtUri,
                        isPlaying = uiState.isPlaying,
                        currentSongId = uiState.currentAudioFile?.id,
                        isSeeking = isSeeking,
                        modifier = Modifier
                            .weight(1f)
                            .padding(16.dp)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        TopBar(
                            onNavigateUp = onNavigateUp,
                            currentSongIndex = currentSongIndex,
                            totalQueueSize = playingQueue.size,
                            onOpenQueue = {
                                coroutineScope.launch { sheetState.show() }
                                showQueueBottomSheet = true
                            },
                            selectedLayout = selectedLayout,
                            onLayoutSelected = onLayoutSelected,
                            isFavorite = uiState.isFavorite,
                            onToggleFavorite = {
                                uiState.currentAudioFile?.let {
                                    if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                    else onEvent(PlayerEvent.AddToFavorites(it))
                                }
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            onShareAudio = {
                                uiState.currentAudioFile?.let { shareAudioFile(context, it) }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        TrackInfo(
                            title = uiState.currentAudioFile?.title,
                            artist = uiState.currentAudioFile?.artist,
                            playerLayout = PlayerLayout.ETHEREAL_FLOW,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

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
                            playerLayout = PlayerLayout.ETHEREAL_FLOW,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

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
                            playerLayout = PlayerLayout.ETHEREAL_FLOW,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}