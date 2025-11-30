package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.util.MediaUtils.formatDuration
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekBarSection(
    sliderValue: Float,
    totalDurationMs: Long,
    playbackPositionMs: Long,
    onSliderValueChange: (Float) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    playerLayout: PlayerLayout,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
    /**
     * Provide the actual playing state from your player (true when audio is playing).
     * Default true keeps backward compatibility but you should pass real state.
     */
    isPlaying: Boolean = true
) {
    // Custom thumb: A static circular indicator
    val customThumb: @Composable (SliderState) -> Unit = {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }

    // Custom track: A layered track for active and inactive portions
    val customTrack: @Composable (SliderState) -> Unit = { sliderState ->
        val activeTrackColor = MaterialTheme.colorScheme.primary
        val inactiveTrackColor = LocalContentColor.current.copy(alpha = 0.15f)

        val totalRange = sliderState.valueRange.endInclusive - sliderState.valueRange.start
        val currentProgressFraction = if (totalRange > 0) {
            (sliderState.value - sliderState.valueRange.start) / totalRange
        } else {
            0f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(inactiveTrackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(currentProgressFraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(activeTrackColor)
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val sliderValueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(0f)

    @Composable
    fun WaveformSeekBar(
        value: Float,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: () -> Unit,
        valueRange: ClosedFloatingPointRange<Float>,
        modifier: Modifier = Modifier,
        isPlaying: Boolean
    ) {
        val density = LocalDensity.current
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = LocalContentColor.current.copy(alpha = 0.2f)
        val playheadColor = MaterialTheme.colorScheme.primary
        val desiredBarWidthDp = 4.dp
        val barSpacingDp = 1.dp
        val waveHeight = 32.dp // Height of the waveform area (excluding padding)

        // Phase controller: continuously increases while playing, pauses when not.
        val phase = remember { Animatable(0f) }
        LaunchedEffect(isPlaying) {
            if (isPlaying) {
                while (true) {
                    val target = phase.value + (2 * PI).toFloat()
                    phase.animateTo(
                        targetValue = target,
                        animationSpec = tween(durationMillis = 12000, easing = LinearEasing)
                    )
                }
            }
            // When paused, the effect cancels any ongoing animation, keeping the current phase.
        }

        Box(modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onValueChangeFinished() },
                    onDrag = { change, _ ->
                        val dragPosition = change.position.x.coerceIn(0f, size.width.toFloat())
                        val fraction = dragPosition / size.width
                        val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue.coerceIn(valueRange))
                    }
                )
            }) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Calculate spacing using pixel values for precise alignment
                val spacingPx = density.run { barSpacingDp.toPx() }
                val desiredBarWidthPx = density.run { desiredBarWidthDp.toPx() }

                // Compute number of bars that fit exactly (at least 3 to avoid degenerate layout)
                val possibleNumBars = ((size.width + spacingPx) / (desiredBarWidthPx + spacingPx)).toInt().coerceAtLeast(3)
                val numBars = possibleNumBars

                // Recompute barWidth so the bars + spacings exactly fill the width
                val totalSpacing = spacingPx * (numBars - 1)
                val barWidth = ((size.width - totalSpacing) / numBars).coerceAtLeast(1f)

                val barHeightMax = density.run { waveHeight.toPx() } * 0.8f
                val baselineY = size.height
                val progressFraction = if (valueRange.endInclusive > valueRange.start) {
                    (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
                } else 0f

                val animatedTime = phase.value
                val speedMultiplier = 1f // Unified speed for smooth alignment across sections

                val playheadX = (progressFraction * size.width).coerceIn(0f, size.width)

                // Draw active portion (left of playhead)
                clipRect(left = 0f, top = 0f, right = playheadX, bottom = size.height) {
                    for (i in 0 until numBars) {
                        val x = i * (barWidth + spacingPx)
                        val basePhase = (i / numBars.toFloat()) * 4 * PI
                        val animatedPhase = basePhase + (animatedTime * speedMultiplier)
                        val heightFraction = (sin(animatedPhase) * 0.3f + 0.7f).coerceAtLeast(0.2) // Reduced amplitude for less shake (30% variation)
                        val barHeight = (heightFraction * barHeightMax).toFloat()

                        // Draw filled rounded rect for consistent edge alignment
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(x, (baselineY - barHeight)),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(x = barWidth * 0.2f, y = barWidth * 0.2f)
                        )
                    }
                }

                // Draw inactive portion (right of playhead)
                clipRect(left = playheadX, top = 0f, right = size.width, bottom = size.height) {
                    for (i in 0 until numBars) {
                        val x = i * (barWidth + spacingPx)
                        val basePhase = (i / numBars.toFloat()) * 4 * PI
                        val animatedPhase = basePhase + (animatedTime * speedMultiplier)
                        val heightFraction = (sin(animatedPhase) * 0.3f + 0.7f).coerceAtLeast(0.2)
                        val barHeight = (heightFraction * barHeightMax).toFloat()

                        drawRoundRect(
                            color = inactiveColor,
                            topLeft = Offset(x, (baselineY - barHeight)),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(x = barWidth * 0.2f, y = barWidth * 0.2f)
                        )
                    }
                }

                // Draw the playhead (vertical line) at current position
                val playheadStrokeWidth = density.run { 2.dp.toPx() }
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, size.height),
                    strokeWidth = playheadStrokeWidth
                )
            }
        }
    }

    if (playerLayout == PlayerLayout.IMMERSIVE_CANVAS) {
        // ImmersiveCanvas style: Waveform progress indicator with times below
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
        ) {
            // Waveform with constrained height
            Box(
                modifier = Modifier
                    .height(48.dp) // Slightly taller to accommodate waveform
                    .padding(vertical = 8.dp)
            ) {
                WaveformSeekBar(
                    value = sliderValue,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = onSliderValueChangeFinished,
                    valueRange = sliderValueRange,
                    modifier = Modifier.fillMaxWidth(),
                    isPlaying = isPlaying
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(playbackPositionMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
                Text(
                    text = formatDuration(totalDurationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        // EtherealFlowLayout or MinimalistGrooveLayout: times below slider, reduced gap
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
        ) {
            // Wrap Slider in Box with constrained height to reduce gap
            Box(
                modifier = Modifier.height(18.dp)
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = onSliderValueChangeFinished,
                    valueRange = sliderValueRange,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = LocalContentColor.current.copy(alpha = 0.2f)
                    ),
                    thumb = customThumb,
                    track = customTrack,
                    enabled = true,
                    interactionSource = interactionSource,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(playbackPositionMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
                Text(
                    text = formatDuration(totalDurationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    }
}