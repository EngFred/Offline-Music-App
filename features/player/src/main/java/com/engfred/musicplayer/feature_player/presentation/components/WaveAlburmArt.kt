package com.engfred.musicplayer.feature_player.presentation.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_player.utils.extractPaletteColors
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlin.math.min

@Composable
fun WavingAlbumArtX(
    albumArtUri: Uri?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    waveCount: Int = 3,
    maxExtraRadiusFraction: Float = 1.4f, // how far beyond album art the waves reach
    baseWaveDurationMs: Int = 1400,       // duration of a single wave cycle
    innerGlowRadiusDp: Dp = 18.dp,
    usePalette: Boolean = true,           // whether to attempt color extraction
    paletteFallbackColor: Color? = null,  // optional explicit fallback color
    colorCycleDurationMs: Int = 2200      // duration for shifting between palette colors
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    var containerSizePx by remember { mutableStateOf(0) }
    val themePrimary = colorScheme.primary
    val effectiveFallback = paletteFallbackColor ?: themePrimary

    // extracted palette colors (unique, in preferred order)
    var paletteColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    // single primary extracted color fallback for legacy uses
    var extractedPrimaryColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(albumArtUri, usePalette) {
        paletteColors = emptyList()
        extractedPrimaryColor = null

        if (!usePalette || albumArtUri == null) return@LaunchedEffect

        paletteColors = extractPaletteColors(context, albumArtUri, effectiveFallback)
        extractedPrimaryColor = paletteColors.firstOrNull() ?: effectiveFallback
    }

    // single progress variable for ring expansion cycles
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(isPlaying, baseWaveDurationMs) {
        animProgress.stop()
        if (isPlaying) {
            while (isActive && isPlaying) {
                animProgress.snapTo(0f)
                animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = baseWaveDurationMs, easing = LinearEasing)
                )
                yield()
            }
        } else {
            animProgress.snapTo(0f)
        }
    }

    // Color cycle anim: lerp between paletteColors sequentially
    val colorAnimProgress = remember { Animatable(0f) }
    var colorIndex by remember { mutableStateOf(0) }

    LaunchedEffect(paletteColors, isPlaying, colorCycleDurationMs) {
        colorAnimProgress.stop()
        if (paletteColors.isEmpty()) {
            // ensure fallback
            colorIndex = 0
            colorAnimProgress.snapTo(0f)
            return@LaunchedEffect
        }

        if (isPlaying && paletteColors.size > 1) {
            // Loop through palette colors, animating progress 0..1 between current and next
            while (isActive && isPlaying) {
                colorAnimProgress.snapTo(0f)
                colorAnimProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = colorCycleDurationMs, easing = LinearEasing)
                )
                // advance index when animation completes
                colorIndex = (colorIndex + 1) % paletteColors.size
                // small yield to avoid starving
                yield()
            }
            // when stopped, snap to 0 so lerp uses colorIndex -> next
            colorAnimProgress.snapTo(0f)
        } else {
            // not playing or single color: snap to 0
            colorIndex = 0
            colorAnimProgress.snapTo(0f)
        }
    }

    // Compute current animated tint color
    val animatedTintColor: Color
    val colorsList = paletteColors.ifEmpty { listOf(effectiveFallback) }
    if (colorsList.size == 1) {
        animatedTintColor = colorsList.first()
    } else {
        val from = colorsList[colorIndex % colorsList.size]
        val to = colorsList[(colorIndex + 1) % colorsList.size]
        val t = colorAnimProgress.value.coerceIn(0f, 1f)
        animatedTintColor = lerp(from, to, t)
    }

    // subtle breathing when playing (small scale)
    val breathingScale by animateFloatAsStateIfPlaying(
        isPlaying = isPlaying,
        minScale = 0.995f,
        maxScale = 1.01f,
        duration = 1600
    )

    Box(
        modifier = modifier
            .onSizeChanged { containerSizePx = min(it.width, it.height) }
            .clip(CircleShape)
            .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                ambientColor = animatedTintColor.copy(alpha = 0.14f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (containerSizePx <= 0) return@Canvas

            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)

            val albumRadius = (min(size.width, size.height) / 2f)
            val maxRadius = albumRadius * maxExtraRadiusFraction

            val onSurfaceVariant = colorScheme.onSurfaceVariant

            // inner soft glow using animated tint
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedTintColor.copy(alpha = 0.20f), animatedTintColor.copy(alpha = 0.06f), animatedTintColor.copy(alpha = 0f)),
                    center = center,
                    radius = albumRadius + innerGlowRadiusDp.toPx()
                ),
                radius = albumRadius + innerGlowRadiusDp.toPx(),
                center = center
            )

            val progress = animProgress.value.coerceIn(0f, 1f)
            val baseAlpha = 0.7f

            for (i in 0 until waveCount) {
                val offset = i.toFloat() / waveCount.toFloat()
                val raw = (progress + offset) % 1f
                val eased = raw

                val startRadius = albumRadius * 0.88f
                val radius = startRadius + (maxRadius - startRadius) * eased

                val alpha = (1f - eased).coerceIn(0f, 1f) * baseAlpha

                val minStroke = 2.dp.toPx()
                val maxStroke = (albumRadius * 0.14f).coerceAtLeast(minStroke)
                val stroke = minStroke + (maxStroke - minStroke) * (1f - eased)

                // outer glow ring gradient blends animated tint -> transparent
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animatedTintColor.copy(alpha = alpha * 0.36f), animatedTintColor.copy(alpha = alpha * 0.06f), Color.Transparent),
                        center = center,
                        radius = radius + stroke * 1.2f
                    ),
                    radius = radius + stroke * 0.9f,
                    center = center,
                    style = Stroke(width = stroke * 1.2f)
                )

                // main crisp ring tinted with animated color
                drawCircle(
                    color = animatedTintColor.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = stroke)
                )

                // faint secondary inner ring for depth
                drawCircle(
                    color = onSurfaceVariant.copy(alpha = alpha * 0.14f),
                    radius = radius * 0.84f,
                    center = center,
                    style = Stroke(width = stroke * 0.6f)
                )
            }
        }

        Box(
            modifier = Modifier
                .scale(breathingScale)
                .fillMaxSize(0.86f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoilImage(
                imageModel = { albumArtUri },
                imageOptions = ImageOptions(
                    contentDescription = "Album Art",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                ),
                modifier = Modifier.fillMaxSize(),
                failure = {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = "No Album Art Available",
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxSize(0.6f)
                    )
                },
                loading = {
                    CircularProgressIndicator(
                        color = animatedTintColor.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxSize(0.28f)
                    )
                }
            )
        }
    }
}

/**
 * Helper: animates a tiny breathing float when playing, otherwise stays at 1f.
 * Uses Compose animation when playing; returns 1f immediately when not.
 */
@Composable
private fun animateFloatAsStateIfPlaying(
    isPlaying: Boolean,
    minScale: Float,
    maxScale: Float,
    duration: Int
): State<Float> {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                anim.animateTo(maxScale, animationSpec = tween(durationMillis = duration / 2, easing = LinearEasing))
                anim.animateTo(minScale, animationSpec = tween(durationMillis = duration / 2, easing = LinearEasing))
            }
        } else {
            anim.snapTo(1f)
        }
    }
    return anim.asState()
}
