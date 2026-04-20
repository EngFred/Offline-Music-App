package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy

/**
 * Dual-deck DJ layout.
 *
 * Renders the currently playing track on Deck 1 (left, spinning) and the
 * incoming next track on Deck 2 (right, spinning only during a crossfade),
 * connected by an animated crossfader strip that mirrors the live crossfade
 * progress.
 *
 * Layout overview:
 *
 *   ┌────────────────────────────────────────────┐
 *   │  DECK 1 [● PLAYING]   DECK 2 [NEXT]        │
 *   │  ┌──────────────┐     ┌──────────────┐     │
 *   │  │  🎵 (vinyl)  │     │  🎵 (vinyl)  │     │
 *   │  └──────────────┘     └──────────────┘     │
 *   │     120 BPM                125 BPM         │
 *   │     Track Title            Next Title      │
 *   │     Artist                 Artist          │
 *   │  ▓▓▓░░░░░░░░░░             ─────────────   │  ← waveforms
 *   │  0:45          3:20                        │
 *   ├────────────────────────────────────────────┤
 *   │       ── HARMONIC DROP ──                  │
 *   │  D1  [══════●─────────────]  D2            │  ← crossfader
 *   └────────────────────────────────────────────┘
 *
 * Used as an alternative to [NowPlayingSection] when the user enables
 * dual-deck layout via [MixStudioEvent.ToggleDeckLayout].
 */
@Composable
fun DualDeckSection(
    modifier: Modifier = Modifier,
    // Deck 1 — currently playing track
    currentTrack: AudioFile,
    currentBpm: Float?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    waveform: List<Float>,
    currentAlbumArtUri: Uri?,
    // Deck 2 — next / incoming track (may not yet be known)
    nextTrack: AudioFile?,
    nextBpm: Float?,
    nextAlbumArtUri: Uri?,
    // Shared mix state
    isCrossfading: Boolean,
    crossfadeProgress: Float,
    currentMixStrategy: MixStrategy,
    timeToNextMixMs: Long?,
) {
    val primaryColor   = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Two vinyl decks side by side ──────────────────────────────────────
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment    = Alignment.Top
        ) {
            // ── DECK 1 (current track) ────────────────────────────────────────
            DeckPanel(
                modifier        = Modifier.weight(1f),
                deckLabel       = "DECK 1",
                deckColor       = primaryColor,
                track           = currentTrack,
                albumArtUri     = currentAlbumArtUri,
                bpm             = currentBpm,
                isSpinning      = isPlaying,
                timeToNextMixMs = timeToNextMixMs,
                status          = if (isPlaying) DeckStatus.PLAYING else DeckStatus.PAUSED,
                waveform        = waveform,
                positionMs      = positionMs,
                durationMs      = durationMs,
            )

            // ── DECK 2 (next track) ───────────────────────────────────────────
            // Spins only when it's actively being crossfaded in so the user can
            // visually see Deck 2 "coming alive" during the transition.
            DeckPanel(
                modifier        = Modifier.weight(1f),
                deckLabel       = "DECK 2",
                deckColor       = secondaryColor,
                track           = nextTrack,
                albumArtUri     = nextAlbumArtUri,
                bpm             = nextBpm,
                isSpinning      = isCrossfading,
                timeToNextMixMs = null,
                status          = when {
                    nextTrack == null -> DeckStatus.EMPTY
                    isCrossfading     -> DeckStatus.PLAYING
                    else              -> DeckStatus.READY
                },
                waveform        = emptyList(),  // waveform data only for the current track
                positionMs      = 0L,
                durationMs      = nextTrack?.duration ?: 0L,
            )
        }

        // ── Animated crossfader strip ─────────────────────────────────────────
        CrossfaderStrip(
            crossfadeProgress  = crossfadeProgress,
            isCrossfading      = isCrossfading,
            deck1Color         = primaryColor,
            deck2Color         = secondaryColor,
            strategyColor      = currentMixStrategy.themeColor,
            currentMixStrategy = currentMixStrategy,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Deck status enum
// ─────────────────────────────────────────────────────────────────────────────

private enum class DeckStatus { PLAYING, PAUSED, READY, EMPTY }

// ─────────────────────────────────────────────────────────────────────────────
//  Single deck panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckPanel(
    modifier: Modifier = Modifier,
    deckLabel: String,
    deckColor: Color,
    track: AudioFile?,
    albumArtUri: Uri?,
    bpm: Float?,
    isSpinning: Boolean,
    timeToNextMixMs: Long?,
    status: DeckStatus,
    waveform: List<Float>,
    positionMs: Long,
    durationMs: Long,
) {
    val isActive = status == DeckStatus.PLAYING

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(
                width = 1.dp,
                color = deckColor.copy(alpha = if (isActive) 0.55f else 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Header row: deck label + status chip ──────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text          = deckLabel,
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Black,
                letterSpacing = 2.sp,
                color         = deckColor
            )
            DeckStatusChip(status = status, color = deckColor)
        }

        // ── Vinyl disc ────────────────────────────────────────────────────────
        VinylSection(
            albumArtUri     = albumArtUri,
            isPlaying       = isSpinning,
            // Only show the countdown arc on the active (playing) deck
            timeToNextMixMs = if (isActive) timeToNextMixMs else null,
            primaryColor    = deckColor,
            vinylSize       = 128.dp
        )

        // ── BPM readout ───────────────────────────────────────────────────────
        Row(
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text          = bpm?.toInt()?.toString() ?: "---",
                fontSize      = 26.sp,
                fontWeight    = FontWeight.Black,
                letterSpacing = (-1).sp,
                color         = if (bpm != null) deckColor else deckColor.copy(alpha = 0.30f)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text          = "BPM",
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.sp,
                color         = deckColor.copy(alpha = if (bpm != null) 0.70f else 0.30f),
                modifier      = Modifier.padding(bottom = 3.dp)
            )
        }

        // ── Track title & artist ──────────────────────────────────────────────
        if (track != null) {
            Text(
                text       = track.title,
                style      = MaterialTheme.typography.bodySmall,
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
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text      = "No track loaded",
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        }

        // ── Mini waveform — only for Deck 1 when data is available ───────────
        if (waveform.isNotEmpty()) {
            val playbackFraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            val animatedPlayback by animateFloatAsState(
                targetValue   = playbackFraction,
                animationSpec = tween(300),
                label         = "deck_mini_waveform"
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val barCount = 30
                val sampled  = List(barCount) { i ->
                    val idx = (i.toFloat() / barCount * waveform.size)
                        .toInt().coerceIn(0, waveform.size - 1)
                    waveform[idx]
                }
                val barW  = size.width / sampled.size
                val sw    = (barW * 0.65f).coerceAtLeast(1.5f)
                val headX = size.width * animatedPlayback
                sampled.forEachIndexed { i, amp ->
                    val h = (amp * size.height * 1.4f).coerceIn(2f, size.height)
                    val x = i * barW + barW / 2f
                    drawLine(
                        color       = if (x <= headX) deckColor else deckColor.copy(alpha = 0.20f),
                        start       = Offset(x, (size.height - h) / 2f),
                        end         = Offset(x, (size.height + h) / 2f),
                        strokeWidth = sw,
                        cap         = StrokeCap.Round
                    )
                }
                // Playhead
                drawLine(
                    color       = Color.White.copy(alpha = 0.75f),
                    start       = Offset(headX, 0f),
                    end         = Offset(headX, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text       = formatDeckMs(positionMs),
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color      = deckColor
                )
                Text(
                    text  = formatDeckMs(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
        } else if (track != null && durationMs > 0L) {
            // Show total duration even when waveform isn't available (Deck 2)
            Text(
                text      = formatDeckMs(durationMs),
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Deck status chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckStatusChip(status: DeckStatus, color: Color) {
    val label = when (status) {
        DeckStatus.PLAYING -> "PLAYING"
        DeckStatus.PAUSED  -> "PAUSED"
        DeckStatus.READY   -> "NEXT"
        DeckStatus.EMPTY   -> "EMPTY"
    }
    val contentAlpha = when (status) {
        DeckStatus.PLAYING -> 1.0f
        DeckStatus.PAUSED  -> 0.65f
        DeckStatus.READY   -> 0.80f
        DeckStatus.EMPTY   -> 0.30f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        // Pulsing dot on PLAYING status
        if (status == DeckStatus.PLAYING) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text          = label,
            style         = MaterialTheme.typography.labelSmall,
            fontWeight    = FontWeight.Black,
            letterSpacing = 0.5.sp,
            color         = color.copy(alpha = contentAlpha)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Crossfader strip
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Horizontal crossfader that mirrors the live [crossfadeProgress] (0–1).
 *
 * When not crossfading the thumb rests at centre (0.5).
 * When crossfading, the thumb animates from left (D1) to right (D2),
 * with a gradient track that transitions between the two deck colours.
 * Three vertical grip lines are drawn on the thumb, like a real DJ fader.
 */
@Composable
private fun CrossfaderStrip(
    crossfadeProgress: Float,
    isCrossfading: Boolean,
    deck1Color: Color,
    deck2Color: Color,
    strategyColor: Color,
    currentMixStrategy: MixStrategy,
) {
    // When not crossfading, keep thumb centred so it doesn't snap back to 0
    val targetPos = if (isCrossfading) crossfadeProgress else 0.5f
    val animatedProgress by animateFloatAsState(
        targetValue   = targetPos,
        animationSpec = tween(durationMillis = 200),
        label         = "crossfader_position"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Mix strategy badge ────────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(strategyColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text          = currentMixStrategy.uiLabel,
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Black,
                letterSpacing = 1.sp,
                color         = strategyColor
            )
        }

        // ── D1 / label / D2 labels ────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text       = "D1",
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                // D1 label brightens when thumb is on D1 side
                color      = deck1Color.copy(alpha = if (animatedProgress < 0.5f) 1f else 0.40f)
            )
            Text(
                text          = "CROSSFADER",
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color         = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
            Text(
                text       = "D2",
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                // D2 label brightens when thumb is on D2 side
                color      = deck2Color.copy(alpha = if (animatedProgress >= 0.5f) 1f else 0.40f)
            )
        }

        // ── Fader track + thumb (drawn via Canvas for pixel precision) ────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
        ) {
            val trackH      = 5.dp.toPx()
            val thumbW      = 18.dp.toPx()
            val thumbH      = size.height
            val trackY      = (size.height - trackH) / 2f
            val trackCorner = CornerRadius(trackH / 2f)
            val thumbCorner = CornerRadius(4.dp.toPx())

            // Background gradient track (full width, D1 → D2 colours)
            drawRoundRect(
                brush        = Brush.horizontalGradient(
                    colors   = listOf(deck1Color.copy(0.25f), deck2Color.copy(0.25f))
                ),
                topLeft      = Offset(0f, trackY),
                size         = Size(size.width, trackH),
                cornerRadius = trackCorner
            )

            // Filled portion: from left edge to thumb centre
            val thumbCentreX = animatedProgress * (size.width - thumbW) + thumbW / 2f
            drawRoundRect(
                brush        = Brush.horizontalGradient(
                    colors  = listOf(
                        deck1Color.copy(0.80f),
                        lerp(deck1Color, deck2Color, animatedProgress).copy(0.80f)
                    ),
                    startX  = 0f,
                    endX    = thumbCentreX.coerceAtLeast(1f)
                ),
                topLeft      = Offset(0f, trackY),
                size         = Size(thumbCentreX, trackH),
                cornerRadius = trackCorner
            )

            // Thumb body — colour lerps between D1 and D2 colours
            val thumbX     = (animatedProgress * (size.width - thumbW)).coerceIn(0f, size.width - thumbW)
            val thumbColor = lerp(deck1Color, deck2Color, animatedProgress)
            drawRoundRect(
                color        = thumbColor,
                topLeft      = Offset(thumbX, 0f),
                size         = Size(thumbW, thumbH),
                cornerRadius = thumbCorner
            )

            // Three vertical grip lines on the thumb face
            val gripColor = Color.White.copy(alpha = 0.55f)
            val gripSW    = 1.5.dp.toPx()
            val cy        = size.height / 2f
            val cx        = thumbX + thumbW / 2f
            val gripHalf  = 5.dp.toPx()
            for (xOff in listOf(-3.dp.toPx(), 0f, 3.dp.toPx())) {
                drawLine(
                    color       = gripColor,
                    start       = Offset(cx + xOff, cy - gripHalf),
                    end         = Offset(cx + xOff, cy + gripHalf),
                    strokeWidth = gripSW,
                    cap         = StrokeCap.Round
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDeckMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}