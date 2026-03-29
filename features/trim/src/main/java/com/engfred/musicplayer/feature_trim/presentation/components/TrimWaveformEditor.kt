package com.engfred.musicplayer.feature_trim.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.util.MediaUtils.formatDuration
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimWaveformEditor(
    modifier: Modifier = Modifier,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    currentPositionMs: Long,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    // Generate dummy waveform data once (Replace with actual audio amplitude data)
    val amplitudes = remember { List(60) { Random.nextFloat() * 0.8f + 0.2f } }

    Column(modifier = modifier.fillMaxWidth()) {
        // Floating Timestamps above the waveform
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatDuration(startMs), style = MaterialTheme.typography.labelLarge, color = primaryColor)
            Text(text = formatDuration(endMs), style = MaterialTheme.typography.labelLarge, color = primaryColor)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(surfaceVariant.copy(alpha = 0.5f))
        ) {
            // 1. Draw the Waveform and Playhead
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barWidth = canvasWidth / amplitudes.size
                val gap = 4f

                // Map times to canvas pixels
                val startPx = (startMs.toFloat() / durationMs.toFloat()) * canvasWidth
                val endPx = (endMs.toFloat() / durationMs.toFloat()) * canvasWidth
                val currentPx = (currentPositionMs.toFloat() / durationMs.toFloat()) * canvasWidth

                // Draw amplitude bars
                amplitudes.forEachIndexed { index, amplitude ->
                    val x = index * barWidth
                    val barHeight = canvasHeight * amplitude
                    val y = (canvasHeight - barHeight) / 2

                    // Color logic: active inside trim bounds, dimmed outside
                    val isActive = x in startPx..endPx
                    val barColor = if (isActive) primaryColor else surfaceVariant

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth - gap, barHeight),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }

                // Draw Scrubber (Playhead)
                if (currentPositionMs in startMs..endMs) {
                    drawRect(
                        color = onSurface,
                        topLeft = Offset(currentPx, 0f),
                        size = Size(4f, canvasHeight)
                    )
                }
            }

            // 2. The invisible slider on top to handle standard Android touch/drag accessibility
            RangeSlider(
                value = startMs.toFloat()..endMs.toFloat(),
                onValueChange = { range ->
                    onStartChange(range.start.toLong())
                    onEndChange(range.endInclusive.toLong())
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor,
                    activeTrackColor = Color.Transparent, // Track drawn by canvas
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}