package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.graphics.BlurMaskFilter as AndroidBlurMaskFilter
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.data.crossfade.MixStrategy
import kotlinx.coroutines.delay

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PRO DJ DUAL-DECK SECTION  —  Virtual DJ–inspired booth layout
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Layout (portrait):
 *
 *  ┌──────────────────────────────────────────────┐
 *  │  ▓▓▓▓▓▓│░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░    │  ← single waveform
 *  ├──────────────────────────────────────────────┤
 *  │ ┌────┐  ┌──────────────┐  ┌────┐             │
 *  │ │vinyl│  │  VU METERS  │  │vinyl│             │  ← decks + mixer
 *  │ │     │  │  SYNC: BPM  │  │     │             │
 *  │ └────┘  └──────────────┘  └────┘             │
 *  │  120 BPM    ±2 BPM       125 BPM             │
 *  │  Title 1                  Title 2            │
 *  ├──────────────────────────────────────────────┤
 *  │ ── HARMONIC DROP ── [AUTO]                   │  ← read-only crossfader
 *  │ D1  [●──────────────────────────────]  D2    │  ← starts at D1 side
 *  └──────────────────────────────────────────────┘
 *
 *  Deck alternation: each new song rotates on the opposite deck.
 *  Song 1 → Deck 1 spins. Song 2 → Deck 2 spins. Etc.
 */
@Composable
fun DualDeckSection(
    modifier: Modifier = Modifier,
    // Deck 1 — current track
    currentTrack: AudioFile,
    currentBpm: Float?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    waveform: List<Float>,
    currentAlbumArtUri: Uri?,
    // Deck 2 — next / incoming track
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
    val strategyColor  = currentMixStrategy.themeColor

    // ── Deck alternation: toggle which physical deck plays the current song ───
    // activeDeckIndex=0 → current song on Deck 1, activeDeckIndex=1 → current on Deck 2
    var activeDeckIndex by remember { mutableIntStateOf(0) }
    var wasCrossfading  by remember { mutableStateOf(false) }

    LaunchedEffect(isCrossfading) {
        if (wasCrossfading && !isCrossfading) {
            // Crossfade just completed → flip the active deck
            activeDeckIndex = 1 - activeDeckIndex
        }
        wasCrossfading = isCrossfading
    }

    // ── Derive per-deck values from activeDeckIndex ───────────────────────────
    val deck1Track     = if (activeDeckIndex == 0) currentTrack   else nextTrack
    val deck2Track     = if (activeDeckIndex == 0) nextTrack       else currentTrack
    val deck1Bpm       = if (activeDeckIndex == 0) currentBpm      else nextBpm
    val deck2Bpm       = if (activeDeckIndex == 0) nextBpm         else currentBpm
    val deck1Art       = if (activeDeckIndex == 0) currentAlbumArtUri else nextAlbumArtUri
    val deck2Art       = if (activeDeckIndex == 0) nextAlbumArtUri    else currentAlbumArtUri

    // The deck that holds the current (playing) song is always spinning while playing.
    // The other deck starts spinning only during crossfade (incoming track).
    val deck1Spinning  = if (activeDeckIndex == 0) isPlaying    else isCrossfading
    val deck2Spinning  = if (activeDeckIndex == 0) isCrossfading else isPlaying
    val deck1Active    = if (activeDeckIndex == 0) true          else isCrossfading
    val deck2Active    = if (activeDeckIndex == 0) isCrossfading else true

    val deck1Position  = if (activeDeckIndex == 0) positionMs else 0L
    val deck1Duration  = if (activeDeckIndex == 0) durationMs else (nextTrack?.duration ?: 0L)
    val deck2Position  = if (activeDeckIndex == 0) 0L else positionMs
    val deck2Duration  = if (activeDeckIndex == 0) (nextTrack?.duration ?: 0L) else durationMs
    val deck1TimeToMix = if (activeDeckIndex == 0) timeToNextMixMs else null
    val deck2TimeToMix = if (activeDeckIndex == 0) null else timeToNextMixMs

    // Active deck's colour (for waveform accent)
    val activeColor = if (activeDeckIndex == 0) primaryColor else secondaryColor

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val totalWidth  = maxWidth
        val vinylSize   = when {
            totalWidth >= 420.dp -> 120.dp
            totalWidth >= 360.dp ->  96.dp
            else                 ->  80.dp
        }
        val waveHeight  = if (totalWidth >= 360.dp) 72.dp else 58.dp
        val mixerWidth  = if (totalWidth >= 360.dp) 62.dp else 50.dp

        Column(
            modifier            = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── SECTION 1: Single scrolling waveform (same style as classic mode) ──
            SingleDeckWaveform(
                waveform      = waveform,
                positionMs    = positionMs,
                durationMs    = durationMs,
                deckColor     = activeColor,
                currentBpm    = currentBpm,
                isPlaying     = isPlaying,
                isCrossfading = isCrossfading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(waveHeight)
                    .clip(RoundedCornerShape(10.dp))
            )

            // ── SECTION 2: Deck platters + centre mixer ──────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.Top
            ) {
                DeckPlatterColumn(
                    modifier        = Modifier.weight(1f),
                    deckLabel       = "DECK 1",
                    deckColor       = primaryColor,
                    track           = deck1Track,
                    albumArtUri     = deck1Art,
                    bpm             = deck1Bpm,
                    isSpinning      = deck1Spinning,
                    isActive        = deck1Active,
                    positionMs      = deck1Position,
                    durationMs      = deck1Duration,
                    timeToNextMixMs = deck1TimeToMix,
                    vinylSize       = vinylSize,
                    alignEnd        = false,
                )

                CenterMixerStrip(
                    modifier      = Modifier.width(mixerWidth),
                    deck1Bpm      = deck1Bpm,
                    deck2Bpm      = deck2Bpm,
                    deck1Color    = primaryColor,
                    deck2Color    = secondaryColor,
                    strategyColor = strategyColor,
                    isPlaying     = isPlaying,
                    isCrossfading = isCrossfading,
                    vinylSize     = vinylSize,
                )

                DeckPlatterColumn(
                    modifier        = Modifier.weight(1f),
                    deckLabel       = "DECK 2",
                    deckColor       = secondaryColor,
                    track           = deck2Track,
                    albumArtUri     = deck2Art,
                    bpm             = deck2Bpm,
                    isSpinning      = deck2Spinning,
                    isActive        = deck2Active,
                    positionMs      = deck2Position,
                    durationMs      = deck2Duration,
                    timeToNextMixMs = deck2TimeToMix,
                    vinylSize       = vinylSize,
                    alignEnd        = true,
                )
            }

            // ── SECTION 3: Read-only auto crossfader ─────────────────────────
            ProDJCrossfader(
                crossfadeProgress  = crossfadeProgress,
                isCrossfading      = isCrossfading,
                activeDeckIndex    = activeDeckIndex,
                deck1Color         = primaryColor,
                deck2Color         = secondaryColor,
                strategyColor      = strategyColor,
                currentMixStrategy = currentMixStrategy,
                modifier           = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  1. SINGLE DECK WAVEFORM  (matches the classic NowPlaying waveform style)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Single scrolling waveform — the same visual language as the classic deck view.
 * Played bars are fully coloured; upcoming bars are dimmed.
 * A white playhead line divides past from future.
 * Four beat-counter dots pulse in sync with the BPM at the top-right.
 */
@Composable
private fun SingleDeckWaveform(
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
        val barCount   = 52
        val source     = if (waveform.isNotEmpty()) waveform else List(barCount) { 0.10f }
        val barWidth   = size.width / barCount
        val sw         = (barWidth * 0.68f).coerceAtLeast(2f)
        val playheadX  = size.width * animPlayFraction
        val cy         = size.height / 2f

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
            val dx    = startX + i * dotSpacing + dotR
            val isOn  = isPlaying && i == beatIdx
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

// ─────────────────────────────────────────────────────────────────────────────
//  2. DECK PLATTER COLUMN  (unchanged from original)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeckPlatterColumn(
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
                            val p = android.graphics.Paint().apply {
                                isAntiAlias = true
                                color = deckColor.copy(alpha = 0.35f).toArgb()
                                maskFilter = AndroidBlurMaskFilter(
                                    (vinylSize.toPx() * 0.12f),
                                    AndroidBlurMaskFilter.Blur.NORMAL
                                )
                            }
                            val r = vinylSize.toPx() / 2f + 4.dp.toPx()
                            canvas.nativeCanvas.drawCircle(center.x, center.y, r, p)
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

// ─────────────────────────────────────────────────────────────────────────────
//  3. CENTER MIXER STRIP  (unchanged from original)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CenterMixerStrip(
    modifier: Modifier = Modifier,
    deck1Bpm: Float?,
    deck2Bpm: Float?,
    deck1Color: Color,
    deck2Color: Color,
    strategyColor: Color,
    isPlaying: Boolean,
    isCrossfading: Boolean,
    vinylSize: Dp,
) {
    val bpmDiff = if (deck1Bpm != null && deck2Bpm != null) deck2Bpm - deck1Bpm else null
    val syncState = when {
        bpmDiff == null                      -> SyncState.UNKNOWN
        kotlin.math.abs(bpmDiff) < 2f        -> SyncState.SYNCED
        kotlin.math.abs(bpmDiff) < 6f        -> SyncState.CLOSE
        else                                 -> SyncState.OFF
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
    val d1Level = if (isPlaying) d1Vu else 0.08f
    val d2Level = if (isCrossfading) d2Vu else 0.08f

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

private enum class SyncState { SYNCED, CLOSE, OFF, UNKNOWN }

@Composable
private fun VuMeterBar(modifier: Modifier, level: Float, color: Color) {
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

// ─────────────────────────────────────────────────────────────────────────────
//  4. PRO DJ CROSSFADER  — read-only, starts at active deck's side
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Read-only automated crossfader.
 *
 * Position logic:
 *  • activeDeckIndex == 0 (Deck 1 playing): thumb starts at LEFT (0f).
 *    During crossfade it travels right (0f → 1f) as Deck 2 fades in.
 *  • activeDeckIndex == 1 (Deck 2 playing): thumb starts at RIGHT (1f).
 *    During crossfade it travels left (1f → 0f) as Deck 1 fades in.
 *
 * An "AUTO" badge + lock icon make it visually clear the fader is
 * engine-controlled and cannot be dragged.
 */
@Composable
private fun ProDJCrossfader(
    crossfadeProgress: Float,
    isCrossfading: Boolean,
    activeDeckIndex: Int,          // 0 = Deck 1 active, 1 = Deck 2 active
    deck1Color: Color,
    deck2Color: Color,
    strategyColor: Color,
    currentMixStrategy: MixStrategy,
    modifier: Modifier = Modifier,
) {
    // Thumb position: starts at the active deck's side, moves toward the incoming deck
    val targetPos = when {
        !isCrossfading && activeDeckIndex == 0 -> 0f
        !isCrossfading && activeDeckIndex == 1 -> 1f
        activeDeckIndex == 0                   -> crossfadeProgress          // 0 → 1
        else                                   -> 1f - crossfadeProgress     // 1 → 0
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
                color = if (isCrossfading) strategyColor.copy(alpha = 0.40f)
                else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Top row: strategy badge  +  AUTO pill ─────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Strategy badge (left)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCrossfading) {
                    val glow by rememberInfiniteTransition(label = "badge_glow").animateFloat(
                        initialValue = 0.50f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "glow"
                    )
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(strategyColor.copy(alpha = glow))
                    )
                } else {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(strategyColor))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text          = currentMixStrategy.uiLabel,
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color         = strategyColor.copy(alpha = if (isCrossfading) 1f else 0.70f)
                )
            }

            // AUTO badge (right) — communicates the fader is engine-controlled
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Small lock icon drawn via Canvas
                Canvas(modifier = Modifier.size(8.dp)) {
                    val w = size.width; val h = size.height
                    // Shackle arc (top half)
                    drawArc(
                        color      = Color.White.copy(alpha = 0.50f),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter  = false,
                        topLeft    = Offset(w * 0.20f, 0f),
                        size       = Size(w * 0.60f, h * 0.55f),
                        style      = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                    // Body rectangle
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

        // ── D1 / CROSSFADER label / D2 row ────────────────────────────────────
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

        // ── Fader track + thumb ────────────────────────────────────────────────
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
                brush = Brush.horizontalGradient(
                    listOf(deck1Color.copy(0.20f), deck2Color.copy(0.20f))
                ),
                topLeft      = Offset(0f, trackY),
                size         = Size(size.width, trackH),
                cornerRadius = cr
            )

            // Filled "played" portion
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

            // Thumb glow when crossfading
            if (isCrossfading) {
                drawIntoCanvas { canvas ->
                    val p = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = lerp(deck1Color, deck2Color, animPos).copy(0.60f).toArgb()
                        maskFilter = AndroidBlurMaskFilter(8.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawRoundRect(
                        thumbX - 2f, 0f, thumbX + thumbW + 2f, thumbH,
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
            // Specular highlight
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
            // Three grip lines
            val cx    = thumbX + thumbW / 2f
            val gripY1 = thumbH * 0.25f; val gripY2 = thumbH * 0.75f
            for (xOff in listOf(-3.5.dp.toPx(), 0f, 3.5.dp.toPx())) {
                drawLine(
                    color       = Color.White.copy(0.55f),
                    start       = Offset(cx + xOff, gripY1),
                    end         = Offset(cx + xOff, gripY2),
                    strokeWidth = 1.5.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }

            // Dotted track marks (tick marks at 0%, 50%, 100%)
            // These reinforce the visual that this is a fixed scale, not draggable
            val tickY  = trackY + trackH + 3.dp.toPx()
            val tickR  = 1.5.dp.toPx()
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val tx = fraction * size.width
                drawCircle(
                    color  = Color.White.copy(alpha = 0.18f),
                    radius = tickR,
                    center = Offset(tx.coerceIn(tickR, size.width - tickR), tickY)
                )
            }
        }

        // ── "Mix engine controlled" helper text ───────────────────────────────
        Text(
            text      = if (isCrossfading) "Mixing in progress…" else "Controlled by mix engine",
            style     = MaterialTheme.typography.labelSmall,
            color     = Color.White.copy(alpha = 0.22f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.fillMaxWidth(),
            fontSize  = 9.sp
        )
    }
}

@Composable
private fun VcaIndicator(label: String, color: Color, isLouder: Boolean, isCutting: Boolean) {
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

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDeckMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}