package com.engfred.musicplayer.feature_dj_mix.presentation.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BlurMaskFilter as AndroidBlurMaskFilter

// ─────────────────────────────────────────────────────────────────────────────
//  Read-only automated crossfader
//
//  Position logic:
//   • activeDeckIndex == 0 (Deck 1 playing): thumb starts LEFT (0f).
//     During crossfade it travels right (0→1) as Deck 2 fades in.
//   • activeDeckIndex == 1 (Deck 2 playing): thumb starts RIGHT (1f).
//     During crossfade it travels left (1→0) as Deck 1 fades in.
//
//  An "AUTO" badge + lock icon make it visually clear the fader is
//  engine-controlled and cannot be dragged.
// ─────────────────────────────────────────────────────────────────────────────

/** Accent colour used for all crossfader / mix-state chrome. */
private val CrossfaderAccent = Color(0xFFD32F2F)

@Composable
internal fun ProDJCrossfader(
    crossfadeProgress: Float,
    isCrossfading: Boolean,
    activeDeckIndex: Int,
    deck1Color: Color,
    deck2Color: Color,
    modifier: Modifier = Modifier,
) {
    // Fixed accent — no longer derived from a strategy enum.
    val accentColor = CrossfaderAccent

    val targetPos = when {
        !isCrossfading && activeDeckIndex == 0 -> 0f
        !isCrossfading && activeDeckIndex == 1 -> 1f
        activeDeckIndex == 0                   -> crossfadeProgress
        else                                   -> 1f - crossfadeProgress
    }
    val animPos by animateFloatAsState(
        targetValue   = targetPos,
        animationSpec = tween(200),
        label         = "xfader_pos"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0D1117))
            .border(
                width = 1.dp,
                color = if (isCrossfading) accentColor.copy(alpha = 0.40f)
                else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── State label + AUTO pill ───────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCrossfading) {
                    val glow by rememberInfiniteTransition(label = "badge_glow").animateFloat(
                        initialValue  = 0.50f,
                        targetValue   = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label         = "glow"
                    )
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = glow))
                    )
                } else {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text          = if (isCrossfading) "MIXING NOW" else "AUTO MIX",
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color         = accentColor.copy(alpha = if (isCrossfading) 1f else 0.70f)
                )
            }

            // AUTO badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Lock icon drawn with Canvas
                Canvas(modifier = Modifier.size(8.dp)) {
                    val w = size.width; val h = size.height
                    drawArc(
                        color      = Color.White.copy(alpha = 0.50f),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter  = false,
                        topLeft    = Offset(w * 0.20f, 0f),
                        size       = Size(w * 0.60f, h * 0.55f),
                        style      = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawRoundRect(
                        color        = Color.White.copy(alpha = 0.50f),
                        topLeft      = Offset(w * 0.08f, h * 0.42f),
                        size         = Size(w * 0.84f, h * 0.55f),
                        cornerRadius = CornerRadius(1.5.dp.toPx())
                    )
                }
                Text(
                    text          = "AUTO",
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color         = Color.White.copy(alpha = 0.40f),
                    fontSize      = 9.sp
                )
            }
        }

        // ── D1 / CROSSFADER label / D2 ────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            VcaIndicator(
                label     = "D1",
                color     = deck1Color,
                isLouder  = animPos < 0.5f,
                isCutting = animPos > 0.85f
            )
            Text(
                text          = "CROSSFADER",
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp,
                color         = Color.White.copy(alpha = 0.22f)
            )
            VcaIndicator(
                label     = "D2",
                color     = deck2Color,
                isLouder  = animPos >= 0.5f,
                isCutting = animPos < 0.15f
            )
        }

        // ── Fader track + thumb ───────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
        ) {
            val trackH  = 6.dp.toPx()
            val thumbW  = 22.dp.toPx()
            val thumbH  = size.height
            val trackY  = (size.height - trackH) / 2f
            val cr      = CornerRadius(trackH / 2f)
            val tcr     = CornerRadius(5.dp.toPx())
            val travelW = size.width - thumbW
            val thumbX  = (animPos * travelW).coerceIn(0f, travelW)
            val thumbCx = thumbX + thumbW / 2f

            // Track background
            drawRoundRect(
                brush        = Brush.horizontalGradient(
                    listOf(deck1Color.copy(0.20f), deck2Color.copy(0.20f))
                ),
                topLeft      = Offset(0f, trackY),
                size         = Size(size.width, trackH),
                cornerRadius = cr
            )

            // Active fill left of thumb
            if (thumbCx > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            deck1Color.copy(0.90f),
                            lerp(deck1Color, deck2Color, animPos).copy(0.90f)
                        ),
                        startX = 0f,
                        endX   = thumbCx.coerceAtLeast(1f)
                    ),
                    topLeft      = Offset(0f, trackY),
                    size         = Size(thumbCx, trackH),
                    cornerRadius = cr
                )
            }

            // Glow behind thumb during crossfade
            if (isCrossfading) {
                drawIntoCanvas { canvas ->
                    val p = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color       = lerp(deck1Color, deck2Color, animPos).copy(0.60f).toArgb()
                        maskFilter  = AndroidBlurMaskFilter(8.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawRoundRect(
                        thumbX - 2f, 0f,
                        thumbX + thumbW + 2f, thumbH,
                        5.dp.toPx(), 5.dp.toPx(), p
                    )
                }
            }

            // Thumb body
            val thumbColor = lerp(deck1Color, deck2Color, animPos)
            drawRoundRect(
                color        = thumbColor,
                topLeft      = Offset(thumbX, 0f),
                size         = Size(thumbW, thumbH),
                cornerRadius = tcr
            )

            // Thumb highlight sheen
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(0.35f), Color.Transparent),
                    startY = 0f,
                    endY   = thumbH * 0.45f
                ),
                topLeft      = Offset(thumbX, 0f),
                size         = Size(thumbW, thumbH * 0.5f),
                cornerRadius = tcr
            )

            // Grip lines
            val cx     = thumbX + thumbW / 2f
            val gripY1 = thumbH * 0.25f
            val gripY2 = thumbH * 0.75f
            for (xOff in listOf(-3.5.dp.toPx(), 0f, 3.5.dp.toPx())) {
                drawLine(
                    color       = Color.White.copy(0.55f),
                    start       = Offset(cx + xOff, gripY1),
                    end         = Offset(cx + xOff, gripY2),
                    strokeWidth = 1.5.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }

            // Position tick marks (0 %, 50 %, 100 %)
            val tickY = trackY + trackH + 3.dp.toPx()
            val tickR = 1.5.dp.toPx()
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val tx = fraction * size.width
                drawCircle(
                    color  = Color.White.copy(alpha = 0.18f),
                    radius = tickR,
                    center = Offset(tx.coerceIn(tickR, size.width - tickR), tickY)
                )
            }
        }

        // Footer status line
        Text(
            text      = if (isCrossfading) "Crossfade in progress…" else "Engine ready",
            style     = MaterialTheme.typography.labelSmall,
            color     = Color.White.copy(alpha = 0.22f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.fillMaxWidth(),
            fontSize  = 9.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  VCA level indicator (D1 / D2 labels with volume bar mini-graph)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun VcaIndicator(
    label: String,
    color: Color,
    isLouder: Boolean,
    isCutting: Boolean
) {
    val alpha = when {
        isCutting -> 0.20f
        isLouder  -> 1.00f
        else      -> 0.45f
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color      = color.copy(alpha = alpha)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { i ->
                val barAlpha = if (!isCutting && (isLouder || i < 1)) alpha else alpha * 0.3f
                Box(
                    Modifier
                        .width(3.dp)
                        .height((4 + i * 2).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color.copy(alpha = barAlpha))
                )
            }
        }
    }
}