package com.engfred.musicplayer.feature_video.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.ui.components.CastMediaRouteButton
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoPlayerEvent
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoPlayerState
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoResizeMode
import java.util.Locale

@Composable
fun VideoPlayerControls(
    state: VideoPlayerState,
    onEvent: (VideoPlayerEvent) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val currentPosition = state.playbackState.currentPositionMs
    val totalDuration = state.playbackState.totalDurationMs.coerceAtLeast(1L)
    val bufferedPosition = state.playbackState.bufferedPositionMs

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onEvent(VideoPlayerEvent.ToggleControls) }
            )
    ) {
        // ── 1. TV Cast Mode: Fixed Positioned Center Display (ZERO layout shift) ──
        if (state.isCastConnected) {
            // Anchor 1: Permanent TV Status Info (fixed position, never shifts)
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = if (isLandscape) (-46).dp else 0.dp)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // TV Icon with ambient glow
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = "Casting",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Receiver Name Headline e.g. "Casting on Android TV"
                val deviceName = state.castDeviceName
                val castTitle = if (!deviceName.isNullOrBlank()) "Casting on $deviceName" else "Casting on TV"

                Text(
                    text = castTitle,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    ),
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Video Title
                state.videoFile?.title?.let { title ->
                    Text(
                        text = title.replace('_', ' ').trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Cast status badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CastConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Anchor 2: Playback Buttons Row positioned at fixed slot below TV info (zero layout shift)
            AnimatedVisibility(
                visible = state.areControlsVisible && !state.isLocked && !state.playbackState.isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = if (isLandscape) 110.dp else 62.dp)
            ) {
                PlaybackButtonsRow(
                    state = state,
                    onEvent = onEvent
                )
            }
        }

        // Center Buffering Spinner
        if (state.playbackState.isLoading) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(16.dp)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.5.dp,
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        // Locked Screen State
        if (state.isLocked) {
            AnimatedVisibility(
                visible = state.areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Surface(
                    onClick = { onEvent(VideoPlayerEvent.ToggleLock) },
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Unlock Controls",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Unlock",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
            return@Box
        }

        // ── 2. Interactive Overlay Controls (Top Bar, Bottom Scrubber, Local center buttons) ──
        AnimatedVisibility(
            visible = state.areControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f)
                            )
                        )
                    )
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Transparent
                                )
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = state.videoFile?.title?.replace('_', ' ')?.trim() ?: "Video Player",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.2.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Playback Speed Button (Pill shape)
                        Surface(
                            onClick = { onEvent(VideoPlayerEvent.ShowSpeedDialog(true)) },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = "${state.playbackSpeed}x",
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Cast Button (Transparent background, pure icon)
                        CastMediaRouteButton(
                            tintColor = if (state.isCastConnected) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Center Controls for Local Phone Playback (when NOT casting)
                if (!state.isCastConnected && !state.playbackState.isLoading) {
                    PlaybackButtonsRow(
                        state = state,
                        onEvent = onEvent,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Bottom Bar: Scrubber, Timestamps & Quick Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.9f)
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // High-End Custom Video Scrubber
                    VideoScrubber(
                        currentPositionMs = currentPosition,
                        totalDurationMs = totalDuration,
                        bufferedPositionMs = bufferedPosition,
                        onSeek = { targetMs -> onEvent(VideoPlayerEvent.SeekTo(targetMs)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Bottom info & action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(totalDuration)}",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Resize Mode Button
                            IconButton(
                                onClick = { onEvent(VideoPlayerEvent.ToggleResizeMode) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                val resizeIcon = when (state.resizeMode) {
                                    VideoResizeMode.FIT -> Icons.Rounded.FitScreen
                                    VideoResizeMode.FILL -> Icons.Rounded.AspectRatio
                                    VideoResizeMode.ZOOM -> Icons.Rounded.Fullscreen
                                }
                                Icon(
                                    imageVector = resizeIcon,
                                    contentDescription = "Resize Mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Lock Screen Button
                            IconButton(
                                onClick = { onEvent(VideoPlayerEvent.ToggleLock) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LockOpen,
                                    contentDescription = "Lock Controls",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Orientation / Landscape Toggle Button
                            IconButton(
                                onClick = {
                                    val activity = context.findActivity()
                                    when {
                                        isLandscape && activity != null -> {
                                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        }
                                        state.videoFile?.isPortraitVideo() == true -> {
                                            onEvent(VideoPlayerEvent.SetFullscreen(!state.isFullscreen))
                                        }
                                        activity != null -> {
                                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        state.isFullscreen && !isLandscape -> Icons.Rounded.FullscreenExit
                                        state.videoFile?.isPortraitVideo() == true && !isLandscape -> Icons.Rounded.Fullscreen
                                        else -> Icons.Rounded.ScreenRotation
                                    },
                                    contentDescription = when {
                                        state.isFullscreen && !isLandscape -> "Exit Fullscreen"
                                        state.videoFile?.isPortraitVideo() == true && !isLandscape -> "Fullscreen"
                                        else -> "Rotate Screen"
                                    },
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Speed Selection Dialog
        if (state.showSpeedDialog) {
            val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            AlertDialog(
                onDismissRequest = { onEvent(VideoPlayerEvent.ShowSpeedDialog(false)) },
                containerColor = Color(0xFF1C1C24),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = "Playback Speed",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        speedOptions.forEach { speed ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onEvent(VideoPlayerEvent.SetPlaybackSpeed(speed)) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.playbackSpeed == speed,
                                    onClick = { onEvent(VideoPlayerEvent.SetPlaybackSpeed(speed)) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = Color.White.copy(alpha = 0.5f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (speed == 1.0f) "1.0x (Normal)" else "${speed}x",
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onEvent(VideoPlayerEvent.ShowSpeedDialog(false)) }) {
                        Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun PlaybackButtonsRow(
    state: VideoPlayerState,
    onEvent: (VideoPlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(0.7f),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onEvent(VideoPlayerEvent.SeekBy(-10000L)) },
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                .size(46.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay10,
                contentDescription = "Rewind 10s",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }

        // High-end frosted glow play button
        VideoPlayButton(
            isPlaying = state.playbackState.isPlaying,
            isEnded = state.playbackState.isEnded,
            onClick = { onEvent(VideoPlayerEvent.TogglePlayPause) },
            size = 62.dp
        )

        IconButton(
            onClick = { onEvent(VideoPlayerEvent.SeekBy(10000L)) },
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                .size(46.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Forward10,
                contentDescription = "Forward 10s",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}


private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun com.engfred.musicplayer.core.domain.model.VideoFile.isPortraitVideo(): Boolean {
    val measuredWidth = width
    val measuredHeight = height
    if (measuredWidth != null && measuredHeight != null) {
        return measuredHeight > measuredWidth
    }

    val dimensions = resolution
        ?.lowercase(Locale.getDefault())
        ?.split("x", "×")
        ?.mapNotNull { it.trim().toIntOrNull() }

    return dimensions?.takeIf { it.size == 2 }?.let { (w, h) -> h > w } == true
}
