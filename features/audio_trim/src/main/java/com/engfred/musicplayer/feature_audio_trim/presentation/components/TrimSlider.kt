package com.engfred.musicplayer.feature_audio_trim.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.util.MediaUtils.formatDuration

@Composable
fun TrimSlider(
    modifier: Modifier = Modifier,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    currentPositionMs: Long,
    isPlaying: Boolean,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekToStart: () -> Unit
) {

    val colorScheme = MaterialTheme.colorScheme

    // Compute relative position for accurate bottom display
    val relativePositionMs = (currentPositionMs - startMs).coerceAtLeast(0L)
    val trimDurationMs = (endMs - startMs).coerceAtLeast(1L)

    Column(modifier = modifier) {
        // time labels
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Start: ${formatDuration(startMs)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { contentDescription = "Trim start time: ${formatDuration(startMs)}" }
            )
            Text(
                text = "Now: ${formatDuration(currentPositionMs)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { contentDescription = "Current playback position: ${formatDuration(currentPositionMs)}" }
            )
            Text(
                text = "End: ${formatDuration(endMs)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { contentDescription = "Trim end time: ${formatDuration(endMs)}" }
            )
        }

        // Range slider: we map ms -> 0f..duration
        RangeSlider(
            value = startMs.toFloat()..endMs.toFloat(),
            onValueChange = {
                onStartChange(it.start.toLong())
                onEndChange(it.endInclusive.toLong())
            },
            valueRange = 0f..durationMs.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Adjust trim range from ${formatDuration(startMs)} to ${formatDuration(endMs)}" }
        )

        // playback position indicator (drawn in a small canvas strip)
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = "Playback progress bar within trim range" }) {
            val widthPx = size.width
            val left = (startMs.toFloat() / durationMs.toFloat()) * widthPx
            val right = (endMs.toFloat() / durationMs.toFloat()) * widthPx
            // background track between start and end
            drawLine(
                color = colorScheme.surfaceVariant,
                start = Offset(left, size.height / 2f),
                end = Offset(right, size.height / 2f),
                strokeWidth = 6f
            )

            // current position indicator relative to start/end
            val rel = relativePositionMs.toFloat() / trimDurationMs.toFloat()
            val posX = left + rel * (right - left)
            drawLine(
                color = colorScheme.primary,
                start = Offset(posX, 0f),
                end = Offset(posX, size.height),
                strokeWidth = 3f
            )
        }

        // controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.semantics { contentDescription = if (isPlaying) "Pause preview" else "Play preview" }
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null // Handled by button semantics
                    )
                }
                IconButton(
                    onClick = onSeekToStart,
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = "Seek to start of trim" }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = null // Handled by button semantics
                    )
                }
            }

            Text(
                text = "${formatDuration(relativePositionMs)} / ${formatDuration(trimDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { contentDescription = "Relative playback time: ${formatDuration(relativePositionMs)} of ${formatDuration(trimDurationMs)}" }
            )
        }
    }
}