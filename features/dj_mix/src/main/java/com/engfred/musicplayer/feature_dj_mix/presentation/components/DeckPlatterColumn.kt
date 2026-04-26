package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.graphics.BlurMaskFilter as AndroidBlurMaskFilter
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.engfred.musicplayer.core.domain.model.AudioFile

@Composable
internal fun DeckPlatterColumn(
    modifier: Modifier = Modifier,
    deckLabel: String,
    deckColor: Color,
    track: AudioFile?,
    albumArtUri: Uri?,
    bpm: Float?,
    isSpinning: Boolean,
    isActive: Boolean,
    positionMs: Long,
    durationMs: Long,
    timeToNextMixMs: Long?,
    vinylSize: Dp,
    alignEnd: Boolean,
) {
    val alignment = if (alignEnd) Alignment.End else Alignment.Start

    // ── PERFORMANCE FIX: Cache the glowing platter shadow paint ─────────────
    val density = LocalDensity.current
    val shadowPaint = remember(density, deckColor, vinylSize) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = deckColor.copy(alpha = 0.35f).toArgb()
            val blurRadiusPx = with(density) { vinylSize.toPx() * 0.12f }
            if (blurRadiusPx > 0f) {
                maskFilter = AndroidBlurMaskFilter(blurRadiusPx, AndroidBlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Deck label chip
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(alignment)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isActive) deckColor.copy(alpha = 0.18f)
                    else deckColor.copy(alpha = 0.06f)
                )
                .border(
                    width = 1.dp,
                    color = deckColor.copy(alpha = if (isActive) 0.55f else 0.15f),
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    val pulse by rememberInfiniteTransition(label = "dot_pulse").animateFloat(
                        initialValue = 0.4f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "pulse"
                    )
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(deckColor.copy(alpha = pulse))
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text          = deckLabel,
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    color         = deckColor.copy(alpha = if (isActive) 1f else 0.50f)
                )
            }
        }

        // Vinyl disc with glow border when active
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(
                    if (isActive) Modifier.drawBehind {
                        drawIntoCanvas { canvas ->
                            val r = vinylSize.toPx() / 2f + 4.dp.toPx()
                            canvas.nativeCanvas.drawCircle(center.x, center.y, r, shadowPaint)
                        }
                    } else Modifier
                )
        ) {
            VinylSection(
                albumArtUri     = albumArtUri,
                isPlaying       = isSpinning,
                timeToNextMixMs = if (isActive) timeToNextMixMs else null,
                primaryColor    = deckColor,
                vinylSize       = vinylSize
            )
        }

        // BPM readout
        Row(
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text          = bpm?.toInt()?.toString() ?: "---",
                fontSize      = (vinylSize.value * 0.22f).sp,
                fontWeight    = FontWeight.Black,
                letterSpacing = (-1).sp,
                color         = if (bpm != null) deckColor else deckColor.copy(alpha = 0.25f)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text          = "BPM",
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.sp,
                color         = deckColor.copy(alpha = if (bpm != null) 0.65f else 0.20f),
                modifier      = Modifier.padding(bottom = 2.dp)
            )
        }

        // Track title
        if (track != null) {
            Text(
                text       = track.title,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
            Text(
                text      = track.artist ?: "Unknown",
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text      = "— EMPTY —",
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        }

        // Time display
        if (durationMs > 0L) {
            val timeText = if (isActive && positionMs > 0L)
                "-${formatDeckMs(durationMs - positionMs)}"
            else
                formatDeckMs(durationMs)
            Text(
                text       = timeText,
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color      = if (isActive) deckColor.copy(alpha = 0.80f)
                else deckColor.copy(alpha = 0.30f),
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
        }
    }
}