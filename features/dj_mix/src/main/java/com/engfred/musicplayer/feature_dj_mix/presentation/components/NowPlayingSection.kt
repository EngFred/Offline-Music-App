package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy
import kotlin.math.max

val MixStrategy.uiLabel: String
    get() = when (this) {
        MixStrategy.TRANSPARENT     -> "TRANSPARENT BLEND"
        MixStrategy.SMOOTH          -> "SMOOTH SYNC"
        MixStrategy.POWER_MIX       -> "POWER MIX"
        MixStrategy.HARMONIC        -> "HARMONIC DROP"
        MixStrategy.WIDE_TRANSITION -> "ENERGY VALLEY"
    }

val MixStrategy.themeColor: Color
    get() = when (this) {
        MixStrategy.TRANSPARENT     -> Color(0xFF0288D1)
        MixStrategy.SMOOTH          -> Color(0xFF388E3C)
        MixStrategy.POWER_MIX       -> Color(0xFFF57C00)
        MixStrategy.HARMONIC        -> Color(0xFF8E24AA)
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
    isPlaying: Boolean,
    timeToNextMixMs: Long? = null,
    nextTrack: AudioFile? = null,
    onAbortCrossfade: () -> Unit
) {
    val playbackProgress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animatedPlayback by animateFloatAsState(
        targetValue    = playbackProgress,
        animationSpec  = tween(durationMillis = 300),
        label          = "playback_progress"
    )

    // ── Vinyl rotation ────────────────────────────────────────────────────────
    // FIX: snapTo is now called ONCE when playback starts (outside the while loop)
    // so it only normalizes the angle on play, not every 4 seconds.
    // LaunchedEffect(isPlaying) already cancels the previous coroutine when the
    // key changes, so rapid play/pause flips are safe.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Normalize once on start so rotation.value never grows unbounded
            rotation.snapTo(rotation.value % 360f)
            while (true) {
                rotation.animateTo(
                    targetValue   = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
                )
            }
        }
        // When paused the coroutine is simply cancelled — rotation freezes in place
    }

    // ── Mix countdown arc state ───────────────────────────────────────────────
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

    val primaryColor = MaterialTheme.colorScheme.primary
    val urgentColor  = Color(0xFFEF5350)
    val arcColor: Color = when {
        timeToNextMixMs == null  -> primaryColor
        timeToNextMixMs <= 0L    -> urgentColor
        timeToNextMixMs < 5_000L -> lerp(primaryColor, urgentColor, 1f - timeToNextMixMs.toFloat() / 5_000f)
        else                     -> primaryColor
    }

    val countdownText: String? = when {
        timeToNextMixMs == null -> null
        timeToNextMixMs <= 0L   -> "MIXING\u2026"
        else                    -> "MIX IN ~${max(1L, timeToNextMixMs / 1000L)}s"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Vinyl record with countdown arc overlay ───────────────────────
            Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 3.5.dp.toPx()
                    val inset       = strokeWidth / 2f
                    val arcSize     = Size(size.width - inset * 2, size.height - inset * 2)
                    val topLeft     = Offset(inset, inset)

                    if (countdownMaxMs > 0L || animatedCountdownFraction > 0f) {
                        drawArc(
                            color      = arcColor.copy(alpha = 0.18f),
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
                            color      = arcColor.copy(alpha = 0.92f),
                            startAngle = -90f,
                            sweepAngle = 360f * animatedCountdownFraction,
                            useCenter  = false,
                            topLeft    = topLeft,
                            size       = arcSize,
                            style      = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
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
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
                    } else {
                        FallbackVinylArt()
                    }
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = trackTitle,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text     = trackArtist,
                    style    = MaterialTheme.typography.bodyLarge,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            bpm?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text       = it.toInt().toString(),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color      = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text          = "BPM",
                        style         = MaterialTheme.typography.labelSmall,
                        fontWeight    = FontWeight.Bold,
                        color         = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Waveform bars ─────────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            val source     = waveform.ifEmpty { List(48) { 0.10f } }
            val barCount   = 48
            val chunkSize  = (source.size / barCount).coerceAtLeast(1)
            val downsampled = source.chunked(chunkSize).map { chunk -> chunk.maxOrNull() ?: 0f }

            val barWidth    = size.width / downsampled.size
            val strokeWidth = (barWidth * 0.70f).coerceAtLeast(2f)

            downsampled.forEachIndexed { index, amplitude ->
                val barHeight = (amplitude * size.height * 1.5f).coerceIn(4f, size.height)
                val x         = index * barWidth + (barWidth / 2)
                val startY    = (size.height - barHeight) / 2
                drawLine(
                    color       = primaryColor.copy(alpha = 0.80f),
                    start       = Offset(x, startY),
                    end         = Offset(x, startY + barHeight),
                    strokeWidth = strokeWidth,
                    cap         = StrokeCap.Round
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text       = formatMs(positionMs),
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
            Text(
                text       = formatMs(durationMs),
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress   = { animatedPlayback },
            modifier   = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            strokeCap  = StrokeCap.Round,
            color      = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        )

        // ── Countdown row: UP NEXT chip (left) + countdown badge (right) ──────
        // The whole row slides in together when the engine enters the pre-buffer
        // zone. Having both pieces on one row makes the relationship obvious —
        // "this track is coming, and it's coming in ~Xs".
        AnimatedVisibility(
            visible = countdownText != null,
            enter   = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
            exit    = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 }
        ) {
            if (countdownText != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // ── UP NEXT chip ──────────────────────────────────────────
                    // Shown only when the pre-buffer has resolved a next track.
                    // Uses a very low-alpha pill so it reads as "info" not "action".
                    if (nextTrack != null) {
                        Row(
                            modifier = Modifier
                                .background(
                                    color = primaryColor.copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text          = "UP NEXT",
                                style         = MaterialTheme.typography.labelSmall,
                                fontWeight    = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp,
                                color         = primaryColor.copy(alpha = 0.65f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text     = nextTrack.title,
                                style    = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color    = Color.White.copy(alpha = 0.70f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp)
                            )
                        }
                    } else {
                        // Keep the right side pinned to the end even when there's no chip
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // ── Countdown indicator ───────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(arcColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text          = countdownText,
                            style         = MaterialTheme.typography.labelSmall,
                            fontWeight    = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color         = arcColor
                        )
                    }
                }
            }
        }

        // ── Active crossfade progress ─────────────────────────────────────────
        if (isCrossfading) {
            Spacer(modifier = Modifier.height(24.dp))
            val strategyColor = currentMixStrategy.themeColor
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector        = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint               = strategyColor,
                    modifier           = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text          = currentMixStrategy.uiLabel,
                    style         = MaterialTheme.typography.labelMedium,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color         = strategyColor,
                    modifier      = Modifier.padding(end = 8.dp)
                )
                val animatedCrossfade by animateFloatAsState(
                    targetValue   = crossfadeProgress,
                    animationSpec = tween(100),
                    label         = "crossfade_progress"
                )
                LinearProgressIndicator(
                    progress   = { animatedCrossfade },
                    modifier   = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    strokeCap  = StrokeCap.Round,
                    color      = strategyColor,
                    trackColor = strategyColor.copy(alpha = 0.15f)
                )
                TextButton(
                    onClick        = onAbortCrossfade,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier       = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text       = "✕",
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color      = strategyColor.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FallbackVinylArt(modifier: Modifier = Modifier) {
    val primaryColor   = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = size.width / 2
            drawCircle(color = Color(0xFF333333), radius = maxRadius)
            drawCircle(color = Color(0xFF141414), radius = maxRadius - 1.dp.toPx())
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.White.copy(alpha = 0.00f), Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.00f), Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.00f)
                    )
                ),
                radius = maxRadius
            )
            for (i in 3..9) {
                drawCircle(
                    color  = Color.Black.copy(alpha = 0.80f),
                    radius = maxRadius * (i / 10f),
                    style  = Stroke(width = 1.dp.toPx())
                )
            }
            val labelRadius = maxRadius * 0.45f
            drawCircle(color = primaryColor, radius = labelRadius)
            drawCircle(
                color  = onPrimaryColor.copy(alpha = 0.30f),
                radius = labelRadius * 0.85f,
                style  = Stroke(width = 1.dp.toPx())
            )
            drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = labelRadius * 0.50f)
        }
        Icon(
            imageVector        = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint               = onPrimaryColor.copy(alpha = 0.90f),
            modifier           = Modifier.size(22.dp).padding(bottom = 12.dp, end = 12.dp)
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}