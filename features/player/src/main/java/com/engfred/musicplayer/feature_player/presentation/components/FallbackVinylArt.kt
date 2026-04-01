package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
fun FallbackVinylArt(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2

            // 1. Physical Base (The "Deep Black" Plastic)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF232323), Color(0xFF0A0A0A)),
                    center = center,
                    radius = radius
                )
            )

            // 2. High-Fidelity Grooves
            // We draw 30+ tiny circles with varying opacities to simulate physical ridges
            for (i in 0..35) {
                val grooveRadius = radius * (0.4f + (i * 0.015f))
                if (grooveRadius <= radius) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.07f),
                        radius = grooveRadius,
                        center = center,
                        style = Stroke(width = 1f)
                    )
                }
            }

            // 3. The "Classic Vinyl Sheen" (Sweep Gradient Reflection)
            // This creates the "V-shaped" light reflection seen on real records
            rotate(degrees = -45f) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = center
                    ),
                    radius = radius
                )
            }

            // 4. Center Label Area (The "Sticker" foundation)
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF6366F1), Color(0xFF4338CA)),
                    start = Offset(center.x - radius/3, center.y - radius/3),
                    end = Offset(center.x + radius/3, center.y + radius/3)
                ),
                radius = radius * 0.35f,
                center = center
            )

            // 5. Stylized Label Detail (Inner ring)
            drawCircle(
                color = Color.Black.copy(alpha = 0.2f),
                radius = radius * 0.32f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // 6. The Spindle Hole
            drawCircle(
                color = Color(0xFF121212),
                radius = 6.dp.toPx(),
                center = center
            )
        }

        // 7. Integrated Icon
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = Color.White.copy(alpha = 0.8f)
        )
    }
}