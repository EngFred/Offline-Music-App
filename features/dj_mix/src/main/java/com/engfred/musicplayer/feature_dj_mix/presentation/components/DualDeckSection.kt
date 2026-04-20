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
 *  │  DECK 1   [●●●●] BPM diff [●●●●]   DECK 2   │  ← dual waveform
 *  │ ▓▓▓▓▓▓│░░░░░░      ░░░░│             row
 *  ├──────────────────────────────────────────────┤
 *  │ ┌────┐  ┌──────────────┐  ┌────┐             │
 *  │ │ 🎵 │  │  VU METERS   │  │ 🎵 │             │  ← decks + mixer
 *  │ │vinyl│  │  SYNC: BPM  │  │vinyl│             │
 *  │ └────┘  └──────────────┘  └────┘             │
 *  │  120 BPM    ±2 BPM       125 BPM             │
 *  │  Title 1                  Title 2            │
 *  ├──────────────────────────────────────────────┤
 *  │ ── HARMONIC DROP ──                          │  ← crossfader
 *  │ D1  [══════●──────────────]  D2              │
 *  └──────────────────────────────────────────────┘
 *
 *  In landscape the component fills the left panel and scales proportionally.
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

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val totalWidth  = maxWidth
        val isWide      = totalWidth >= 360.dp
        val vinylSize   = when {
            totalWidth >= 420.dp -> 120.dp
            totalWidth >= 360.dp ->  96.dp
            else                 ->  80.dp
        }
        val waveHeight  = if (isWide) 82.dp else 64.dp
        val mixerWidth  = if (isWide) 62.dp else 50.dp

        Column(
            modifier            = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── SECTION 1: Dual scrolling waveform ───────────────────────────
            DualScrollingWaveform(
                waveform          = waveform,
                positionMs        = positionMs,
                durationMs        = durationMs,
                deck1Color        = primaryColor,
                deck2Color        = secondaryColor,
                currentBpm        = currentBpm,
                isCrossfading     = isCrossfading,
                crossfadeProgress = crossfadeProgress,
                isPlaying         = isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(waveHeight)
                    .clip(RoundedCornerShape(10.dp))
            )

            // ── SECTION 2: Deck platters + center mixer ──────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.Top
            ) {
                // Deck 1 (current)
                DeckPlatterColumn(
                    modifier        = Modifier.weight(1f),
                    deckLabel       = "DECK 1",
                    deckColor       = primaryColor,
                    track           = currentTrack,
                    albumArtUri     = currentAlbumArtUri,
                    bpm             = currentBpm,
                    isSpinning      = isPlaying,
                    isActive        = true,
                    positionMs      = positionMs,
                    durationMs      = durationMs,
                    timeToNextMixMs = timeToNextMixMs,
                    vinylSize       = vinylSize,
                    alignEnd        = false,
                )

                // Central mixer strip
                CenterMixerStrip(
                    modifier      = Modifier.width(mixerWidth),
                    deck1Bpm      = currentBpm,
                    deck2Bpm      = nextBpm,
                    deck1Color    = primaryColor,
                    deck2Color    = secondaryColor,
                    strategyColor = strategyColor,
                    isPlaying     = isPlaying,
                    isCrossfading = isCrossfading,
                    vinylSize     = vinylSize,
                )

                // Deck 2 (next)
                DeckPlatterColumn(
                    modifier        = Modifier.weight(1f),
                    deckLabel       = "DECK 2",
                    deckColor       = secondaryColor,
                    track           = nextTrack,
                    albumArtUri     = nextAlbumArtUri,
                    bpm             = nextBpm,
                    isSpinning      = isCrossfading,
                    isActive        = isCrossfading,
                    positionMs      = 0L,
                    durationMs      = nextTrack?.duration ?: 0L,
                    timeToNextMixMs = null,
                    vinylSize       = vinylSize,
                    alignEnd        = true,
                )
            }

            // ── SECTION 3: Pro crossfader ────────────────────────────────────
            ProDJCrossfader(
                crossfadeProgress  = crossfadeProgress,
                isCrossfading      = isCrossfading,
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
//  1. DUAL SCROLLING WAVEFORM
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Virtual-DJ–style split waveform.
 *
 * Left half  = Deck 1 scrolling waveform (playhead fixed at ~35% from left).
 * Right half = Deck 2 waveform (static / filled placeholder when no position).
 * A glowing centre divider separates the two halves.
 * Beat-grid lines are drawn at BPM intervals.
 * Four beat-counter dots animate in sync with the BPM.
 */
@Composable
private fun DualScrollingWaveform(
    waveform: List<Float>,
    positionMs: Long,
    durationMs: Long,
    deck1Color: Color,
    deck2Color: Color,
    currentBpm: Float?,
    isCrossfading: Boolean,
    crossfadeProgress: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    // ── Animated playback position (smooth 300 ms) ────────────────────────────
    val playFraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val animPlayFraction by animateFloatAsState(
        targetValue   = playFraction,
        animationSpec = tween(300),
        label         = "waveform_scroll"
    )

    // ── Beat counter (1-2-3-4 pulsing dots) ──────────────────────────────────
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

    // ── Crossfade D2 opacity ──────────────────────────────────────────────────
    val d2Alpha by animateFloatAsState(
        targetValue   = if (isCrossfading) 0.55f + crossfadeProgress * 0.45f else 0.30f,
        animationSpec = tween(400),
        label         = "d2_alpha"
    )

    Canvas(modifier = modifier) {
        val halfW      = size.width / 2f
        val divW       = 1.5.dp.toPx()
        val divX       = halfW
        val cornerPx   = 6.dp.toPx()

        // ── Background ────────────────────────────────────────────────────────
        drawRect(color = Color(0xFF080C12))

        // ── Subtle horizontal centre line (zero-crossing) ─────────────────────
        val cy = size.height / 2f
        drawLine(
            color       = Color.White.copy(alpha = 0.04f),
            start       = Offset(0f, cy),
            end         = Offset(size.width, cy),
            strokeWidth = 1.dp.toPx()
        )

        // ── DECK 1 WAVEFORM (left half, scrolling) ────────────────────────────
        drawDeckWaveform(
            waveform      = waveform,
            centerFraction = animPlayFraction,
            deckColor     = deck1Color,
            leftBound     = 0f,
            rightBound    = halfW - divW / 2f,
            height        = size.height,
            playheadAt    = 0.38f,          // playhead at 38% from left
            scrollDir     = 1,              // left-to-right flow
            currentBpm    = currentBpm,
            durationMs    = durationMs,
            totalSamples  = waveform.size
        )

        // ── DECK 2 WAVEFORM (right half, static placeholder) ─────────────────
        // We show a "loaded" wave pattern since we don't have Deck2 position
        drawDeck2Waveform(
            waveform   = waveform,          // reuse same waveform array as visual
            deckColor  = deck2Color,
            leftBound  = halfW + divW / 2f,
            rightBound = size.width,
            height     = size.height,
            alpha      = d2Alpha,
        )

        // ── Glowing centre divider ────────────────────────────────────────────
        val glowColor = lerp(deck1Color, deck2Color, 0.5f)
        drawIntoCanvas { canvas ->
            val glowPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color       = glowColor.copy(alpha = 0.50f).toArgb()
                maskFilter  = AndroidBlurMaskFilter(6.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawRect(
                divX - 3.dp.toPx(), 0f,
                divX + 3.dp.toPx(), size.height,
                glowPaint
            )
        }
        drawLine(
            color       = Color.White.copy(alpha = 0.90f),
            start       = Offset(divX, 0f),
            end         = Offset(divX, size.height),
            strokeWidth = divW
        )

        // ── Beat-counter dots (top strip) ─────────────────────────────────────
        val dotR   = 3.dp.toPx()
        val dotY   = dotR + 2.dp.toPx()
        val dotSpacing = 8.dp.toPx()
        val groupW = dotSpacing * 3f + dotR * 2f
        // Deck1 dots (left panel centre)
        val d1cx = halfW / 2f
        for (i in 0 until 4) {
            val dx    = d1cx - groupW / 2f + i * dotSpacing
            val isOn  = isPlaying && i == beatIdx
            val alpha = if (isOn) 1f else 0.20f
            if (isOn) {
                drawIntoCanvas { canvas ->
                    val p = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = deck1Color.copy(alpha = 0.8f).toArgb()
                        maskFilter = AndroidBlurMaskFilter(4.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawCircle(dx, dotY, dotR * 1.4f, p)
                }
            }
            drawCircle(color = deck1Color.copy(alpha = alpha), radius = dotR, center = Offset(dx, dotY))
        }
        // Deck2 dots (right panel centre)
        val d2cx = halfW + halfW / 2f
        for (i in 0 until 4) {
            val dx   = d2cx - groupW / 2f + i * dotSpacing
            val isOn = isCrossfading && i == beatIdx
            val alpha = if (isOn) 1f else 0.20f
            if (isOn) {
                drawIntoCanvas { canvas ->
                    val p = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = deck2Color.copy(alpha = 0.8f).toArgb()
                        maskFilter = AndroidBlurMaskFilter(4.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawCircle(dx, dotY, dotR * 1.4f, p)
                }
            }
            drawCircle(color = deck2Color.copy(alpha = alpha), radius = dotR, center = Offset(dx, dotY))
        }

        // ── Deck labels at bottom corners ─────────────────────────────────────
        // (text labels are drawn via Compose Text in parent, just show color accent bars)
        val accentH = 3.dp.toPx()
        val accentW = 32.dp.toPx()
        drawRoundRect(
            color        = deck1Color.copy(alpha = 0.85f),
            topLeft      = Offset(8.dp.toPx(), size.height - accentH - 4.dp.toPx()),
            size         = Size(accentW, accentH),
            cornerRadius = CornerRadius(accentH / 2f)
        )
        drawRoundRect(
            color        = deck2Color.copy(alpha = if (isCrossfading) 0.85f else 0.45f),
            topLeft      = Offset(size.width - accentW - 8.dp.toPx(), size.height - accentH - 4.dp.toPx()),
            size         = Size(accentW, accentH),
            cornerRadius = CornerRadius(accentH / 2f)
        )
    }
}

/** Draws a scrolling waveform for the given deck half. */
private fun DrawScope.drawDeckWaveform(
    waveform: List<Float>,
    centerFraction: Float,
    deckColor: Color,
    leftBound: Float,
    rightBound: Float,
    height: Float,
    playheadAt: Float,        // 0..1 within this half
    scrollDir: Int,           // +1 = left→right, -1 = right→left
    currentBpm: Float?,
    durationMs: Long,
    totalSamples: Int,
) {
    val halfW    = rightBound - leftBound
    val barsVis  = 52
    val barW     = halfW / barsVis
    val sw       = (barW * 0.60f).coerceAtLeast(1.5f)
    val playheadBar = (barsVis * playheadAt).toInt()
    val centerSample = if (totalSamples > 0)
        (centerFraction * totalSamples).toInt().coerceIn(0, totalSamples - 1)
    else 0
    val firstSample  = centerSample - playheadBar
    val cy = height / 2f

    // Beat grid interval in samples
    val beatGridSamples: Float? = if (currentBpm != null && durationMs > 0 && totalSamples > 0) {
        (60_000f / currentBpm) / durationMs * totalSamples
    } else null

    for (i in 0 until barsVis) {
        val sampleIdx = (firstSample + i * scrollDir).coerceIn(0, (totalSamples - 1).coerceAtLeast(0))
        val amp = if (totalSamples > 0) waveform.getOrElse(sampleIdx) { 0.08f } else 0.08f
        val x   = leftBound + i * barW + barW / 2f
        val h   = (amp * height * 1.55f).coerceIn(3f, height * 0.88f)
        val isPast = i < playheadBar

        // Colour: bright for played, dim for upcoming
        val barAlpha = if (isPast) 0.95f else 0.22f
        drawLine(
            color       = deckColor.copy(alpha = barAlpha),
            start       = Offset(x, cy - h / 2f),
            end         = Offset(x, cy + h / 2f),
            strokeWidth = sw,
            cap         = StrokeCap.Round
        )

        // Subtle beat-grid markers (faint white lines every beat)
        if (beatGridSamples != null && beatGridSamples > 0f) {
            val fromFirst = (sampleIdx - (if (totalSamples > 0) (centerFraction * totalSamples).toInt() else 0)).toFloat()
            if (fromFirst % beatGridSamples < barW / halfW * totalSamples || i == 0) {
                // Simplified: mark every N bars where N ≈ beatGridSamples / (totalSamples / barsVis)
            }
        }
    }

    // Draw beat grid lines separately for clarity
    if (beatGridSamples != null && beatGridSamples > 0f && totalSamples > 0) {
        val samplesPerBar = totalSamples.toFloat() / barsVis
        var sample = (firstSample / beatGridSamples).toInt() * beatGridSamples
        while (sample < firstSample + barsVis * samplesPerBar) {
            val barOffset = (sample - firstSample) / samplesPerBar
            if (barOffset >= 0 && barOffset < barsVis) {
                val x = leftBound + barOffset * barW
                drawLine(
                    color       = deckColor.copy(alpha = 0.35f),
                    start       = Offset(x, 0f),
                    end         = Offset(x, height * 0.18f),
                    strokeWidth = 1.dp.toPx(),
                    cap         = StrokeCap.Round
                )
                drawLine(
                    color       = deckColor.copy(alpha = 0.35f),
                    start       = Offset(x, height * 0.82f),
                    end         = Offset(x, height),
                    strokeWidth = 1.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }
            sample += beatGridSamples
        }
    }

    // Playhead line
    val phX = leftBound + playheadBar * barW
    drawIntoCanvas { canvas ->
        val p = android.graphics.Paint().apply {
            isAntiAlias = true
            color = Color.White.copy(alpha = 0.70f).toArgb()
            maskFilter = AndroidBlurMaskFilter(3.dp.toPx(), AndroidBlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawLine(phX, 0f, phX, height, p)
    }
    drawLine(
        color       = Color.White.copy(alpha = 0.90f),
        start       = Offset(phX, 0f),
        end         = Offset(phX, height),
        strokeWidth = 1.5.dp.toPx(),
        cap         = StrokeCap.Round
    )

    // Playhead triangle marker at top
    val tri = 4.dp.toPx()
    val path = Path().apply {
        moveTo(phX, 0f)
        lineTo(phX - tri, -tri * 0.1f)
        lineTo(phX + tri, -tri * 0.1f)
        close()
    }
    drawPath(path, color = deckColor)
}

/** Draws a static Deck-2 waveform preview (no position offset). */
private fun DrawScope.drawDeck2Waveform(
    waveform: List<Float>,
    deckColor: Color,
    leftBound: Float,
    rightBound: Float,
    height: Float,
    alpha: Float,
) {
    val halfW    = rightBound - leftBound
    val barsVis  = 52
    val barW     = halfW / barsVis
    val sw       = (barW * 0.60f).coerceAtLeast(1.5f)
    val cy       = height / 2f

    for (i in 0 until barsVis) {
        val sampleIdx = if (waveform.isNotEmpty()) {
            (i.toFloat() / barsVis * waveform.size).toInt().coerceIn(0, waveform.size - 1)
        } else -1
        val amp = if (sampleIdx >= 0) waveform[sampleIdx] else
            (0.15f + 0.35f * kotlin.math.sin(i * 0.35f).toFloat().let { if (it < 0f) -it else it })
        val x = leftBound + i * barW + barW / 2f
        val h = (amp * height * 1.55f).coerceIn(3f, height * 0.88f)
        drawLine(
            color       = deckColor.copy(alpha = alpha * 0.55f),
            start       = Offset(x, cy - h / 2f),
            end         = Offset(x, cy + h / 2f),
            strokeWidth = sw,
            cap         = StrokeCap.Round
        )
    }

    // "CUE" position marker at start (leftBound)
    drawLine(
        color       = deckColor.copy(alpha = alpha),
        start       = Offset(leftBound + 4.dp.toPx(), 0f),
        end         = Offset(leftBound + 4.dp.toPx(), height),
        strokeWidth = 1.5.dp.toPx(),
        cap         = StrokeCap.Round
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  2. DECK PLATTER COLUMN
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
                    // Pulsing live dot
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
                text      = track.title,
                style     = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color     = MaterialTheme.colorScheme.onSurface,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
            Text(
                text     = track.artist ?: "Unknown",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text     = "— EMPTY —",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Time display
        if (durationMs > 0L) {
            val timeText = if (isActive && positionMs > 0L)
                "-${formatDeckMs(durationMs - positionMs)}"
            else
                formatDeckMs(durationMs)
            Text(
                text      = timeText,
                style     = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color     = if (isActive) deckColor.copy(alpha = 0.80f)
                else deckColor.copy(alpha = 0.30f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  3. CENTER MIXER STRIP
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The central mixer strip between the two decks.
 *
 * Shows:
 *  • Animated VU meter bars for each deck (tall = loud, short = quiet or paused)
 *  • BPM SYNC indicator (green / orange / red based on BPM difference)
 *  • ±BPM difference text
 *
 * The VU meters use a random-walk animation when playing for a realistic feel.
 */
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
    // ── BPM sync state ────────────────────────────────────────────────────────
    val bpmDiff = if (deck1Bpm != null && deck2Bpm != null) deck2Bpm - deck1Bpm else null
    val syncState = when {
        bpmDiff == null          -> SyncState.UNKNOWN
        kotlin.math.abs(bpmDiff) < 2f  -> SyncState.SYNCED
        kotlin.math.abs(bpmDiff) < 6f  -> SyncState.CLOSE
        else                           -> SyncState.OFF
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

    // ── VU meter animation ────────────────────────────────────────────────────
    // Simulated VU bars that bounce realistically when playing
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
        Spacer(Modifier.height(20.dp)) // align with deck label height

        // ── VU meters + sync block ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(vinylSize + 28.dp)  // match vinyl height roughly
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
                // Sync label
                Text(
                    text          = syncLabel,
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color         = syncColor
                )

                // Sync dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(syncColor.copy(alpha = if (syncState == SyncState.SYNCED) 1f else 0.50f))
                )

                // VU meter bars (side by side, vertical)
                Row(
                    modifier              = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment     = Alignment.Bottom
                ) {
                    VuMeterBar(
                        modifier = Modifier.weight(1f),
                        level    = d1Level,
                        color    = deck1Color
                    )
                    VuMeterBar(
                        modifier = Modifier.weight(1f),
                        level    = d2Level,
                        color    = deck2Color
                    )
                }

                // BPM diff
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

/** A single animated vertical VU meter bar. */
@Composable
private fun VuMeterBar(modifier: Modifier, level: Float, color: Color) {
    val animLevel by animateFloatAsState(
        targetValue   = level,
        animationSpec = tween(80),
        label         = "vu_bar"
    )
    Canvas(modifier = modifier.fillMaxHeight()) {
        val w     = size.width
        val h     = size.height
        val segH  = h / 12f
        val segGap = 1.5.dp.toPx()
        val segPad = 1.dp.toPx()

        for (i in 11 downTo 0) {
            val fraction  = (11 - i).toFloat() / 11f  // 0 = bottom, 1 = top
            val isLit     = fraction <= animLevel
            val segColor  = when {
                fraction > 0.80f -> Color(0xFFFF5252)   // red (peak)
                fraction > 0.60f -> Color(0xFFFFAB40)   // orange
                else             -> color               // deck colour
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
//  4. PRO DJ CROSSFADER
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pro-grade crossfader modelled after a real DJ mixer's crossfader.
 *
 * Features:
 *  • Gradient track from D1→D2 colour
 *  • Filled "played" portion that lerps between deck colours
 *  • Illuminated thumb with three grip lines
 *  • Neon glow on the thumb when crossfading
 *  • Mix strategy badge with coloured dot
 *  • D1/D2 volume-cut indicators (VCA style visual)
 */
@Composable
private fun ProDJCrossfader(
    crossfadeProgress: Float,
    isCrossfading: Boolean,
    deck1Color: Color,
    deck2Color: Color,
    strategyColor: Color,
    currentMixStrategy: MixStrategy,
    modifier: Modifier = Modifier,
) {
    val targetPos = if (isCrossfading) crossfadeProgress else 0.5f
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
        // Strategy badge
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment     = Alignment.CenterVertically
        ) {
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

        // D1 / CROSSFADER label / D2
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // D1 VCA indicator
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
            // D2 VCA indicator
            VcaIndicator(
                label     = "D2",
                color     = deck2Color,
                isLouder  = animPos >= 0.5f,
                isCutting = animPos < 0.15f
            )
        }

        // Fader track + thumb
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
        ) {
            val trackH   = 6.dp.toPx()
            val thumbW   = 22.dp.toPx()
            val thumbH   = size.height
            val trackY   = (size.height - trackH) / 2f
            val cr       = CornerRadius(trackH / 2f)
            val tcr      = CornerRadius(5.dp.toPx())
            val travelW  = size.width - thumbW
            val thumbX   = (animPos * travelW).coerceIn(0f, travelW)
            val thumbCx  = thumbX + thumbW / 2f

            // Track background
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(deck1Color.copy(0.20f), deck2Color.copy(0.20f))
                ),
                topLeft      = Offset(0f, trackY),
                size         = Size(size.width, trackH),
                cornerRadius = cr
            )
            // Filled portion
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

            // Thumb glow
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
            // Specular highlight on thumb (top edge)
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
            val gripY1 = thumbH * 0.25f; val gripY2 = thumbH * 0.75f
            val gripSW = 1.5.dp.toPx()
            val cx     = thumbX + thumbW / 2f
            for (xOff in listOf(-3.5.dp.toPx(), 0f, 3.5.dp.toPx())) {
                drawLine(
                    color       = Color.White.copy(0.55f),
                    start       = Offset(cx + xOff, gripY1),
                    end         = Offset(cx + xOff, gripY2),
                    strokeWidth = gripSW,
                    cap         = StrokeCap.Round
                )
            }
        }
    }
}

/** A small VCA-style volume indicator (like the channel-cut indicators on a real mixer). */
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
        // Three mini level bars
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