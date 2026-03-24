package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun NowPlayingCard(
    trackTitle: String,
    trackArtist: String,
    bpm: Float?,
    positionMs: Long,
    durationMs: Long,
    isCrossfading: Boolean,
    crossfadeProgress: Float,
    albumArtUri: Uri?,
    waveform: List<Float> = emptyList(), // <--- UI/UX UPDATE: New Visualizer Parameter
    modifier: Modifier = Modifier
) {
    val playbackProgress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animatedPlayback by animateFloatAsState(
        targetValue = playbackProgress,
        animationSpec = tween(durationMillis = 300),
        label = "playback_progress"
    )

    // Continuous Vinyl Rotation
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Glassmorphic Base
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated Vinyl Record with Album Art!
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF121212))
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                        .graphicsLayer { rotationZ = rotation },
                    contentAlignment = Alignment.Center
                ) {
                    if (albumArtUri != null) {
                        // The actual Album Art wrapped onto the record
                        AsyncImage(
                            model = albumArtUri,
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                        // Dark overlay to maintain the "vinyl" aesthetic
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.2f))
                        )
                    } else {
                        // Fallback icon if no URI is provided
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // The center spindle hole of the record
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F0F13)) // matches the overall background
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trackTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = trackArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Premium BPM Badge
                bpm?.let {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${it.toInt()} BPM",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── UI/UX TEAM UPDATE: Live Bouncing Visualizer ──────────────────────────
            // We render the waveform data emitted by the Visualizer API.
            // We chunk the data into thicker bars for a premium hardware-style aesthetic.
            if (waveform.isNotEmpty()) {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(vertical = 4.dp)
                ) {
                    // Downsample to 48 bars for a chunkier, modern spectrum look
                    val barCount = 48
                    val chunkSize = (waveform.size / barCount).coerceAtLeast(1)
                    val downsampled = waveform.chunked(chunkSize).map { chunk ->
                        chunk.maxOrNull() ?: 0f
                    }

                    val barWidth = size.width / downsampled.size
                    // Leave a small gap between bars
                    val strokeWidth = (barWidth * 0.65f).coerceAtLeast(2f)

                    downsampled.forEachIndexed { index, amplitude ->
                        // Scale the amplitude up visually, ensure a minimum height so it's always visible
                        val rawHeight = amplitude * size.height * 1.5f
                        val barHeight = rawHeight.coerceIn(4f, size.height)

                        val x = index * barWidth + (barWidth / 2)
                        // Center the bounce vertically
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
                // Fallback flat line/spacer if no audio data is flowing
                Spacer(modifier = Modifier.height(48.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Timestamps
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
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Playback Progress
            LinearProgressIndicator(
                progress = { animatedPlayback },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.1f)
            )

            // Dynamic Crossfade Timeline
            if (isCrossfading) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CROSSFADING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.tertiary,
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
                            .height(4.dp),
                        strokeCap = StrokeCap.Round,
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}