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

    // ── Ken Burns animation ───────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "ken_burns")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "scale"
    )

    // ── Custom background picker ──────────────────────────────────────────────
    // OpenDocument (not GetContent) returns a persistable URI that survives
    // process death. takePersistableUriPermission locks read access permanently.
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onCustomBackgroundSelected(uri.toString())
        }
    }

    // ── Background URI resolution ─────────────────────────────────────────────
    // Priority: user-chosen custom image > current track album art > null (gradient fallback).
    val displayBackgroundUri: Any? = when {
        customBackgroundUri != null -> Uri.parse(customBackgroundUri)
        else -> uiState.currentAudioFile?.albumArtUri
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
            isPlaying = uiState.isPlaying
        )
    }

    var verticalDragCumulative by remember { mutableFloatStateOf(0f) }
    val dragThreshold = 100f

    CompositionLocalProvider(LocalContentColor provides defaultContentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
            // ── 1. Full-screen animated background ────────────────────────────
            val DefaultArtworkContent: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.DarkGray, Color(0xFF121212), Color.Black)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Audiotrack,
                        contentDescription = "Default artwork",
                        modifier = Modifier
                            .size(200.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.5f
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
                            alpha = 0.8f
                        },
                    failure = { DefaultArtworkContent() }
                )
            } else {
                DefaultArtworkContent()
            }

            // ── 2. Gradient scrim ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.7f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.95f)
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )

            if (!isLandscape) {
                // ── Portrait UI ───────────────────────────────────────────────
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

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                        .navigationBarsPadding(),
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

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Download album art
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
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }

                        // Share
                        IconButton(onClick = {
                            if (currentSongIndex >= 0 && currentSongIndex < playingQueue.size) {
                                shareAudioFile(context, playingQueue[currentSongIndex])
                            }
                        }) {
                            Icon(
                                Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }

                        // Queue
                        IconButton(onClick = {
                            coroutineScope.launch { sheetState.show() }
                            showQueueBottomSheet = true
                        }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Queue",
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }

                        // Custom background picker.
                        // Tap  → open image picker.
                        // Long-press → clear custom image, revert to album art.
                        Box(
                            modifier = Modifier
                                .size(48.dp)
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
                                contentDescription = if (customBackgroundUri != null)
                                    "Custom background active — long-press to reset"
                                else
                                    "Set custom background image",
                                tint = if (customBackgroundUri != null)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

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
                        onSetShuffleMode = { onEvent(PlayerEvent.SetShuffleMode(it)) },
                        onSetRepeatMode = { onEvent(PlayerEvent.SetRepeatMode(it)) },
                        playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                // ── Landscape UI ──────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
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
                            dynamicContentColor = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        )

                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
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
                                                if (uiState.isFavorite) onEvent(PlayerEvent.RemoveFromFavorites(it.id))
                                                else onEvent(PlayerEvent.AddToFavorites(it))
                                            }
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        },
                                        playerLayout = PlayerLayout.IMMERSIVE_CANVAS
                                    )
                                }

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
                                    modifier = Modifier.padding(start = 16.dp),
                                    sliderValue = uiState.playbackPositionMs.toFloat(),
                                    totalDurationMs = uiState.totalDurationMs,
                                    playbackPositionMs = uiState.playbackPositionMs,
                                    onSliderValueChange = {
                                        onEvent(PlayerEvent.SetSeeking(true))
                                        onEvent(PlayerEvent.SeekTo(it.toLong()))
                                    },
                                    onSliderValueChangeFinished = {
                                        onEvent(PlayerEvent.SetSeeking(false))
                                    },
                                    playerLayout = PlayerLayout.IMMERSIVE_CANVAS,
                                    isPlaying = uiState.isPlaying
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.8f)
                            .fillMaxHeight()
                            .padding(8.dp)
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