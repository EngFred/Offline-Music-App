package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.graphics.BlurMaskFilter as AndroidBlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
//  Single scrolling waveform — the same visual language as the classic deck.
//  Played bars are fully coloured; upcoming bars are dimmed.
//  A white playhead line divides past from future.
//  Four beat-counter dots pulse in sync with the BPM at the top-right.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SingleDeckWaveform(
    waveform: List<Float>,
    positionMs: Long,
    durationMs: Long,
    deckColor: Color,
    currentBpm: Float?,
    isPlaying: Boolean,
    isCrossfading: Boolean,
    modifier: Modifier = Modifier,
) {
    val playFraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animPlayFraction by animateFloatAsState(
        targetValue   = playFraction,
        animationSpec = tween(300),
        label         = "single_waveform_pos"
    )

    // Beat counter dots
    var beatIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPlaying, currentBpm) {
        if (!isPlaying || currentBpm == null || currentBpm <= 0f) {
            beatIdx = 0; return@LaunchedEffect
        }
        val intervalMs = (60_000f / currentBpm).toLong().coerceAtLeast(100L)
        while (true) {
            delay(intervalMs)
            beatIdx = (beatIdx + 1) % 4
        }
    }

    Canvas(modifier = modifier) {
        val barCount  = 52
        val source    = if (waveform.isNotEmpty()) waveform else List(barCount) { 0.10f }
        val barWidth  = size.width / barCount
        val sw        = (barWidth * 0.68f).coerceAtLeast(2f)
        val playheadX = size.width * animPlayFraction
        val cy        = size.height / 2f

        // Background
        drawRect(color = Color(0xFF080C12))

        // Zero-crossing guide
        drawLine(
            color       = Color.White.copy(alpha = 0.04f),
            start       = Offset(0f, cy),
            end         = Offset(size.width, cy),
            strokeWidth = 1.dp.toPx()
        )

        // Waveform bars
        for (i in 0 until barCount) {
            val srcIdx = (i.toFloat() / barCount * source.size)
                .toInt().coerceIn(0, source.size - 1)
            val amp    = source.getOrElse(srcIdx) { 0.08f }
            val x      = i * barWidth + barWidth / 2f
            val h      = (amp * size.height * 1.55f).coerceIn(3f, size.height * 0.92f)
            val isPast = x <= playheadX
            drawLine(
                color       = deckColor.copy(alpha = if (isPast) 0.95f else 0.18f),
                start       = Offset(x, cy - h / 2f),
                end         = Offset(x, cy + h / 2f),
                strokeWidth = sw,
                cap         = StrokeCap.Round
            )
        }

        // Playhead glow
        drawIntoCanvas { canvas ->
            val p = android.graphics.Paint().apply {
                isAntiAlias = true
                color       = Color.White.copy(alpha = 0.55f).toArgb()
                maskFilter  = AndroidBlurMaskFilter(3.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawLine(playheadX, 0f, playheadX, size.height, p)
        }
        drawLine(
            color       = Color.White.copy(alpha = 0.90f),
            start       = Offset(playheadX, 0f),
            end         = Offset(playheadX, size.height),
            strokeWidth = 1.5.dp.toPx(),
            cap         = StrokeCap.Round
        )

        // Playhead top triangle
        val tri = 4.dp.toPx()
        val triPath = Path().apply {
            moveTo(playheadX, 0f)
            lineTo(playheadX - tri, 0f)
            lineTo(playheadX + tri, 0f)
            lineTo(playheadX, tri * 1.2f)
            close()
        }
        drawPath(triPath, color = deckColor)

        // Beat-counter dots (top-right corner)
        val dotR       = 3.dp.toPx()
        val dotY       = dotR + 3.dp.toPx()
        val dotSpacing = 9.dp.toPx()
        val totalDotsW = dotSpacing * 3f + dotR * 2f
        val startX     = size.width - totalDotsW - 8.dp.toPx()
        for (i in 0 until 4) {
            val dx   = startX + i * dotSpacing + dotR
            val isOn = isPlaying && i == beatIdx
            if (isOn) {
                drawIntoCanvas { canvas ->
                    val p = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = deckColor.copy(alpha = 0.7f).toArgb()
                        maskFilter = AndroidBlurMaskFilter(4.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawCircle(dx, dotY, dotR * 1.5f, p)
                }
            }
            drawCircle(
                color  = deckColor.copy(alpha = if (isOn) 1f else 0.18f),
                radius = dotR,
                center = Offset(dx, dotY)
            )
        }

        // Crossfade indicator strip at bottom-right when mixing
        if (isCrossfading) {
            val stripW = 48.dp.toPx()
            val stripH = 3.dp.toPx()
            val stripY = size.height - stripH - 4.dp.toPx()
            drawRoundRect(
                color        = deckColor.copy(alpha = 0.25f),
                topLeft      = Offset(size.width - stripW - 8.dp.toPx(), stripY),
                size         = Size(stripW, stripH),
                cornerRadius = CornerRadius(stripH / 2f)
            )
            drawRoundRect(
                color        = deckColor.copy(alpha = 0.90f),
                topLeft      = Offset(size.width - stripW - 8.dp.toPx(), stripY),
                size         = Size(stripW * 0.6f, stripH),
                cornerRadius = CornerRadius(stripH / 2f)
            )
        }
    }
}