package com.engfred.musicplayer.feature_trim.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun TrimLoadingIndicator(
    modifier: Modifier = Modifier,
    title: String = "Crafting your audio...",
    subtitle: String? = "Applying precise cuts"
) {
    // Read composable-only theme values
    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorTertiary = MaterialTheme.colorScheme.tertiary
    val colorOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val colorOnSurface = MaterialTheme.colorScheme.onSurface
    val typography = MaterialTheme.typography

    val infiniteTransition = rememberInfiniteTransition(label = "audio_eq_transition")

    // Create 5 staggered animations for the equalizer bars
    val barCount = 5
    val animatedHeights = List(barCount) { index ->
        // Stagger the animation delay based on the index to create a "wave" effect
        val delay = index * 150
        val height by infiniteTransition.animateFloat(
            initialValue = 0.2f, // Min height (20%)
            targetValue = 1f,    // Max height (100%)
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 600,
                    delayMillis = delay,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
        height
    }

    // A subtle breathing effect for the title text
    val textAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "text_pulse"
    )

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Equalizer Canvas
        Canvas(
            modifier = Modifier
                .height(48.dp)
                .width(72.dp)
        ) {
            // Calculate widths to perfectly space the bars
            val totalGaps = barCount - 1
            // Let gap width equal half of a bar's width
            val barWidth = size.width / (barCount + totalGaps * 0.5f)
            val gapWidth = barWidth * 0.5f
            val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

            animatedHeights.forEachIndexed { index, heightFraction ->
                val xOffset = index * (barWidth + gapWidth)
                val currentHeight = size.height * heightFraction
                // Center the bar vertically
                val yOffset = (size.height - currentHeight) / 2f

                // Alternate colors to create depth
                val barColor = if (index % 2 == 0) colorPrimary else colorTertiary

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(xOffset, yOffset),
                    size = Size(barWidth, currentHeight),
                    cornerRadius = cornerRadius
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Breathing Title Text
        Text(
            text = title,
            style = typography.titleMedium.copy(color = colorOnSurface.copy(alpha = textAlpha)),
        )

        subtitle?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                textAlign = TextAlign.Center,
                text = it,
                style = typography.bodySmall,
                color = colorOnSurfaceVariant
            )
        }
    }
}