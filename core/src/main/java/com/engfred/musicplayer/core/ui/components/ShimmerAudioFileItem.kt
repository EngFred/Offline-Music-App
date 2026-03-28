package com.engfred.musicplayer.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    )

    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )
}

@Composable
fun ShimmerAudioFileItem(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp, start = 15.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album Art Placeholder
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(brush)
        )
        Spacer(modifier = Modifier.width(12.dp))

        // Text Placeholders
        Column(modifier = Modifier.weight(1f)) {
            // Title Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Artist Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        // Duration Placeholder
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.width(12.dp))

        // More Options Icon Placeholder
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(brush)
        )
        // Match the end padding of your actual item
        Spacer(modifier = Modifier.width(12.dp))
    }
}