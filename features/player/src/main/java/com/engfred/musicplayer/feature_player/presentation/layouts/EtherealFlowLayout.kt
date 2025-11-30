package com.engfred.musicplayer.feature_player.presentation.layouts

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.engfred.musicplayer.feature_player.presentation.components.ControlBar
import com.engfred.musicplayer.feature_player.presentation.components.FavoriteButton
import com.engfred.musicplayer.feature_player.presentation.components.PlayingQueueSection
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

    // Handle status bar color and icon appearance
    DisposableEffect(isLandscape, selectedLayout) {
        val window = (context as? Activity)?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, view) }

        // Set status bar icons to dark for light themes, and light for dark themes
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        onDispose {
            insetsController?.isAppearanceLightStatusBars = colorScheme.background.luminance() > 0.5f
        }
    }

    // Dynamic gradient based on album art
    val gradientColors by produceState(
        initialValue = listOf(Color(0xFF1E1E1E), Color(0xFF333333)),
        uiState.currentAudioFile?.albumArtUri
    ) {
        val uri = uiState.currentAudioFile?.albumArtUri
        value = getDynamicGradientColors(context, uri?.toString())
    }

    // Animation for flowing gradient effect
    // We use rememberInfiniteTransition instead of Animatable+LaunchedEffect.
    // This ensures the animation doesn't stop when gradientColors change (new song).
    val infiniteTransition = rememberInfiniteTransition(label = "gradient_animation")
    val animatedProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradient_progress"
    )

    // Create flowing gradient with animated positions
    val flowingGradient = remember(gradientColors, animatedProgress) {
        if (gradientColors.size >= 2) {
            Brush.linearGradient(
                colors = gradientColors + gradientColors, // Double the colors for seamless flow
                start = androidx.compose.ui.geometry.Offset(
                    x = -animatedProgress * 1000f,
                    y = -animatedProgress * 500f
                ),
                end = androidx.compose.ui.geometry.Offset(
                    x = 1000f - animatedProgress * 1000f,
                    y = 500f - animatedProgress * 500f
                )
            )
        } else {
            Brush.verticalGradient(gradientColors)
        }
    }

    val dynamicContentColor by remember(gradientColors) {
        val topGradientColor = gradientColors.firstOrNull() ?: Color.Black
        val targetLuminance = topGradientColor.luminance()
        val chosenColor = if (targetLuminance > 0.5f) Color.Black else Color.White
        mutableStateOf(chosenColor)
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
    val dragThreshold = 100f

    var horizontalDragCumulative by remember { mutableFloatStateOf(0f) }
    val horizontalThreshold = 100f

    CompositionLocalProvider(LocalContentColor provides dynamicContentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(flowingGradient)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            uiState.currentAudioFile?.let {
                                if (uiState.isFavorite) {
                                    onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                } else {
                                    onEvent(PlayerEvent.AddToFavorites(it))
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (verticalDragCumulative > dragThreshold) {
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
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (horizontalDragCumulative > horizontalThreshold) {
                                onEvent(PlayerEvent.SkipToPrevious)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            } else if (horizontalDragCumulative < -horizontalThreshold) {
                                onEvent(PlayerEvent.SkipToNext)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            horizontalDragCumulative = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            horizontalDragCumulative += dragAmount
                            true
                        }
                    )
                }
                .systemBarsPadding()
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(
                            label = "Skip to previous song",
                            action = {
                                onEvent(PlayerEvent.SkipToPrevious)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                true
                            }
                        ),
                        CustomAccessibilityAction(
                            label = "Skip to next song",
                            action = {
                                onEvent(PlayerEvent.SkipToNext)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                true
                            }
                        )
                    )
                }
        ) {
            // responsive paddings & spacing
            val horizontalPadding = when {
                isLandscape -> 32.dp
                else -> 0.dp
            }
            val verticalPadding = when {
                isLandscape -> 0.dp
                else -> 0.dp
            }
            val spacing = when {
                isLandscape -> 20.dp
                else -> 16.dp
            }

            if (!isLandscape) {
                // Portrait — enlarge hit targets / art on tablets
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
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
                        modifier = Modifier.fillMaxWidth(),
                        onShareAudio = {
                            uiState.currentAudioFile?.let { shareAudioFile(context, it) }
                        }
                    )

                    // Album art — larger on tablet
                    AlbumArtDisplay(
                        albumArtUri = uiState.currentAudioFile?.albumArtUri,
                        isPlaying = uiState.isPlaying,
                        playerLayout = PlayerLayout.ETHEREAL_FLOW,
                        modifier = Modifier
                            .fillMaxWidth()
                    )

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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                        },
                        playerLayout = PlayerLayout.ETHEREAL_FLOW
                    )

                    Spacer(modifier = Modifier.height(spacing))

                    SeekBarSection(
                        sliderValue = uiState.playbackPositionMs.toFloat(),
                        totalDurationMs = uiState.totalDurationMs,
                        playbackPositionMs = uiState.playbackPositionMs,
                        onSliderValueChange = { newValue ->
                            onEvent(PlayerEvent.SetSeeking(true))
                            onEvent(PlayerEvent.SeekTo(newValue.toLong()))
                        },
                        onSliderValueChangeFinished = {
                            onEvent(PlayerEvent.SetSeeking(false))
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        },
                        playerLayout = PlayerLayout.ETHEREAL_FLOW,
                        modifier = Modifier.padding(24.dp)
                    )

                    Spacer(modifier = Modifier.height(spacing))

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
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp, top = 8.dp)
                    )
                }
            } else {
                // Landscape layout
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        // Top bar is now outside of the scrollable column
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
                            modifier = Modifier.fillMaxWidth(),
                            onShareAudio = {
                                uiState.currentAudioFile?.let { shareAudioFile(context, it) }
                            }
                        )
                        // The rest of the content is in a scrollable column
                        val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
                        Column(
                            modifier = Modifier
                                .verticalScroll(scrollState)
                                .weight(1f) // Use weight to fill the remaining space
                                .padding(horizontal = 24.dp), // Adjust padding as needed
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            AlbumArtDisplay(
                                albumArtUri = uiState.currentAudioFile?.albumArtUri,
                                isPlaying = uiState.isPlaying,
                                playerLayout = PlayerLayout.ETHEREAL_FLOW,
                                modifier = Modifier
                                    .fillMaxWidth().size(200.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            TrackInfo(
                                title = uiState.currentAudioFile?.title,
                                artist = uiState.currentAudioFile?.artist,
                                playerLayout = PlayerLayout.ETHEREAL_FLOW,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FavoriteButton(
                                isFavorite = uiState.isFavorite,
                                onToggleFavorite = {
                                    uiState.currentAudioFile?.let {
                                        if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                        else onEvent(PlayerEvent.AddToFavorites(it))
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    }
                                },
                                playerLayout = PlayerLayout.ETHEREAL_FLOW
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SeekBarSection(
                                sliderValue = uiState.playbackPositionMs.toFloat(),
                                totalDurationMs = uiState.totalDurationMs,
                                playbackPositionMs = uiState.playbackPositionMs,
                                onSliderValueChange = { newValue ->
                                    onEvent(PlayerEvent.SetSeeking(true))
                                    onEvent(PlayerEvent.SeekTo(newValue.toLong()))
                                },
                                onSliderValueChangeFinished = {
                                    onEvent(PlayerEvent.SetSeeking(false))
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                },
                                playerLayout = PlayerLayout.ETHEREAL_FLOW,
                                modifier = Modifier.padding(horizontal = 24.dp)
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
                                playerLayout = PlayerLayout.ETHEREAL_FLOW,
                                modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp, top = 8.dp)
                            )
                        }
                    }

                    // Right: queue
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .navigationBarsPadding()
                            .padding(end = 8.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Top
                    ) {
                        PlayingQueueSection(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LocalContentColor.current.copy(alpha = 0.06f))
                                .padding(bottom = 7.dp),
                            playingQueue = playingQueue,
                            playingAudio = uiState.currentAudioFile,
                            onPlayItem = onPlayQueueItem,
                            onRemoveItem = onRemoveQueueItem,
                            isCompact = false,
                            isPlaying = uiState.isPlaying
                        )
                    }
                }
            }
        }
    }
}