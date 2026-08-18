package com.engfred.musicplayer.feature_trim.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val trimDurationMs = (endMs - startMs).coerceAtLeast(0L)

    val amplitudes = remember { List(60) { Random.nextFloat() * 0.8f + 0.2f } }

    Column(modifier = modifier.fillMaxWidth()) {
        // Floating Timestamps & Duration Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDuration(startMs),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = primaryColor
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = primaryColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = "${formatDuration(trimDurationMs)} selected",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    color = primaryColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Text(
                text = formatDuration(endMs),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = primaryColor
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Waveform container with custom drag handles
        val density = LocalDensity.current
        var containerWidthPx = remember { 0f }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceVariant.copy(alpha = 0.35f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp)
                )
                .onSizeChanged { containerWidthPx = it.width.toFloat() }
        ) {
            val horizontalPaddingPx = with(density) { 8.dp.toPx() }

            // 1. Draw Waveform and Playhead
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barWidth = canvasWidth / amplitudes.size
                val gap = 4f

                val startPx = (startMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()) * canvasWidth
                val endPx = (endMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()) * canvasWidth
                val currentPx = (currentPositionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()) * canvasWidth

                // Draw dimmed overlay outside selection
                drawRect(
                    color = Color.Black.copy(alpha = 0.25f),
                    topLeft = Offset(0f, 0f),
                    size = Size(startPx, canvasHeight)
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.25f),
                    topLeft = Offset(endPx, 0f),
                    size = Size(canvasWidth - endPx, canvasHeight)
                )

                amplitudes.forEachIndexed { index, amplitude ->
                    val x = index * barWidth
                    val barHeight = canvasHeight * amplitude
                    val y = (canvasHeight - barHeight) / 2

                    val isActive = x in startPx..endPx
                    val barColor = if (isActive) primaryColor else surfaceVariant.copy(alpha = 0.8f)

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size((barWidth - gap).coerceAtLeast(1f), barHeight),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }

                // Playhead indicator
                if (currentPositionMs in startMs..endMs) {
                    drawRoundRect(
                        color = onSurface,
                        topLeft = Offset(currentPx - 1.5f, 0f),
                        size = Size(3f, canvasHeight),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }

            // 2. Draggable Start Handle
            val usableWidth = containerWidthPx - (horizontalPaddingPx * 2)
            val startFraction = startMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()
            val endFraction = endMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()

            val handleWidthDp = 22.dp
            val handleWidthPx = with(density) { handleWidthDp.toPx() }

            // Start handle
            if (usableWidth > 0f) {
                val startOffsetDp = with(density) {
                    ((horizontalPaddingPx + startFraction * usableWidth) - handleWidthPx / 2).toDp()
                }
                Box(
                    modifier = Modifier
                        .offset(x = startOffsetDp)
                        .fillMaxHeight()
                        .width(handleWidthDp)
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                if (usableWidth > 0f) {
                                    val newFraction =
                                        (startFraction + dragAmount / usableWidth)
                                            .coerceIn(
                                                0f,
                                                endMs.toFloat() / durationMs.coerceAtLeast(1L)
                                                    .toFloat() - 0.02f
                                            )
                                    onStartChange((newFraction * durationMs).toLong())
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Tall pill handle — white with primary border
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .padding(vertical = 6.dp)
                            .shadow(4.dp, RoundedCornerShape(3.dp))
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White)
                            .border(1.5.dp, primaryColor, RoundedCornerShape(3.dp))
                    )
                }

                // End handle
                val endOffsetDp = with(density) {
                    ((horizontalPaddingPx + endFraction * usableWidth) - handleWidthPx / 2).toDp()
                }
                Box(
                    modifier = Modifier
                        .offset(x = endOffsetDp)
                        .fillMaxHeight()
                        .width(handleWidthDp)
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                if (usableWidth > 0f) {
                                    val newFraction =
                                        (endFraction + dragAmount / usableWidth)
                                            .coerceIn(
                                                startMs.toFloat() / durationMs.coerceAtLeast(1L)
                                                    .toFloat() + 0.02f,
                                                1f
                                            )
                                    onEndChange((newFraction * durationMs).toLong())
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Tall pill handle — white with tertiary border
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .padding(vertical = 6.dp)
                            .shadow(4.dp, RoundedCornerShape(3.dp))
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.tertiary,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }

        // Labels below the waveform
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "◀ START",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    fontSize = 10.sp
                ),
                color = primaryColor.copy(alpha = 0.7f)
            )
            Text(
                text = "END ▶",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            )
        }
    }
}