package com.engfred.musicplayer.feature_player.presentation.components

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_player.utils.extractPaletteColors
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun RotatingWaveAlbumArt(
    albumArtUri: Uri?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val density = LocalDensity.current

    var wavePhase by remember { mutableFloatStateOf(0f) }
    val wavesAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "wavesAlpha"
    )

    // Animate wave phase while playing
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                wavePhase += 0.15f
                delay(16L) // ~60 FPS
            }
        }
    }

    // Extract palette colors
    var extractedColors by remember(albumArtUri) { mutableStateOf(listOf<Color>()) }
    var currentColorIndex by remember(albumArtUri) { mutableIntStateOf(0) }

    LaunchedEffect(albumArtUri) {
        if (albumArtUri != null) {
            extractedColors = extractPaletteColors(context, albumArtUri, colorScheme.primary)
        } else {
            extractedColors = listOf(colorScheme.primary)
        }
    }

    // Smooth crossfade between palette colors (infinite loop)
    val animatedWaveColor by animateColorAsState(
        targetValue = extractedColors.getOrElse(currentColorIndex) { colorScheme.primary },
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "waveColorShift"
    )

    // Loop through colors forever
    LaunchedEffect(isPlaying, extractedColors) {
        if (isPlaying && extractedColors.size > 1) {
            while (true) {
                delay(2000L) // matches tween duration
                currentColorIndex = (currentColorIndex + 1) % extractedColors.size
            }
        }
    }

    // --- Infinite Rotation Animation ---
    var rawRotation by remember { mutableFloatStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = rawRotation,
        animationSpec = tween(
            durationMillis = if (isPlaying) 50 else 2000, // fast updates while playing, smooth slowdown otherwise
            easing = if (isPlaying) LinearEasing else FastOutSlowInEasing
        ),
        label = "albumRotation"
    )

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                rawRotation += 0.8f // ⬅️ removed `% 360f`, infinite smooth rotation
                delay(16L) // ~60 FPS
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val maxRadiusPx = min(constraints.maxWidth, constraints.maxHeight) / 2f
        val innerRadiusPx = maxRadiusPx * 0.65f

        // Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )

        // Radial wave visualizer
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (wavesAlpha > 0f) {
                val barCount = 72
                val frequency = 4f
                val amplitudePx = (maxRadiusPx - innerRadiusPx) * 0.35f
                val baseLengthPx = (maxRadiusPx - innerRadiusPx) * 0.4f
                val barWidthPx = 3f

                val lineAlpha = 0.7f * wavesAlpha
                val circleAlpha = 0.15f * wavesAlpha

                for (i in 0 until barCount) {
                    val angleDeg = i * (360f / barCount.toFloat())
                    val angleRad = (angleDeg * (PI / 180.0)).toFloat()

                    val waveHeight =
                        (sin(angleRad * frequency + wavePhase) * amplitudePx + amplitudePx) / 2f + baseLengthPx

                    val outerRadiusPx = innerRadiusPx + waveHeight

                    val startX = center.x + innerRadiusPx * cos(angleRad)
                    val startY = center.y + innerRadiusPx * sin(angleRad)
                    val endX = center.x + outerRadiusPx * cos(angleRad)
                    val endY = center.y + outerRadiusPx * sin(angleRad)

                    drawLine(
                        color = animatedWaveColor.copy(alpha = lineAlpha),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = barWidthPx,
                        cap = StrokeCap.Round
                    )
                }

                drawCircle(
                    color = animatedWaveColor.copy(alpha = circleAlpha),
                    radius = innerRadiusPx + 4f,
                    center = center,
                    style = Stroke(width = 8f)
                )
            }
        }

        // Album art with rotation
        Box(
            modifier = Modifier
                .size(with(density) { (innerRadiusPx * 2f).toDp() })
                .shadow(
                    elevation = 20.dp,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .rotate(animatedRotation), // Smooth infinite rotation
            contentAlignment = Alignment.Center
        ) {
            CoilImage(
                imageModel = { albumArtUri },
                imageOptions = ImageOptions(
                    contentDescription = "Album Art",
                    contentScale = ContentScale.Crop
                ),
                modifier = Modifier.fillMaxSize(),
                failure = {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = "No Album Art Available",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxSize(0.6f)
                    )
                },
                loading = {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxSize(0.4f)
                    )
                }
            )
        }
    }
}
