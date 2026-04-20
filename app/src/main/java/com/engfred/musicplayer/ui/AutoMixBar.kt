package com.engfred.musicplayer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.compose.runtime.collectAsState

/**
 * Persistent mini-controller shown in the main bottom bar when a DJ session
 * is active. Replaces the normal [MiniPlayer].
 *
 * ── What it shows ────────────────────────────────────────────────────────────
 * • 2 dp track-progress line at the very top (primary colour)
 * • Pulsing status dot (primary when playing/mixing, muted when paused)
 * • Current track title + artist
 * • BPM chip (appears once BPM analysis is cached for the track)
 * • "MIXING" animated label during crossfade
 * • Play / Pause button
 * • Skip / Mix Now button (only in Auto-Mix mode)
 *
 * ── Interaction model ────────────────────────────────────────────────────────
 * Tapping the bar → [onClick] → navigates to MixStudioScreen.
 * Play/Pause icon → toggles engine playback directly via ViewModel.
 * Skip icon       → triggers immediate crossfade (Mix Now) via ViewModel.
 *
 * ── Design ───────────────────────────────────────────────────────────────────
 * No gradients. No scale animations. The container inherits the parent's
 * surface color; tonal separation comes only from the 2 dp progress line
 * and the 0.06 alpha top divider.
 */
@UnstableApi
@Composable
fun AutoMixBar(
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AutoMixBarViewModel = hiltViewModel()
) {
    val state by viewModel.barState.collectAsState()

    // ── Animations ────────────────────────────────────────────────────────────
    // Status dot pulses only when audio is actually moving (playing or in a fade).
    val isActive = state.isPlaying || state.isCrossfading
    val dotPulse by rememberInfiniteTransition(label = "dot_pulse").animateFloat(
        initialValue  = 0.40f,
        targetValue   = 1.00f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    val dotAlpha = if (isActive) dotPulse else 0.30f

    // Smooth track-progress bar — 300 ms easing to match position poll interval.
    val progressFraction = if (state.durationMs > 0L)
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    else 0f
    val animatedProgress by animateFloatAsState(
        targetValue   = progressFraction,
        animationSpec = tween(300),
        label         = "track_progress"
    )

    val primary   = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    // Make the entire root Column clickable so the ripple fills the whole bar
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {

        // ── Top divider (separates bar from content above) ───────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(onSurface.copy(alpha = 0.06f))
        )

        // ── 2 dp track-progress line ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(onSurface.copy(alpha = 0.08f))   // unfilled track
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .background(primary)                       // filled portion
            )
        }

        // ── Main row ─────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ── Status dot ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = dotAlpha))
            )

            Spacer(Modifier.width(12.dp))

            // ── Track info ───────────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f)
            ) {
                if (state.currentTrack != null) {
                    Text(
                        text       = state.currentTrack!!.title,
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = onSurface,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text     = state.currentTrack!!.artist ?: "Unknown Artist",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Don't let artist crush the BPM chip
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        // BPM chip — appears as soon as analysis is cached
                        state.bpm?.let { bpm ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text       = "· ${bpm.toInt()} BPM",
                                style      = MaterialTheme.typography.labelSmall,
                                color      = primary.copy(alpha = 0.80f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // "MIXING" label — only during an active crossfade
                        if (state.isCrossfading) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text          = "· MIXING",
                                style         = MaterialTheme.typography.labelSmall,
                                color         = primary,
                                fontWeight    = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                modifier      = Modifier.alpha(dotPulse)
                            )
                        }
                    }
                } else {
                    // Session active but no track yet (rare edge: engine initialising)
                    Text(
                        text       = "DJ Auto-Mix",
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = onSurface
                    )
                    Text(
                        text  = "Tap to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurface.copy(alpha = 0.55f)
                    )
                }
            }

            // ── Play / Pause ─────────────────────────────────────────────────
            IconButton(
                onClick  = viewModel::playPause,
                modifier = Modifier.size(40.dp)
            ) {
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(80))
                    },
                    label = "play_pause_icon"
                ) { playing ->
                    Icon(
                        imageVector        = if (playing) Icons.Rounded.Pause
                        else         Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint               = onSurface,
                        modifier           = Modifier.size(22.dp)
                    )
                }
            }

            // ── Mix Now (Skip) — Auto-Mix mode only ──────────────────────────
            // Hidden in continuous-play mode because there is no on-demand
            // crossfade to trigger — tracks transition naturally at their end.
            if (state.isRealMixMode) {
                val mixNowEnabled = !state.isCrossfading && state.currentTrack != null
                IconButton(
                    onClick  = viewModel::mixNow,
                    enabled  = mixNowEnabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Rounded.SkipNext,
                        contentDescription = "Mix Now",
                        tint               = if (mixNowEnabled) primary
                        else onSurface.copy(alpha = 0.25f),
                        modifier           = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}