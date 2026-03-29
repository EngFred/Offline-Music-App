package com.engfred.musicplayer.feature_trim.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_trim.presentation.TrimUiState

@Composable
fun TrimPlaybackControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSeekToStart: () -> Unit,
    onReset: () -> Unit,
    isTrimming: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reset Button
        IconButton(onClick = onReset, enabled = !isTrimming) {
            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Reset Trim", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Play/Pause (Hero Button)
        FloatingActionButton(
            onClick = onTogglePlay,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(0.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // Seek to Start
        IconButton(onClick = onSeekToStart) {
            Icon(imageVector = Icons.Rounded.Replay, contentDescription = "Seek to Start", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TrimBottomAction(
    state: TrimUiState,
    onSaveClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = Color.Transparent
    ) {
        val trimDurationMs = state.endTimeMs - state.startTimeMs
        val isTrimmed = state.audioFile != null && trimDurationMs < state.audioFile.duration
        val hasCriticalError = state.error != null && !state.error.contains("File too large")

        val canSave = !state.isTrimming && !hasCriticalError && trimDurationMs >= 30_000L && state.trimResult == null && isTrimmed

        if (state.isTrimming) {
            // Re-using your custom loading indicator but constrained nicely inside the bottom bar area
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    TrimLoadingIndicator(
                        title = "Trimming Audio...",
                        subtitle = "Please do not close the app"
                    )
                }
            }
        } else {
            Button(
                onClick = onSaveClicked,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp), // Premium tall button
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(
                    text = if (state.trimResult != null) "Saved Successfully" else "Save Trimmed Audio",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}