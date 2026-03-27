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
import androidx.compose.material3.Surface
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
    customCueInMs: Long? = null,
    customMixOutMs: Long? = null,
    onAbortCrossfade: () -> Unit,
    onSetCueIn: () -> Unit,
    onSetMixOut: () -> Unit,
    onClearCues: () -> Unit
) {
    val playbackProgress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animatedPlayback by animateFloatAsState(
        targetValue    = playbackProgress,
        animationSpec  = tween(durationMillis = 300),
        label          = "playback_progress"
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

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {

        // ── Hero Zone: Huge Vinyl ─────────────────────────────────────────────
        Box(modifier = Modifier.size(188.dp), contentAlignment = Alignment.Center) {
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
                    FallbackVinylArt()
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Info Row: Title, Artist, Large BPM ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = trackTitle,
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color      = MaterialTheme.colorScheme.onBackground,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text     = trackArtist,
                    style    = MaterialTheme.typography.bodyLarge,
                    color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            bpm?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text       = it.toInt().toString(),
                        fontSize   = 34.sp,
                        fontWeight = FontWeight.Black,
                        color      = MaterialTheme.colorScheme.primary,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text          = "BPM",
                        style         = MaterialTheme.typography.labelSmall,
                        fontWeight    = FontWeight.Bold,
                        color         = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Strategy Badge (Always Visible) ───────────────────────────────────
        val strategyColor = currentMixStrategy.themeColor
        Surface(
            color = strategyColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(strategyColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text          = currentMixStrategy.uiLabel,
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color         = strategyColor
                )
            }
        }

        // ── Split Alpha Waveform ──────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val barCount = 48
            val source = waveform.ifEmpty { List(barCount) { 0.10f } }

            // Map source → barCount using index lerp instead of chunked().
            // chunked() only works when source.size >= barCount; when the engine
            // emits WAVEFORM_BARS=32 and barCount=48, chunked gives only 32 bars
            // and the right-half flatlines. Index mapping always produces barCount bars.
            val downsampled = List(barCount) { i ->
                val srcIdx = (i.toFloat() / barCount * source.size)
                    .toInt().coerceIn(0, source.size - 1)
                source[srcIdx]
            }

            val barWidth = size.width / downsampled.size
            val strokeWidth = (barWidth * 0.70f).coerceAtLeast(2f)
            val playheadX = size.width * animatedPlayback

            downsampled.forEachIndexed { index, amplitude ->
                val barHeight = (amplitude * size.height * 1.5f).coerceIn(4f, size.height)
                val x = index * barWidth + (barWidth / 2)
                val startY = (size.height - barHeight) / 2

                val isPlayed = x <= playheadX
                val barColor = if (isPlayed) primaryColor else primaryColor.copy(alpha = 0.20f)

                drawLine(
                    color = barColor,
                    start = Offset(x, startY),
                    end = Offset(x, startY + barHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // White Playhead Marker
            drawLine(
                color = Color.White,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Timers ────────────────────────────────────────────────────────────
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
                color      = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        // ── DJ Cue Overrides ──────────────────────────────────────────────────
//        Spacer(modifier = Modifier.height(8.dp))
//        Row(
//            modifier = Modifier.fillMaxWidth(),
//            horizontalArrangement = Arrangement.Center,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            CueButton(
//                text = "CUE IN",
//                isActive = customCueInMs != null,
//                onClick = onSetCueIn
//            )
//            Spacer(modifier = Modifier.width(12.dp))
//            CueButton(
//                text = "MIX OUT",
//                isActive = customMixOutMs != null,
//                onClick = onSetMixOut
//            )
//            Spacer(modifier = Modifier.width(12.dp))
//            CueButton(
//                text = "CLEAR",
//                isActive = false,
//                isDanger = true,
//                onClick = onClearCues,
//                enabled = customCueInMs != null || customMixOutMs != null
//            )
//        }

        // ── Next Track & Countdown Row ────────────────────────────────────────
        AnimatedVisibility(
            visible = countdownText != null || nextTrack != null,
            enter   = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
            exit    = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 }
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // UP NEXT Chip
                    if (nextTrack != null) {
                        Row(
                            modifier = Modifier
                                .background(
                                    color = primaryColor.copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text          = "UP NEXT",
                                style         = MaterialTheme.typography.labelSmall,
                                fontWeight    = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp,
                                color         = primaryColor.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text     = nextTrack.title,
                                style    = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Countdown Timer
                    if (countdownText != null) {
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
        }

        // ── Active Crossfade Abort ────────────────────────────────────────────
        if (isCrossfading) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    modifier       = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text       = "✕ ABORT",
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color      = strategyColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CueButton(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    isDanger: Boolean = false,
    enabled: Boolean = true
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
        isDanger -> MaterialTheme.colorScheme.error
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    }

    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = color.copy(alpha = if (isActive) 0.5f else 0.2f),
                shape = RoundedCornerShape(50)
            )
            .background(
                color = if (isActive) color.copy(alpha = 0.1f) else Color.Transparent,
                shape = RoundedCornerShape(50)
            )
            .height(28.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
            color = color
        )
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
            modifier           = Modifier.size(32.dp).padding(bottom = 12.dp, end = 12.dp)
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}