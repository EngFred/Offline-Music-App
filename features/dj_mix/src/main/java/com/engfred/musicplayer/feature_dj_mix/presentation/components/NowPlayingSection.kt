package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy

val MixStrategy.uiLabel: String
    get() = when (this) {
        MixStrategy.TRANSPARENT -> "TRANSPARENT BLEND"
        MixStrategy.SMOOTH -> "SMOOTH SYNC"
        MixStrategy.POWER_MIX -> "POWER MIX"
        MixStrategy.HARMONIC -> "HARMONIC DROP"
        MixStrategy.WIDE_TRANSITION -> "ENERGY VALLEY"
    }

val MixStrategy.themeColor: Color
    get() = when (this) {
        MixStrategy.TRANSPARENT -> Color(0xFF0288D1) // Deepened slightly for better contrast on light themes
        MixStrategy.SMOOTH -> Color(0xFF388E3C)
        MixStrategy.POWER_MIX -> Color(0xFFF57C00)
        MixStrategy.HARMONIC -> Color(0xFF8E24AA)
        MixStrategy.WIDE_TRANSITION -> Color(0xFFD32F2F)
    }

@Composable
fun NowPlayingSection(
    modifier: Modifier = Modifier,
    trackTitle: String,
    trackArtist: String,
    bpm: Float?,
    positionMs: Long,
    durationMs: Long,
    isCrossfading: Boolean,
    crossfadeProgress: Float,
    currentMixStrategy: MixStrategy,
    albumArtUri: Uri?,
    waveform: List<Float> = emptyList(),
    isPlaying: Boolean
) {
    val playbackProgress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animatedPlayback by animateFloatAsState(
        targetValue = playbackProgress,
        animationSpec = tween(durationMillis = 300),
        label = "playback_progress"
    )

    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                val currentAngle = rotation.value % 360f
                rotation.snapTo(currentAngle)
                rotation.animateTo(
                    targetValue = currentAngle + 360f,
                    animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vinyl Record Display
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    // Adaptive background container for the album art
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .graphicsLayer { rotationZ = rotation.value },
                contentAlignment = Alignment.Center
            ) {
                if (albumArtUri != null) {
                    // Actual Album Art
                    AsyncImage(
                        model = albumArtUri,
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.1f)))
                } else {
                    // Procedural Vinyl Fallback
                    FallbackVinylArt()
                }

                // The Spindle Hole (Adapts to background to look like a true hole)
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        // This makes the hole match the app's background color (light or dark)
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trackTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface, // Adaptive Text Color
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = trackArtist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // Adaptive Secondary Text
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Minimalist BPM text
            bpm?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = it.toInt().toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "BPM",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (waveform.isNotEmpty()) {
            val primaryColor = MaterialTheme.colorScheme.primary
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                val barCount = 48
                val chunkSize = (waveform.size / barCount).coerceAtLeast(1)
                val downsampled = waveform.chunked(chunkSize).map { chunk ->
                    chunk.maxOrNull() ?: 0f
                }

                val barWidth = size.width / downsampled.size
                val strokeWidth = (barWidth * 0.7f).coerceAtLeast(2f)

                downsampled.forEachIndexed { index, amplitude ->
                    val rawHeight = amplitude * size.height * 1.5f
                    val barHeight = rawHeight.coerceIn(4f, size.height)

                    val x = index * barWidth + (barWidth / 2)
                    val startY = (size.height - barHeight) / 2
                    val endY = startY + barHeight

                    drawLine(
                        color = primaryColor.copy(alpha = 0.8f),
                        start = Offset(x, startY),
                        end = Offset(x, endY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(56.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Timestamps & Progress
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMs(positionMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = formatMs(durationMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant // Adaptive Secondary Text
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { animatedPlayback },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            strokeCap = StrokeCap.Round,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) // Adaptive track color
        )

        if (isCrossfading) {
            Spacer(modifier = Modifier.height(24.dp))
            val strategyColor = currentMixStrategy.themeColor
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint = strategyColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = currentMixStrategy.uiLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = strategyColor,
                    modifier = Modifier.padding(end = 12.dp)
                )

                val animatedCrossfade by animateFloatAsState(
                    targetValue = crossfadeProgress,
                    animationSpec = tween(durationMillis = 100),
                    label = "crossfade_progress"
                )
                LinearProgressIndicator(
                    progress = { animatedCrossfade },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    strokeCap = StrokeCap.Round,
                    color = strategyColor,
                    trackColor = strategyColor.copy(alpha = 0.15f)
                )
            }
        }
    }
}

/**
 * Procedural spinning vinyl record.
 * The vinyl itself stays dark (like real life), but the labels and highlights adapt perfectly.
 */
@Composable
private fun FallbackVinylArt(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary // Text/Icon color that contrasts with primary

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.width / 2

            // 1. Highlighted Outer Rim
            drawCircle(color = Color(0xFF333333), radius = maxRadius)

            // 2. Base Vinyl Color (Stays dark black in both themes)
            drawCircle(color = Color(0xFF141414), radius = maxRadius - 1.dp.toPx())

            // 3. Prominent Vinyl Gloss/Shine (White reflection works on black vinyl everywhere)
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.0f),
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.0f),
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.0f)
                    )
                ),
                radius = maxRadius
            )

            // 4. Record Grooves
            for (i in 3..9) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.8f),
                    radius = maxRadius * (i / 10f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // 5. Solid, Bright Record Label
            val labelRadius = maxRadius * 0.45f
            drawCircle(
                color = primaryColor,
                radius = labelRadius
            )

            // Inner styling for the label
            drawCircle(
                color = onPrimaryColor.copy(alpha = 0.3f),
                radius = labelRadius * 0.85f,
                style = Stroke(width = 1.dp.toPx())
            )

            // Darker center zone for the spindle
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = labelRadius * 0.5f
            )
        }

        // 6. Centered Music Icon
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = onPrimaryColor.copy(alpha = 0.9f), // Ensures icon is visible regardless of primary color
            modifier = Modifier
                .size(22.dp)
                .padding(bottom = 12.dp, end = 12.dp)
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}