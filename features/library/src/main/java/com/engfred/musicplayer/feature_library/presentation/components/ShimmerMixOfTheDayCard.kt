package com.engfred.musicplayer.feature_library.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.ui.components.shimmerBrush

@Composable
fun ShimmerMixOfTheDayCard(modifier: Modifier = Modifier) {
    // Reuse the exact same animated brush so the whole screen shimmers in sync
    val brush = shimmerBrush()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            .clip(RoundedCornerShape(12.dp))
            // Match the gradient background of the real card
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Overlapping Image Stack Placeholder
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // Draw 3 overlapping rounded boxes matching your album art logic
                for (i in 2 downTo 0) {
                    val xOffset = (i * 12).dp
                    Box(
                        modifier = Modifier
                            .offset(x = xOffset)
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(brush)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Text Placeholders
            Column(modifier = Modifier.weight(1f)) {
                // "Mix of the Day" Title Placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Spacer(Modifier.height(8.dp))
                // Subtitle Placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }

            // Chevron Icon Placeholder
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(brush)
            )
        }
    }
}