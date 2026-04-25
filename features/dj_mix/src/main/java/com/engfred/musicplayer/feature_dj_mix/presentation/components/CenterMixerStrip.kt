package com.engfred.musicplayer.feature_dj_mix.presentation.components

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.foundation.Canvas

// ─────────────────────────────────────────────────────────────────────────────
//  Centre mixer strip — VU meters + BPM sync indicator
//
//  VU logic:
//    • Deck 1 VU is active when Deck 1 is playing:
//        - activeDeckIndex == 0 && isPlaying   (Deck 1 is the main deck)
//        - activeDeckIndex == 1 && isCrossfading (Deck 1 is the incoming deck)
//    • Deck 2 VU is active when Deck 2 is playing:
//        - activeDeckIndex == 1 && isPlaying   (Deck 2 is the main deck)
//        - activeDeckIndex == 0 && isCrossfading (Deck 2 is the incoming deck)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun CenterMixerStrip(
    modifier: Modifier = Modifier,
    deck1Bpm: Float?,
    deck2Bpm: Float?,
    deck1Color: Color,
    deck2Color: Color,
    isPlaying: Boolean,
    isCrossfading: Boolean,
    activeDeckIndex: Int,
    vinylSize: Dp,
) {
    val bpmDiff = if (deck1Bpm != null && deck2Bpm != null) deck2Bpm - deck1Bpm else null
    val syncState = when {
        bpmDiff == null                   -> SyncState.UNKNOWN
        kotlin.math.abs(bpmDiff) < 2f     -> SyncState.SYNCED
        kotlin.math.abs(bpmDiff) < 6f     -> SyncState.CLOSE
        else                              -> SyncState.OFF
    }
    val syncColor = when (syncState) {
        SyncState.SYNCED  -> Color(0xFF00E676)
        SyncState.CLOSE   -> Color(0xFFFFAB40)
        SyncState.OFF     -> Color(0xFFFF5252)
        SyncState.UNKNOWN -> deck1Color.copy(alpha = 0.30f)
    }
    val syncLabel = when (syncState) {
        SyncState.SYNCED  -> "SYNC"
        SyncState.CLOSE   -> "~SYN"
        SyncState.OFF     -> "OFF"
        SyncState.UNKNOWN -> "---"
    }

    val vuInfinite = rememberInfiniteTransition(label = "vu")
    val d1Vu by vuInfinite.animateFloat(
        initialValue = 0.40f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            tween(180 + (System.nanoTime() % 120).toInt(), easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "d1_vu"
    )
    val d2Vu by vuInfinite.animateFloat(
        initialValue = 0.30f, targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            tween(210 + (System.nanoTime() % 100).toInt(), easing = LinearOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "d2_vu"
    )

    // ── FIX: derive VU activity from activeDeckIndex, not just isPlaying ──────
    // Deck 1 physical meter should bounce when Deck 1 is actually outputting audio:
    //   • it's the main/current deck  (activeDeckIndex == 0)  while playing, OR
    //   • it's the incoming deck      (activeDeckIndex == 1)  while crossfading
    val deck1HasAudio = (activeDeckIndex == 0 && isPlaying) || (activeDeckIndex == 1 && isCrossfading)
    val deck2HasAudio = (activeDeckIndex == 1 && isPlaying) || (activeDeckIndex == 0 && isCrossfading)

    val d1Level = if (deck1HasAudio) d1Vu else 0.08f
    val d2Level = if (deck2HasAudio) d2Vu else 0.08f

    Column(
        modifier            = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(vinylSize + 28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0D1117))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier            = Modifier.fillMaxSize()
            ) {
                Text(
                    text          = syncLabel,
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color         = syncColor
                )

                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(syncColor.copy(alpha = if (syncState == SyncState.SYNCED) 1f else 0.50f))
                )

                Row(
                    modifier              = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment     = Alignment.Bottom
                ) {
                    VuMeterBar(modifier = Modifier.weight(1f), level = d1Level, color = deck1Color)
                    VuMeterBar(modifier = Modifier.weight(1f), level = d2Level, color = deck2Color)
                }

                if (bpmDiff != null) {
                    val sign = if (bpmDiff >= 0) "+" else ""
                    Text(
                        text          = "${sign}${String.format("%.1f", bpmDiff)}",
                        style         = MaterialTheme.typography.labelSmall,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color         = syncColor.copy(alpha = 0.80f)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Segmented VU meter bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun VuMeterBar(modifier: Modifier, level: Float, color: Color) {
    val animLevel by animateFloatAsState(
        targetValue   = level,
        animationSpec = tween(80),
        label         = "vu_bar"
    )
    Canvas(modifier = modifier.fillMaxHeight()) {
        val w      = size.width
        val h      = size.height
        val segH   = h / 12f
        val segGap = 1.5.dp.toPx()
        val segPad = 1.dp.toPx()

        for (i in 11 downTo 0) {
            val fraction = (11 - i).toFloat() / 11f
            val isLit    = fraction <= animLevel
            val segColor = when {
                fraction > 0.80f -> Color(0xFFFF5252)
                fraction > 0.60f -> Color(0xFFFFAB40)
                else             -> color
            }
            val alpha = if (isLit) 1.0f else 0.10f
            val top   = i * (segH + segGap)
            drawRoundRect(
                color        = segColor.copy(alpha = alpha),
                topLeft      = Offset(segPad, top),
                size         = Size(w - segPad * 2f, segH),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}