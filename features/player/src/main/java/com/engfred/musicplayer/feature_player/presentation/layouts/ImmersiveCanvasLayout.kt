package com.engfred.musicplayer.feature_player.presentation.layouts

import android.app.Activity
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.util.MediaUtils.shareAudioFile
import com.engfred.musicplayer.feature_player.presentation.components.ControlBar
import com.engfred.musicplayer.feature_player.presentation.components.FavoriteButton
import com.engfred.musicplayer.feature_player.presentation.components.PlayingQueueSection
import com.engfred.musicplayer.feature_player.presentation.components.QueueBottomSheet
import com.engfred.musicplayer.feature_player.presentation.components.SeekBarSection
import com.engfred.musicplayer.feature_player.presentation.components.TopBar
import com.engfred.musicplayer.feature_player.presentation.components.TrackInfo
import com.engfred.musicplayer.feature_player.presentation.viewmodel.PlayerEvent
import com.engfred.musicplayer.feature_player.utils.loadBitmapFromUri
import com.engfred.musicplayer.feature_player.utils.saveBitmapToPictures
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.launch
import com.engfred.musicplayer.core.domain.repository.RepeatMode as RM

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveCanvasLayout(
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

    // For Immersive mode, we force White content color because we will have a dark scrim over the image
    val defaultContentColor = Color.White

    // Handle status bar color
    DisposableEffect(isLandscape, selectedLayout) {
        val window = (context as? Activity)?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, view) }
        // Always light text/icons (dark status bar) for immersive mode
        insetsController?.isAppearanceLightStatusBars = false
        onDispose { }
    }

    // --- KEN BURNS ANIMATION STATE ---
    // Slow zoom in/out effect for the background art
    val infiniteTransition = rememberInfiniteTransition(label = "ken_burns")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f, // Zoom in 20%
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing), // 20 seconds
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "scale"
    )

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
            isPlaying = uiState.isPlaying
        )
    }

    var verticalDragCumulative by remember { mutableFloatStateOf(0f) }
    val dragThreshold = 100f

    CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black) // Fallback background
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(
                            label = "Skip to previous song",
                            action = {
                                onEvent(PlayerEvent.SkipToPrevious); view.performHapticFeedback(
                                HapticFeedbackConstants.KEYBOARD_TAP
                            ); true
                            }
                        ),
                        CustomAccessibilityAction(
                            label = "Skip to next song",
                            action = {
                                onEvent(PlayerEvent.SkipToNext); view.performHapticFeedback(
                                HapticFeedbackConstants.KEYBOARD_TAP
                            ); true
                            }
                        )
                    )
                }
                .pointerInput(Unit) {
                    var horizontalDragCumulative = 0f
                    val horizontalThreshold = 100f
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
                            horizontalDragCumulative += dragAmount; true
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
                            verticalDragCumulative += dragAmount; true
                        }
                    )
                }
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
        ) {
            // --- 1. FULL SCREEN ANIMATED BACKGROUND ---

            // Reusable Fallback Component (Defined here to use in both else branch and failure callback)
            val DefaultArtworkContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.DarkGray,
                                    Color(0xFF121212),
                                    Color.Black
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Audiotrack,
                        contentDescription = "Default Artwork",
                        modifier = Modifier
                            .size(200.dp)
                            .graphicsLayer {
                                // Apply the same Ken Burns breathe effect to the icon
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.5f // Increased alpha for better visibility
                            },
                        tint = Color.White
                    )
                }
            }

            val albumArtUri = uiState.currentAudioFile?.albumArtUri
            val hasValidArt = albumArtUri != null && albumArtUri.toString().isNotEmpty()

            if (hasValidArt) {
                CoilImage(
                    imageModel = { albumArtUri },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.8f // Slight dim to blend with black background
                        },
                    // If Coil fails to load the image (e.g. invalid URI), show fallback
                    failure = {
                        DefaultArtworkContent()
                    }
                )
            } else {
                // If No Art URI at all, show Default Music Note
                DefaultArtworkContent()
            }

            // --- 2. GRADIENT SCRIM (READABILITY LAYER) ---
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.7f), // Darker top for Status Bar
                                Color.Transparent,              // Clear center for Art
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.95f) // Dark bottom for Controls
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )

            if (!isLandscape) {
                // --- PORTRAIT UI ---
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
                    dynamicContentColor = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                )

                // Bottom Controls anchored to bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Info & Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TrackInfo(
                            title = uiState.currentAudioFile?.title,
                            artist = uiState.currentAudioFile?.artist,
                            playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        FavoriteButton(
                            isFavorite = uiState.isFavorite,
                            onToggleFavorite = {
                                uiState.currentAudioFile?.let {
                                    if (uiState.isFavorite) onEvent(
                                        PlayerEvent.RemoveFromFavorites(
                                            it.id
                                        )
                                    )
                                    else onEvent(PlayerEvent.AddToFavorites(it))
                                }
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            },
                            playerLayout = PlayerLayout.IMMERSIVE_CANVAS
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            uiState.currentAudioFile?.albumArtUri?.let { uri ->
                                coroutineScope.launch {
                                    val bitmap = loadBitmapFromUri(context, uri)
                                    if (bitmap != null) {
                                        val fname =
                                            uiState.currentAudioFile?.title?.replace(" ", "_")
                                                ?: "album_art"
                                        val success = saveBitmapToPictures(
                                            context,
                                            bitmap,
                                            "${fname}album_art.jpg",
                                            "image/jpeg"
                                        )
                                        val msg =
                                            if (success) "Album art saved!" else "Failed to save album art."
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "No album art found.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            } ?: Toast.makeText(
                                context,
                                "No artwork available.",
                                Toast.LENGTH_SHORT
                            ).show()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "Download",
                                tint = Color.White.copy(0.7f)
                            )
                        }

                        IconButton(onClick = {
                            if (currentSongIndex >= 0 && currentSongIndex < playingQueue.size) {
                                shareAudioFile(context, playingQueue[currentSongIndex])
                            }
                        }) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = Color.White.copy(0.7f)
                            )
                        }

                        IconButton(onClick = {
                            coroutineScope.launch { sheetState.show() }
                            showQueueBottomSheet = true
                        }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Queue",
                                tint = Color.White.copy(0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Main Controls
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
                        playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Seeker
                    SeekBarSection(
                        modifier = Modifier.padding(horizontal = 8.dp),
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
                        playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                        isPlaying = uiState.isPlaying
                    )
                }
            } else {
                // --- LANDSCAPE UI (CONTROLS FIXED AT BOTTOM, ALWAYS VISIBLE) ---
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT SIDE: Main container (TopBar + Spacer + Controls)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        // 1. Top Bar (Aligned to Top)
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
                            dynamicContentColor = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        )

                        // 2. Spacer pushes everything below it to the bottom
//                        Spacer(modifier = Modifier.weight(1f))

                        // 3. Persistent Controls Container (Aligned to Bottom)
                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Semi-transparent background for readability
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Info & Favorite
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TrackInfo(
                                        title = uiState.currentAudioFile?.title,
                                        artist = uiState.currentAudioFile?.artist,
                                        playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FavoriteButton(
                                        isFavorite = uiState.isFavorite,
                                        onToggleFavorite = {
                                            uiState.currentAudioFile?.let {
                                                if (uiState.isFavorite) onEvent(
                                                    PlayerEvent.RemoveFromFavorites(
                                                        it.id
                                                    )
                                                )
                                                else onEvent(PlayerEvent.AddToFavorites(it))
                                            }
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        },
                                        playerLayout = PlayerLayout.IMMERSIVE_CANVAS
                                    )
                                }

                                // Controls & Seeker in one Row for efficiency, or Column for standard stacked look
                                ControlBar(
                                    shuffleMode = uiState.shuffleMode,
                                    isPlaying = uiState.isPlaying,
                                    repeatMode = repeatMode,
                                    onPlayPauseClick = { onEvent(PlayerEvent.PlayPause) },
                                    onSkipPreviousClick = { onEvent(PlayerEvent.SkipToPrevious) },
                                    onSkipNextClick = { onEvent(PlayerEvent.SkipToNext) },
                                    onSetShuffleMode = { onEvent(PlayerEvent.SetShuffleMode(it)) },
                                    onSetRepeatMode = { onEvent(PlayerEvent.SetRepeatMode(it)) },
                                    playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                                    modifier = Modifier.weight(0.5f)
                                )

                                SeekBarSection(
                                    modifier = Modifier
                                        .padding(start = 16.dp),
                                    sliderValue = uiState.playbackPositionMs.toFloat(),
                                    totalDurationMs = uiState.totalDurationMs,
                                    playbackPositionMs = uiState.playbackPositionMs,
                                    onSliderValueChange = {
                                        onEvent(PlayerEvent.SetSeeking(true))
                                        onEvent(PlayerEvent.SeekTo(it.toLong()))
                                    },
                                    onSliderValueChangeFinished = {
                                        onEvent(
                                            PlayerEvent.SetSeeking(
                                                false
                                            )
                                        )
                                    },
                                    playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                                    isPlaying = uiState.isPlaying
                                )
                            }
                        }
                    }

                    // RIGHT SIDE: Queue
                    Column(
                        modifier = Modifier
                            .weight(0.8f)
                            .fillMaxHeight()
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        PlayingQueueSection(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp)),
                            playingQueue = playingQueue,
                            playingAudio = playingAudio,
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