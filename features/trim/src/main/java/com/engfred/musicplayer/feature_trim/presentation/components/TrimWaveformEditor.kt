package com.engfred.musicplayer.feature_trim.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.util.MediaUtils.formatDuration
import kotlin.math.abs
import kotlin.random.Random

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
    val totalDuration = durationMs.coerceAtLeast(1L)
    val trimDurationMs = (endMs - startMs).coerceAtLeast(0L)

    val amplitudes = remember { List(60) { Random.nextFloat() * 0.8f + 0.2f } }

    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    // Keep updated references for pointer gestures
    val currentStartMs by rememberUpdatedState(startMs)
    val currentEndMs by rememberUpdatedState(endMs)
    val onStartChangeState by rememberUpdatedState(onStartChange)
    val onEndChangeState by rememberUpdatedState(onEndChange)

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

        // Main Waveform Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceVariant.copy(alpha = 0.35f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp)
                )
                .onSizeChanged { containerWidthPx = it.width.toFloat() }
                .pointerInput(totalDuration) {
                    var activeTarget = 0 // 0 = none, 1 = start, 2 = end, 3 = window drag
                    var dragStartOffsetMs = 0L

                    detectDragGestures(
                        onDragStart = { offset ->
                            if (containerWidthPx <= 0f) return@detectDragGestures
                            val touchFraction = (offset.x / containerWidthPx).coerceIn(0f, 1f)
                            val touchMs = (touchFraction * totalDuration).toLong()

                            val startFraction = currentStartMs.toFloat() / totalDuration
                            val endFraction = currentEndMs.toFloat() / totalDuration
                            val startPx = startFraction * containerWidthPx
                            val endPx = endFraction * containerWidthPx

                            val thresholdPx = with(density) { 32.dp.toPx() }

                            val distToStart = abs(offset.x - startPx)
                            val distToEnd = abs(offset.x - endPx)

                            activeTarget = when {
                                distToStart < thresholdPx && distToStart <= distToEnd -> 1
                                distToEnd < thresholdPx -> 2
                                offset.x in startPx..endPx -> {
                                    dragStartOffsetMs = touchMs
                                    3
                                }
                                distToStart < distToEnd -> 1
                                else -> 2
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (containerWidthPx <= 0f) return@detectDragGestures
                            val deltaFraction = dragAmount.x / containerWidthPx
                            val deltaMs = (deltaFraction * totalDuration).toLong()

                            when (activeTarget) {
                                1 -> {
                                    val newStart = (currentStartMs + deltaMs).coerceIn(0L, (currentEndMs - 500L).coerceAtLeast(0L))
                                    onStartChangeState(newStart)
                                }
                                2 -> {
                                    val newEnd = (currentEndMs + deltaMs).coerceIn(currentStartMs + 500L, totalDuration)
                                    onEndChangeState(newEnd)
                                }
                                3 -> {
                                    val duration = currentEndMs - currentStartMs
                                    var newStart = currentStartMs + deltaMs
                                    var newEnd = currentEndMs + deltaMs

                                    if (newStart < 0L) {
                                        newStart = 0L
                                        newEnd = duration
                                    }
                                    if (newEnd > totalDuration) {
                                        newEnd = totalDuration
                                        newStart = totalDuration - duration
                                    }
                                    onStartChangeState(newStart)
                                    onEndChangeState(newEnd)
                                }
                            }
                        },
                        onDragEnd = { activeTarget = 0 },
                        onDragCancel = { activeTarget = 0 }
                    )
                }
                .pointerInput(totalDuration) {
                    detectTapGestures { offset ->
                        if (containerWidthPx <= 0f) return@detectTapGestures
                        val touchFraction = (offset.x / containerWidthPx).coerceIn(0f, 1f)
                        val touchMs = (touchFraction * totalDuration).toLong()

                        val startFraction = currentStartMs.toFloat() / totalDuration
                        val endFraction = currentEndMs.toFloat() / totalDuration
                        val startPx = startFraction * containerWidthPx
                        val endPx = endFraction * containerWidthPx

                        val distToStart = abs(offset.x - startPx)
                        val distToEnd = abs(offset.x - endPx)

                        if (distToStart <= distToEnd) {
                            val newStart = touchMs.coerceIn(0L, (currentEndMs - 500L).coerceAtLeast(0L))
                            onStartChangeState(newStart)
                        } else {
                            val newEnd = touchMs.coerceIn(currentStartMs + 500L, totalDuration)
                            onEndChangeState(newEnd)
                        }
                    }
                }
        ) {
            // 1. Draw Waveform, Selection Shading, and Playhead
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barWidth = canvasWidth / amplitudes.size
                val gap = 3.5f

                val startFraction = currentStartMs.toFloat() / totalDuration
                val endFraction = currentEndMs.toFloat() / totalDuration
                val startPx = startFraction * canvasWidth
                val endPx = endFraction * canvasWidth
                val currentPx = (currentPositionMs.toFloat() / totalDuration) * canvasWidth

                // Dim outer unselected regions
                if (startPx > 0f) {
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset(0f, 0f),
                        size = Size(startPx, canvasHeight)
                    )
                }
                if (endPx < canvasWidth) {
                    drawRect(
                        color = Color.Black.copy(alpha = 0.45f),
                        topLeft = Offset(endPx, 0f),
                        size = Size(canvasWidth - endPx, canvasHeight)
                    )
                }

                // Active Region background highlight
                drawRect(
                    color = primaryColor.copy(alpha = 0.08f),
                    topLeft = Offset(startPx, 0f),
                    size = Size((endPx - startPx).coerceAtLeast(0f), canvasHeight)
                )

                // Waveform Bars
                amplitudes.forEachIndexed { index, amplitude ->
                    val x = index * barWidth
                    val barHeight = (canvasHeight - 24.dp.toPx()) * amplitude
                    val y = (canvasHeight - barHeight) / 2

                    val isActive = x in startPx..endPx
                    val barColor = if (isActive) primaryColor else surfaceVariant.copy(alpha = 0.7f)

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size((barWidth - gap).coerceAtLeast(1.5f), barHeight),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }

                // Playhead indicator
                if (currentPositionMs in currentStartMs..currentEndMs) {
                    drawRoundRect(
                        color = onSurface,
                        topLeft = Offset(currentPx - 1.5f, 4.dp.toPx()),
                        size = Size(3f, canvasHeight - 8.dp.toPx()),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }

                // Selection Boundary Lines
                drawLine(
                    color = primaryColor,
                    start = Offset(startPx, 0f),
                    end = Offset(startPx, canvasHeight),
                    strokeWidth = 2.5f
                )
                drawLine(
                    color = primaryColor,
                    start = Offset(endPx, 0f),
                    end = Offset(endPx, canvasHeight),
                    strokeWidth = 2.5f
                )
            }

            // 2. Visual Top/Bottom Trim Boundary Badges
            if (containerWidthPx > 0f) {
                val startPx = (currentStartMs.toFloat() / totalDuration) * containerWidthPx
                val endPx = (currentEndMs.toFloat() / totalDuration) * containerWidthPx

                val startOffsetDp = with(density) { (startPx - 14.dp.toPx()).toDp() }
                val endOffsetDp = with(density) { (endPx - 14.dp.toPx()).toDp() }

                // Start Knob (Top)
                Box(
                    modifier = Modifier
                        .offset(x = startOffsetDp, y = 6.dp)
                        .size(28.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(primaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                // End Knob (Bottom)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = endOffsetDp, y = (-6).dp)
                        .size(28.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(primaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }

        // Labels below the waveform
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "◀ DRAG OR TAP TO SET START",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    fontSize = 10.sp
                ),
                color = primaryColor.copy(alpha = 0.8f)
            )
            Text(
                text = "SET END ▶",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    fontSize = 10.sp
                ),
                color = primaryColor.copy(alpha = 0.8f)
            )
        }
    }
}