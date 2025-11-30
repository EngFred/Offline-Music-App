package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.RepeatOneOn
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_player.R
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.engfred.musicplayer.core.domain.model.PlayerLayout

@Composable
fun ControlBar(
    shuffleMode: ShuffleMode,
    isPlaying: Boolean,
    repeatMode: RepeatMode,
    onPlayPauseClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSetShuffleMode: (ShuffleMode) -> Unit,
    onSetRepeatMode: (RepeatMode) -> Unit,
    playerLayout: PlayerLayout,
    modifier: Modifier = Modifier,
    addExtraSpaceBetweenButtons: Boolean = false
) {

    when (playerLayout) {
        PlayerLayout.IMMERSIVE_CANVAS -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaybackControlIconButton(
                    iconVector = Icons.Rounded.Shuffle,
                    iconResId = null,
                    contentDescription = "Toggle Shuffle Mode",
                    onClick = {
                        val newMode = if (shuffleMode == ShuffleMode.ON) ShuffleMode.OFF else ShuffleMode.ON
                        onSetShuffleMode(newMode)
                    },
                    tint = if (shuffleMode == ShuffleMode.ON) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.7f),
                    size = 35.dp,
                    buttonSize = 48.dp
                )
                if (addExtraSpaceBetweenButtons) {
                    Spacer(modifier = Modifier.width(16.dp))
                }
                PlaybackControlIconButton(
                    iconVector = Icons.Rounded.SkipPrevious,
                    iconResId = null,
                    contentDescription = "Skip Previous Song",
                    onClick = onSkipPreviousClick,
                    tint = LocalContentColor.current,
                    size = 60.dp,
                    buttonSize = 60.dp
                )
                if (addExtraSpaceBetweenButtons) {
                    Spacer(modifier = Modifier.width(16.dp))
                }
                PlaybackControlIconButton(
                    iconVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    iconResId = null,
                    contentDescription = if (isPlaying) "Pause Playback" else "Play Playback",
                    onClick = onPlayPauseClick,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = 45.dp,
                    buttonSize = 60.dp,
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    scaleAnimation = true,
                    isPlaying = isPlaying
                )
                if (addExtraSpaceBetweenButtons) {
                    Spacer(modifier = Modifier.width(16.dp))
                }
                PlaybackControlIconButton(
                    iconVector = Icons.Rounded.SkipNext,
                    iconResId = null,
                    contentDescription = "Skip Next Song",
                    onClick = onSkipNextClick,
                    tint = LocalContentColor.current,
                    size = 60.dp,
                    buttonSize = 64.dp
                )
                if (addExtraSpaceBetweenButtons) {
                    Spacer(modifier = Modifier.width(16.dp))
                }

                // Immersive uses vector icons (unchanged)
                PlaybackControlIconButton(
                    iconVector = when (repeatMode) {
                        RepeatMode.OFF -> Icons.Rounded.Repeat
                        RepeatMode.ALL -> Icons.Rounded.Repeat
                        RepeatMode.ONE -> Icons.Rounded.RepeatOne
                    },
                    // we want the Immersive layout to use vector Repeat icons; choose proper vectors inline
                    iconResId = null,
                    contentDescription = "Toggle Repeat Mode",
                    onClick = {
                        val newMode = when (repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        }
                        onSetRepeatMode(newMode)
                    },
                    tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.7f),
                    size = 34.dp,
                    buttonSize = 58.dp
                )
            }
        }
        PlayerLayout.MINIMALIST_GROOVE -> {
            // For Minimalist: use your drawable repeat icons (repeat.png / repeat_once.png)
            val repeatDrawable = when (repeatMode) {
                RepeatMode.ONE -> R.drawable.repeat_once
                else -> R.drawable.repeat
            }

            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaybackControlIconButton(
                    iconVector = Icons.Rounded.Shuffle,
                    iconResId = null,
                    contentDescription = "Toggle Shuffle Mode",
                    onClick = {
                        val newMode = if (shuffleMode == ShuffleMode.ON) ShuffleMode.OFF else ShuffleMode.ON
                        onSetShuffleMode(newMode)
                    },
                    tint = if (shuffleMode == ShuffleMode.ON) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.7f),
                    size = 24.dp,
                    buttonSize = 40.dp,
                )
                PlaybackControlIconButton(
                    iconVector = Icons.Rounded.SkipPrevious,
                    iconResId = null,
                    contentDescription = "Skip Previous Song",
                    onClick = onSkipPreviousClick,
                    tint = LocalContentColor.current,
                    size = 40.dp,
                    buttonSize = 52.dp
                )
                PlaybackControlIconButton(
                    iconVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    iconResId = null,
                    contentDescription = if (isPlaying) "Pause Playback" else "Play Playback",
                    onClick = onPlayPauseClick,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = 40.dp,
                    buttonSize = 50.dp,
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    scaleAnimation = true,
                    isPlaying = isPlaying
                )
                PlaybackControlIconButton(
                    iconVector = Icons.Rounded.SkipNext,
                    iconResId = null,
                    contentDescription = "Skip Next Song",
                    onClick = onSkipNextClick,
                    tint = LocalContentColor.current,
                    size = 40.dp,
                    buttonSize = 52.dp
                )

                PlaybackControlIconButton(
                    iconVector = null,
                    iconResId = repeatDrawable,
                    contentDescription = "Toggle Repeat Mode",
                    onClick = {
                        val newMode = when (repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        }
                        onSetRepeatMode(newMode)
                    },
                    tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.7f),
                    size = 27.dp,
                    buttonSize = 40.dp,
                )
            }
        }
        else -> {
            // This branch covers Ethereal (and other non-immersive) — use drawable repeat icons here too.
            val repeatDrawable = when (repeatMode) {
                RepeatMode.ONE -> R.drawable.repeat_once
                else -> R.drawable.repeat
            }

            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(LocalContentColor.current.copy(alpha = 0.08f))
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle with smaller white circular background
                    PlaybackControlIconButton(
                        iconVector = Icons.Rounded.Shuffle,
                        iconResId = null,
                        contentDescription = "Toggle Shuffle Mode",
                        onClick = {
                            val newShuffleMode = if (shuffleMode == ShuffleMode.ON) ShuffleMode.OFF else ShuffleMode.ON
                            onSetShuffleMode(newShuffleMode)
                        },
                        tint = LocalContentColor.current,
                        size = 24.dp,
                        buttonSize = 40.dp,
                        backgroundColor = if (shuffleMode == ShuffleMode.ON) MaterialTheme.colorScheme.primary else Color.Transparent
                    )

                    PlaybackControlIconButton(
                        iconVector = Icons.Rounded.SkipPrevious,
                        iconResId = null,
                        contentDescription = "Skip Previous Song",
                        onClick = onSkipPreviousClick,
                        tint = LocalContentColor.current,
                        size = 40.dp,
                        buttonSize = 52.dp
                    )

                    PlaybackControlIconButton(
                        iconVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        iconResId = null,
                        contentDescription = if (isPlaying) "Pause Playback" else "Play Playback",
                        onClick = onPlayPauseClick,
                        tint = LocalContentColor.current,
                        size = 40.dp,
                        buttonSize = 50.dp,
                        backgroundColor = MaterialTheme.colorScheme.primary,
                        scaleAnimation = true,
                        isPlaying = isPlaying
                    )

                    PlaybackControlIconButton(
                        iconVector = Icons.Rounded.SkipNext,
                        iconResId = null,
                        contentDescription = "Skip Next Song",
                        onClick = onSkipNextClick,
                        tint = LocalContentColor.current,
                        size = 40.dp,
                        buttonSize = 52.dp
                    )

                    // Repeat uses drawable in Ethereal/other branch
                    PlaybackControlIconButton(
                        iconVector = null,
                        iconResId = repeatDrawable,
                        contentDescription = "Toggle Repeat Mode",
                        onClick = {
                            val newRepeatMode = when (repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            }
                            onSetRepeatMode(newRepeatMode)
                        },
                        tint = LocalContentColor.current,
                        size = 27.dp,
                        buttonSize = 40.dp,
                        backgroundColor = if(repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackControlIconButton(
    iconVector: ImageVector? = null,
    iconResId: Int? = null,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color,
    size: Dp,
    buttonSize: Dp,
    backgroundColor: Color = Color.Transparent,
    scaleAnimation: Boolean = false,
    isPlaying: Boolean = false
) {
    val scale by animateFloatAsState(
        targetValue = if (scaleAnimation && isPlaying) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "buttonScale"
    )

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .aspectRatio(1f) // Ensure square shape
            .clip(CircleShape)
            .background(backgroundColor)
            .graphicsLayer {
                if (scaleAnimation) {
                    scaleX = scale
                    scaleY = scale
                }
            }
    ) {
        if (iconResId != null) {
            // Drawable painter (PNG)
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size)
            )
        } else if (iconVector != null) {
            // Vector
            Icon(
                imageVector = iconVector,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size)
            )
        } else {
            // Fallback empty space (shouldn't happen)
            Spacer(modifier = Modifier.size(size))
        }
    }
}
