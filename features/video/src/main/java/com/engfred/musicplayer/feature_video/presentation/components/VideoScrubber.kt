package com.engfred.musicplayer.feature_video.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun VideoScrubber(
    currentPositionMs: Long,
    totalDurationMs: Long,
    bufferedPositionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    activeEndColor: Color = Color(0xFFFF5722),
    bufferedColor: Color = Color.White.copy(alpha = 0.35f),
    trackColor: Color = Color.White.copy(alpha = 0.18f)
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var componentWidth by remember { mutableFloatStateOf(1f) }

    val safeDuration = totalDurationMs.coerceAtLeast(1L)
    val actualFraction = (currentPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    val displayFraction = if (isDragging) dragFraction else actualFraction

    // Animate track thickness when scrubbing
    val trackHeightDp by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 4.dp,
        animationSpec = tween(150),
        label = "trackHeight"
    )
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isDragging) 9.dp else 6.dp,
        animationSpec = tween(150),
        label = "thumbRadius"
    )
    val previewAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(150),
        label = "previewAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Floating Time Bubble while dragging
        if (isDragging) {
            val previewMs = (dragFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
            val bubbleOffsetX = (displayFraction * componentWidth - 36f).coerceIn(0f, (componentWidth - 72f).coerceAtLeast(0f))

            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleOffsetX.roundToInt(), -42) }
                    .background(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = formatScrubberTime(previewMs),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = Shadow(color = Color.Black, blurRadius = 4f)
                    )
                )
            }
        }

        // Custom Scrubber Canvas with smooth touch handling
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(safeDuration) {
                    detectTapGestures(
                        onPress = { offset ->
                            componentWidth = size.width.toFloat()
                            val frac = if (size.width > 0f) {
                                (offset.x / size.width).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            isDragging = true
                            dragFraction = frac
                            val targetMs = (frac * safeDuration).toLong()
                            onSeek(targetMs)
                            tryAwaitRelease()
                            isDragging = false
                        }
                    )
                }
                .pointerInput(safeDuration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            componentWidth = size.width.toFloat()
                            isDragging = true
                            dragFraction = if (size.width > 0f) {
                                (offset.x / size.width).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragFraction = if (size.width > 0f) {
                                (change.position.x / size.width).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            val targetMs = (dragFraction * safeDuration).toLong()
                            onSeek(targetMs)
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
        ) {
            componentWidth = size.width
            val heightPx = trackHeightDp.toPx()
            val thumbRadiusPx = thumbRadiusDp.toPx()
            val centerY = size.height / 2f
            val cornerRadius = CornerRadius(heightPx / 2f, heightPx / 2f)

            // 1. Inactive Background Track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, centerY - heightPx / 2f),
                size = Size(size.width, heightPx),
                cornerRadius = cornerRadius
            )

            // 2. Buffered Track
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, centerY - heightPx / 2f),
                    size = Size(size.width * bufferedFraction, heightPx),
                    cornerRadius = cornerRadius
                )
            }

            // 3. Active Played Track (Subtle gradient)
            val activeWidth = size.width * displayFraction
            if (activeWidth > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(activeColor, activeEndColor),
                        startX = 0f,
                        endX = size.width
                    ),
                    topLeft = Offset(0f, centerY - heightPx / 2f),
                    size = Size(activeWidth, heightPx),
                    cornerRadius = cornerRadius
                )
            }

            // 4. Smooth Thumb (Outer ring glow + solid inner circle)
            val thumbCenterX = activeWidth.coerceIn(thumbRadiusPx, size.width - thumbRadiusPx)
            
            // Subtle outer glow/halo when dragging
            if (isDragging) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.3f),
                    radius = thumbRadiusPx + 6.dp.toPx(),
                    center = Offset(thumbCenterX, centerY)
                )
            }

            // Solid inner thumb
            drawCircle(
                color = Color.White,
                radius = thumbRadiusPx,
                center = Offset(thumbCenterX, centerY)
            )
            drawCircle(
                color = activeColor,
                radius = thumbRadiusPx - 2.dp.toPx(),
                center = Offset(thumbCenterX, centerY)
            )
        }
    }
}

private fun formatScrubberTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
