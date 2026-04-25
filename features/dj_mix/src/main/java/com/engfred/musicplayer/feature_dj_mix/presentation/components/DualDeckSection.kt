package com.engfred.musicplayer.feature_dj_mix.presentation.components

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import androidx.compose.material3.MaterialTheme

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
    timeToNextMixMs: Long?,
) {
    val primaryColor   = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    // ── Deck alternation: toggle which physical deck plays the current song ───
    var activeDeckIndex by remember { mutableIntStateOf(0) }
    var wasCrossfading  by remember { mutableStateOf(false) }

    LaunchedEffect(isCrossfading) {
        if (wasCrossfading && !isCrossfading) {
            activeDeckIndex = 1 - activeDeckIndex
        }
        wasCrossfading = isCrossfading
    }

    // ── Derive per-deck values from activeDeckIndex ───────────────────────────
    val deck1Track = if (activeDeckIndex == 0) currentTrack   else nextTrack
    val deck2Track = if (activeDeckIndex == 0) nextTrack       else currentTrack
    val deck1Bpm   = if (activeDeckIndex == 0) currentBpm      else nextBpm
    val deck2Bpm   = if (activeDeckIndex == 0) nextBpm         else currentBpm
    val deck1Art   = if (activeDeckIndex == 0) currentAlbumArtUri else nextAlbumArtUri
    val deck2Art   = if (activeDeckIndex == 0) nextAlbumArtUri    else currentAlbumArtUri

    val deck1Spinning = if (activeDeckIndex == 0) isPlaying    else isCrossfading
    val deck2Spinning = if (activeDeckIndex == 0) isCrossfading else isPlaying
    val deck1Active   = if (activeDeckIndex == 0) true          else isCrossfading
    val deck2Active   = if (activeDeckIndex == 0) isCrossfading else true

    val deck1Position  = if (activeDeckIndex == 0) positionMs else 0L
    val deck1Duration  = if (activeDeckIndex == 0) durationMs else (nextTrack?.duration ?: 0L)
    val deck2Position  = if (activeDeckIndex == 0) 0L else positionMs
    val deck2Duration  = if (activeDeckIndex == 0) (nextTrack?.duration ?: 0L) else durationMs
    val deck1TimeToMix = if (activeDeckIndex == 0) timeToNextMixMs else null
    val deck2TimeToMix = if (activeDeckIndex == 0) null else timeToNextMixMs

    val activeColor = if (activeDeckIndex == 0) primaryColor else secondaryColor

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        val vinylSize  = when {
            totalWidth >= 420.dp -> 120.dp
            totalWidth >= 360.dp ->  96.dp
            else                 ->  80.dp
        }
        val waveHeight = if (totalWidth >= 360.dp) 72.dp else 58.dp
        val mixerWidth = if (totalWidth >= 360.dp) 62.dp else 50.dp

        Column(
            modifier            = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 1. Scrolling waveform ─────────────────────────────────────────
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

            // ── 2. Deck platters + centre mixer ───────────────────────────────
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
                    modifier        = Modifier.width(mixerWidth),
                    deck1Bpm        = deck1Bpm,
                    deck2Bpm        = deck2Bpm,
                    deck1Color      = primaryColor,
                    deck2Color      = secondaryColor,
                    isPlaying       = isPlaying,
                    isCrossfading   = isCrossfading,
                    activeDeckIndex = activeDeckIndex,   // ← passed down for VU fix
                    vinylSize       = vinylSize,
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

            // ── 3. Read-only auto crossfader ──────────────────────────────────
            ProDJCrossfader(
                crossfadeProgress  = crossfadeProgress,
                isCrossfading      = isCrossfading,
                activeDeckIndex    = activeDeckIndex,
                deck1Color         = primaryColor,
                deck2Color         = secondaryColor,
                modifier           = Modifier.fillMaxWidth()
            )
        }
    }
}