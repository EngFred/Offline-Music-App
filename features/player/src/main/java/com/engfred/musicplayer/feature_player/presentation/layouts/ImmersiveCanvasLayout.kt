package com.engfred.musicplayer.feature_player.presentation.layouts

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.util.MediaUtils.shareAudioFile
import com.engfred.musicplayer.feature_player.presentation.components.ControlBar
import com.engfred.musicplayer.feature_player.presentation.components.FavoriteButton
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    repeatMode: RM,
    customBackgroundUri: String?,
    onCustomBackgroundSelected: (String?) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val contentResolver = context.contentResolver

    val defaultContentColor = Color.White

    DisposableEffect(isLandscape, selectedLayout) {
        val window = (context as? Activity)?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, view) }
        insetsController?.isAppearanceLightStatusBars = false
        onDispose { }
    }

    // Slow Ken Burns background zoom
    val infiniteTransition = rememberInfiniteTransition(label = "ken_burns")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "scale"
    )

    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            onCustomBackgroundSelected(uri.toString())
        }
    }

    val displayBackgroundUri: Any? = customBackgroundUri
        ?: uiState.currentAudioFile?.albumArtUri

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
    var horizontalDragCumulative by remember { mutableFloatStateOf(0f) }
    val dragThreshold = 100f
    val horizontalThreshold = 100f

    var isSeeking by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                            } else if (verticalDragCumulative < -dragThreshold) {
                                coroutineScope.launch { sheetState.show() }
                                showQueueBottomSheet = true
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
            // Layer 1: Full-Screen Backdrop
            val DefaultArtworkContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E1B2E), Color(0xFF0F0D1B), Color.Black)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Audiotrack,
                        contentDescription = "Default artwork",
                        modifier = Modifier
                            .size(180.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.4f
                            },
                        tint = Color.White
                    )
                }
            }

            if (displayBackgroundUri != null) {
                CoilImage(
                    imageModel = { displayBackgroundUri },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.85f
                        },
                    failure = {
                        if (customBackgroundUri != null && uiState.currentAudioFile?.albumArtUri != null) {
                            CoilImage(
                                imageModel = { uiState.currentAudioFile?.albumArtUri },
                                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = 0.85f
                                    },
                                failure = { DefaultArtworkContent() }
                            )
                        } else {
                            DefaultArtworkContent()
                        }
                    }
                )
            } else {
                DefaultArtworkContent()
            }

            // Layer 2: Scrim Overlay (Top scrim + Bottom shadow)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Black.copy(alpha = 0.15f),
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.95f)
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )

            // Layer 3: Controls
            if (!isLandscape) {
                // Portrait Mode
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

                // Translucent Frosted Glass Control Card at bottom
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.15f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                                        if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                        else onEvent(PlayerEvent.AddToFavorites(it))
                                    }
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                },
                                playerLayout = PlayerLayout.IMMERSIVE_CANVAS
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

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
                                            val fname = uiState.currentAudioFile?.title
                                                ?.replace(" ", "_") ?: "album_art"
                                            val success = saveBitmapToPictures(
                                                context, bitmap, "${fname}_album_art.jpg", "image/jpeg"
                                            )
                                            Toast.makeText(
                                                context,
                                                if (success) "Album art saved!" else "Failed to save.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(context, "No album art found.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } ?: Toast.makeText(context, "No artwork available.", Toast.LENGTH_SHORT).show()
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }) {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = "Download album art",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
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
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(onClick = {
                                coroutineScope.launch { sheetState.show() }
                                showQueueBottomSheet = true
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = "Queue",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .combinedClickable(
                                        onClick = { backgroundPicker.launch(arrayOf("image/*")) },
                                        onLongClick = {
                                            onCustomBackgroundSelected(null)
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            Toast.makeText(
                                                context,
                                                "Background reset to album art",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (customBackgroundUri != null)
                                        Icons.Rounded.Wallpaper
                                    else
                                        Icons.Outlined.Wallpaper,
                                    contentDescription = "Background options",
                                    tint = if (customBackgroundUri != null)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

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
                            playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
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
                            playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

            } else {
                // Landscape Mode
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.Start
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
                            dynamicContentColor = Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TrackInfo(
                            title = uiState.currentAudioFile?.title,
                            artist = uiState.currentAudioFile?.artist,
                            playerLayout = PlayerLayout.IMMERSIVE_CANVAS
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .padding(16.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
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
                                playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

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
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}