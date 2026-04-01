package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy
import kotlin.math.max

// ── Strategy UI Mapping ───────────────────────────────────────────────────────
//
// [TEST BUILD] Only HARMONIC and WIDE_TRANSITION branches are active.
// TRANSPARENT / SMOOTH / POWER_MIX branches are COMMENTED OUT.
// To restore: un-comment the [STRATEGY TEST] when-branches below.

val MixStrategy.uiLabel: String
    get() = when (this) {
        // MixStrategy.TRANSPARENT     -> "TRANSPARENT BLEND"  // [STRATEGY TEST]
        // MixStrategy.SMOOTH          -> "SMOOTH SYNC"         // [STRATEGY TEST]
        // MixStrategy.POWER_MIX       -> "POWER MIX"           // [STRATEGY TEST]
        MixStrategy.HARMONIC         -> "HARMONIC DROP"
        MixStrategy.WIDE_TRANSITION  -> "ENERGY VALLEY"
    }

val MixStrategy.themeColor: Color
    get() = when (this) {
        // MixStrategy.TRANSPARENT     -> Color(0xFF0288D1)  // [STRATEGY TEST]
        // MixStrategy.SMOOTH          -> Color(0xFF388E3C)  // [STRATEGY TEST]
        // MixStrategy.POWER_MIX       -> Color(0xFFF57C00)  // [STRATEGY TEST]
        MixStrategy.HARMONIC         -> Color(0xFF8E24AA)
        MixStrategy.WIDE_TRANSITION  -> Color(0xFFD32F2F)
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
) {
    val primaryColor    = MaterialTheme.colorScheme.primary
    val urgentColor     = Color(0xFFEF5350)
    val strategyColor   = currentMixStrategy.themeColor

    val arcColor: Color = when {
        timeToNextMixMs == null  -> primaryColor
        timeToNextMixMs <= 0L    -> urgentColor
        timeToNextMixMs < 5_000L -> lerp(primaryColor, urgentColor, 1f - timeToNextMixMs.toFloat() / 5_000f)
        else                     -> primaryColor
    }

    val countdownText: String? = when {
        timeToNextMixMs == null -> null
        timeToNextMixMs <= 0L   -> "MIXING…"
        else                    -> "MIX IN ~${max(1L, timeToNextMixMs / 1000L)}s"
    }

    val playbackProgress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animatedPlayback by animateFloatAsState(
        targetValue   = playbackProgress,
        animationSpec = tween(durationMillis = 300),
        label         = "playback_progress"
    )

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {

        // ── Section 1: Vinyl + countdown arc ─────────────────────────────────
        VinylSection(
            albumArtUri     = albumArtUri,
            isPlaying       = isPlaying,
            timeToNextMixMs = timeToNextMixMs,
            primaryColor    = primaryColor
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Section 2: Track info row ─────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
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
                        text          = it.toInt().toString(),
                        fontSize      = 34.sp,
                        fontWeight    = FontWeight.Black,
                        color         = primaryColor,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text          = "BPM",
                        style         = MaterialTheme.typography.labelSmall,
                        fontWeight    = FontWeight.Bold,
                        color         = primaryColor.copy(alpha = 0.7f),
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Strategy badge ────────────────────────────────────────────────────
        Surface(
            color    = strategyColor.copy(alpha = 0.15f),
            shape    = RoundedCornerShape(50),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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

        // ── Waveform ──────────────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val barCount = 48
            val source   = waveform.ifEmpty { List(barCount) { 0.10f } }
            val downsampled = List(barCount) { i ->
                val srcIdx = (i.toFloat() / barCount * source.size)
                    .toInt().coerceIn(0, source.size - 1)
                source[srcIdx]
            }
            val barWidth    = size.width / downsampled.size
            val strokeWidth = (barWidth * 0.70f).coerceAtLeast(2f)
            val playheadX   = size.width * animatedPlayback

            downsampled.forEachIndexed { index, amplitude ->
                val barHeight = (amplitude * size.height * 1.5f).coerceIn(4f, size.height)
                val x         = index * barWidth + (barWidth / 2)
                val startY    = (size.height - barHeight) / 2
                drawLine(
                    color       = if (x <= playheadX) primaryColor else primaryColor.copy(alpha = 0.20f),
                    start       = Offset(x, startY),
                    end         = Offset(x, startY + barHeight),
                    strokeWidth = strokeWidth,
                    cap         = StrokeCap.Round
                )
            }
            drawLine(
                color       = Color.White,
                start       = Offset(playheadX, 0f),
                end         = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx(),
                cap         = StrokeCap.Round
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
                color      = primaryColor
            )
            Text(
                text       = formatMs(durationMs),
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }

        // ── Up Next + Countdown ───────────────────────────────────────────────
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
                                text       = nextTrack.title,
                                style      = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color      = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis,
                                modifier   = Modifier.widthIn(max = 140.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

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

        // ── Crossfade abort bar ───────────────────────────────────────────────
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
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}