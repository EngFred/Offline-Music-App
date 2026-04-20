package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spinning vinyl disc with an optional countdown arc.
 *
 * @param vinylSize The outer diameter of the disc.  Defaults to 188 dp for the
 *   full-size single-deck view.  Pass a smaller value (e.g. 128 dp) when
 *   rendering inside the dual-deck layout where space is shared between two
 *   panels.  All internal proportions (arc stroke, inner disc, centre hole)
 *   are derived from this parameter, so nothing else needs to change.
 */
@Composable
fun VinylSection(
    albumArtUri: Uri?,
    isPlaying: Boolean,
    timeToNextMixMs: Long?,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    vinylSize: Dp = 188.dp
) {
    val urgentColor = Color(0xFFEF5350)

    // ── Proportional sizing ───────────────────────────────────────────────────
    // All values are derived from [vinylSize] so the component scales correctly
    // regardless of which layout renders it.
    val arcStrokeWidth  = (vinylSize.value * 5f  / 188f).dp  // 5 dp @ 188 → ~3.4 dp @ 128
    val vinylDiscSize   = (vinylSize.value * 174f / 188f).dp  // 174 dp @ 188 → ~118 dp @ 128
    val centerHoleSize  = (vinylSize.value * 28f  / 188f).dp  // 28 dp @ 188 → ~19 dp @ 128
    val musicIconSize   = (vinylSize.value * 22f  / 188f).dp
    val iconOffsetDp    = (vinylSize.value * 14f  / 188f).dp

    // ── Countdown arc colour ──────────────────────────────────────────────────
    val arcColor: Color = when {
        timeToNextMixMs == null  -> primaryColor
        timeToNextMixMs <= 0L    -> urgentColor
        timeToNextMixMs < 5_000L -> lerp(primaryColor, urgentColor,
            1f - timeToNextMixMs.toFloat() / 5_000f)
        else                     -> primaryColor
    }

    // Track the maximum value we've seen so the arc fraction shrinks smoothly
    // from 100 % toward 0 % as the countdown progresses.
    var countdownMaxMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(timeToNextMixMs) {
        when {
            timeToNextMixMs == null          -> countdownMaxMs = 0L
            countdownMaxMs == 0L             -> countdownMaxMs = timeToNextMixMs
            timeToNextMixMs > countdownMaxMs -> countdownMaxMs = timeToNextMixMs
        }
    }

    val countdownFraction: Float = if (timeToNextMixMs != null && countdownMaxMs > 0L) {
        1f - (timeToNextMixMs.toFloat() / countdownMaxMs.toFloat())
    } else 0f

    val animatedCountdownFraction by animateFloatAsState(
        targetValue   = countdownFraction,
        animationSpec = tween(durationMillis = 350),
        label         = "countdown_arc"
    )

    // ── Rotation ──────────────────────────────────────────────────────────────
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            rotation.snapTo(rotation.value % 360f)
            while (true) {
                rotation.animateTo(
                    targetValue   = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    Box(modifier = modifier.size(vinylSize), contentAlignment = Alignment.Center) {

        // ── Countdown arc drawn on a Canvas that fills the outer Box ─────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sw     = arcStrokeWidth.toPx()
            val inset  = sw / 2f
            val arcSz  = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            if (countdownMaxMs > 0L || animatedCountdownFraction > 0f) {
                // Background ring
                drawArc(
                    color      = arcColor.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSz,
                    style      = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
            if (animatedCountdownFraction > 0f) {
                // Filled progress arc
                drawArc(
                    color      = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedCountdownFraction,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSz,
                    style      = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        }

        // ── Vinyl disc (rotates) ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(vinylDiscSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .graphicsLayer { rotationZ = rotation.value },
            contentAlignment = Alignment.Center
        ) {
            // Album art or fallback
            SubcomposeAsyncImage(
                model              = albumArtUri,
                contentDescription = "Album Art",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize().clip(CircleShape),
                loading            = { FallbackVinylArt(accentColor = primaryColor) },
                error              = { FallbackVinylArt(accentColor = primaryColor) }
            )

            // Radial vignette overlay so the centre hole blends in
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                        )
                    )
            )

            // Centre hole
            Box(
                modifier = Modifier
                    .size(centerHoleSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), CircleShape)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Fallback vinyl art (no album art loaded)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FallbackVinylArt(
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFBB86FC)
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.width / 2f
            val cx = center.x
            val cy = center.y

            drawCircle(color = Color(0xFF1A1A1A), radius = maxRadius)

            for (i in 2..10) {
                drawCircle(
                    color  = Color.White.copy(alpha = 0.06f),
                    radius = maxRadius * (i / 11f),
                    style  = Stroke(width = 1.dp.toPx())
                )
            }

            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.22f to Color.White.copy(alpha = 0.18f),
                        0.50f to Color.Transparent,
                        0.72f to Color.White.copy(alpha = 0.10f),
                        1.00f to Color.Transparent
                    ),
                    center = center
                ),
                radius = maxRadius
            )

            val labelRadius = maxRadius * 0.42f
            drawCircle(color = accentColor.copy(alpha = 0.90f), radius = labelRadius)

            drawArc(
                color      = Color.Black.copy(alpha = 0.55f),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter  = true,
                topLeft    = Offset(cx - labelRadius, cy - labelRadius),
                size       = Size(labelRadius * 2f, labelRadius * 2f)
            )

            val stripeAngleRad = Math.toRadians(45.0).toFloat()
            val stripeEnd = Offset(
                x = cx + labelRadius * 0.88f * cos(stripeAngleRad),
                y = cy + labelRadius * 0.88f * sin(stripeAngleRad)
            )
            drawLine(
                color       = Color.White.copy(alpha = 0.55f),
                start       = Offset(cx, cy),
                end         = stripeEnd,
                strokeWidth = 2.5.dp.toPx(),
                cap         = StrokeCap.Round
            )
        }

        Icon(
            imageVector        = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint               = Color.White.copy(alpha = 0.85f),
            modifier           = Modifier
                .offset(x = (-14).dp, y = (-14).dp)
                .size(22.dp)
        )
    }
}