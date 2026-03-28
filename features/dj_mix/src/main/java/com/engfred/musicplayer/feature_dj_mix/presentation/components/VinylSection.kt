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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VinylSection(
    albumArtUri: Uri?,
    isPlaying: Boolean,
    timeToNextMixMs: Long?,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val urgentColor = Color(0xFFEF5350)

    val arcColor: Color = when {
        timeToNextMixMs == null  -> primaryColor
        timeToNextMixMs <= 0L    -> urgentColor
        timeToNextMixMs < 5_000L -> lerp(primaryColor, urgentColor, 1f - timeToNextMixMs.toFloat() / 5_000f)
        else                     -> primaryColor
    }

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

    // Vinyl rotation
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

    Box(modifier = modifier.size(188.dp), contentAlignment = Alignment.Center) {

        // Countdown arc ring drawn behind the vinyl
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 5.dp.toPx()
            val inset       = strokeWidth / 2f
            val arcSize     = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft     = Offset(inset, inset)

            if (countdownMaxMs > 0L || animatedCountdownFraction > 0f) {
                drawArc(
                    color      = arcColor.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            if (animatedCountdownFraction > 0f) {
                drawArc(
                    color      = arcColor.copy(alpha = 1f),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedCountdownFraction,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // The vinyl disc itself — rotates
        Box(
            modifier = Modifier
                .size(174.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .graphicsLayer { rotationZ = rotation.value },
            contentAlignment = Alignment.Center
        ) {
            if (albumArtUri != null) {
                AsyncImage(
                    model              = albumArtUri,
                    contentDescription = "Album Art",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize().clip(CircleShape)
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
            } else {
                FallbackVinylArt(accentColor = primaryColor)
            }

            // Center hole
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), CircleShape)
            )
        }
    }
}

@Composable
fun FallbackVinylArt(
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFBB86FC)
) {
    val onAccent = Color.White

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.width / 2f
            val cx = center.x
            val cy = center.y

            // ── 1. Outer vinyl base ───────────────────────────────────────────
            drawCircle(color = Color(0xFF1A1A1A), radius = maxRadius)

            // ── 2. Subtle grooves (lighter than before so they're visible) ────
            for (i in 2..10) {
                drawCircle(
                    color  = Color.White.copy(alpha = 0.06f),
                    radius = maxRadius * (i / 11f),
                    style  = Stroke(width = 1.dp.toPx())
                )
            }

            // ── 3. Sweep sheen — strong enough to see rotate ──────────────────
            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        0.10f to Color.White.copy(alpha = 0.00f),
                        0.22f to Color.White.copy(alpha = 0.18f),   // bright sector
                        0.30f to Color.White.copy(alpha = 0.04f),
                        0.50f to Color.Transparent,
                        0.72f to Color.White.copy(alpha = 0.10f),   // secondary shimmer
                        0.80f to Color.White.copy(alpha = 0.02f),
                        1.00f to Color.Transparent
                    ),
                    center = center
                ),
                radius = maxRadius
            )

            // ── 4. Label circle ───────────────────────────────────────────────
            val labelRadius = maxRadius * 0.42f
            drawCircle(color = accentColor.copy(alpha = 0.90f), radius = labelRadius)

            // ── 5. STRONG half-dark arc — the PRIMARY rotation cue ────────────
            // alpha = 0.55f makes a clearly visible dark/light split on the label.
            // As the disc spins, this "dark side" sweeps visibly around the label.
            drawArc(
                color      = Color.Black.copy(alpha = 0.55f),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter  = true,
                topLeft    = Offset(cx - labelRadius, cy - labelRadius),
                size       = Size(labelRadius * 2f, labelRadius * 2f)
            )

            // ── 6. Accent stripe — the SECONDARY rotation cue ─────────────────
            // A bright radial line from center toward label edge, like a record stripe.
            // Drawn at a fixed angle in local space; sweeps with every rotation tick.
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

            // ── 7. Inner ring on label ────────────────────────────────────────
            drawCircle(
                color  = Color.White.copy(alpha = 0.25f),
                radius = labelRadius * 0.82f,
                style  = Stroke(width = 1.dp.toPx())
            )
        }

        // ── 8. Offset icon — orbits as vinyl spins ────────────────────────────
        // Placed at top-left quadrant to complement the stripe at bottom-right.
        Icon(
            imageVector        = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint               = onAccent.copy(alpha = 0.85f),
            modifier           = Modifier
                .offset(x = (-14).dp, y = (-14).dp)
                .size(22.dp)
        )
    }
}