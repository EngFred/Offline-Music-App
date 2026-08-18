package com.engfred.musicplayer.core.ui.components

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioFile

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrev: () -> Unit,
    onToggleStopAfterCurrent: () -> Unit,
    stopAfterCurrent: Boolean,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean,
    playbackPositionMs: Long = 0L,
    totalDurationMs: Long = 0L,
    castState: com.engfred.musicplayer.core.domain.model.CastState = com.engfred.musicplayer.core.domain.model.CastState.DISCONNECTED,
    castDeviceName: String? = null
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    AnimatedVisibility(
        visible = playingAudioFile != null || isPlaying,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        playingAudioFile?.let { audioFile ->
            val cardHeight = if (isLandscape) 80.dp else 64.dp

            val cleanTitle = remember(audioFile.title) {
                audioFile.title.replace('_', ' ').trim()
            }
            val cleanArtist = remember(audioFile.artist) {
                audioFile.artist?.replace('_', ' ')?.trim() ?: "Unknown Artist"
            }
            val castDestination = castDeviceName?.takeIf { it.isNotBlank() } ?: "TV"
            val isCasting = castState == com.engfred.musicplayer.core.domain.model.CastState.CONNECTED

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onClick() }
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .semantics { contentDescription = "Open full player" },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Rotating Album Art
                        RotatingAlbumArt(
                            imageModel = audioFile.albumArtUri,
                            size = 42.dp,
                            trackId = audioFile.id,
                            isRotating = isPlaying
                        )

                        // 2. Track Info
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isCasting) {
                                    Icon(
                                        imageVector = Icons.Rounded.CastConnected,
                                        contentDescription = "Casting to device",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = cleanTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isCasting) "Casting to $castDestination" else cleanArtist,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = if (isCasting) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 3. Play / Pause & Skip Next Actions
                        IconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = onPlayNext,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next song",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // 4. Edge Progress Bar
                    val targetProgress = if (totalDurationMs > 0) playbackPositionMs.toFloat() / totalDurationMs else 0f
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
                        label = "miniPlayerProgress"
                    )

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}
