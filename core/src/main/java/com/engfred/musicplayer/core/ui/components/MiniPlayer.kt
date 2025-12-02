package com.engfred.musicplayer.core.ui.components

import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.engfred.musicplayer.core.domain.model.AudioFile

@OptIn(UnstableApi::class)
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
    totalDurationMs: Long = 0L
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    AnimatedVisibility(
        visible = playingAudioFile != null || isPlaying,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = modifier
    ) {
        playingAudioFile?.let { audioFile ->
            val cardHeight = if (isLandscape.not()) 72.dp else 88.dp
            val horizontalCardPadding = if (isLandscape.not()) 12.dp else 24.dp
            val contentHorizontalPadding = if (isLandscape.not()) 12.dp else 20.dp

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .padding(horizontal = horizontalCardPadding, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onClick() }
                    .semantics { contentDescription = "Open full player" },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp).copy(alpha = 0.95f)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = contentHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RotatingAlbumArt(
                            imageModel = audioFile.albumArtUri,
                            size = if (isLandscape.not()) 48.dp else 64.dp,
                            trackId = audioFile.id,
                            isRotating = isPlaying
                        )
                        Spacer(modifier = Modifier.width(if (isLandscape.not()) 12.dp else 16.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = audioFile.title,
                                style = if (isLandscape.not()) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = audioFile.artist ?: "Unknown Artist",
                                style = if (isLandscape.not()) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly // Even spacing for better balance
                        ) {
                            // Prev
                            IconButton(
                                onClick = { onPlayPrev() },
                                modifier = Modifier.size(if (isLandscape.not()) 48.dp else 52.dp) // Larger touch target
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipPrevious,
                                    contentDescription = "Previous song",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(if (isLandscape.not()) 32.dp else 36.dp)
                                )
                            }

                            // Play/Pause (larger for emphasis)
                            IconButton(
                                onClick = { onPlayPause() },
                                modifier = Modifier.size(if (isLandscape.not()) 48.dp else 52.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (isLandscape.not()) 40.dp else 44.dp) // Slightly larger
                                )
                            }

                            // Next
                            IconButton(
                                onClick = { onPlayNext() },
                                modifier = Modifier.size(if (isLandscape.not()) 48.dp else 52.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Next song",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(if (isLandscape.not()) 32.dp else 36.dp)
                                )
                            }

                            // Stop After Current (moved to end; slightly smaller as secondary action)
                            val stopIconColor by animateColorAsState(
                                targetValue = if (stopAfterCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                label = "stopIconColor"
                            )
                            IconButton(
                                onClick = onToggleStopAfterCurrent,
                                modifier = Modifier.size(if (isLandscape.not()) 48.dp else 52.dp)
                            ) {
                                Icon(
                                    imageVector = if (stopAfterCurrent) Icons.Rounded.HourglassBottom else Icons.Rounded.HourglassEmpty,
                                    contentDescription = if (stopAfterCurrent) "Disable stop after current song" else "Stop after current song",
                                    tint = stopIconColor,
                                    modifier = Modifier.size(if (isLandscape.not()) 24.dp else 28.dp) // Slightly larger icon for visibility
                                )
                            }
                        }
                    }

                    val targetProgress = if (totalDurationMs > 0) playbackPositionMs.toFloat() / totalDurationMs else 0f

                    // Smooth out the progress bar movement
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = tween(durationMillis = 500, easing = LinearEasing), // Match update interval
                        label = "miniPlayerProgress"
                    )

                    LinearProgressIndicator(
                        progress = { animatedProgress }, // Use animated value
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}