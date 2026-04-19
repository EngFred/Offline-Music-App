package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Mix Now" action row.
 *
 * ── States ────────────────────────────────────────────────────────────────────
 *  • Idle    → outlined button, fully tappable.
 *  • Mixing  → progress indicator + "Mixing…" label, NOT tappable.
 *              The button itself is disabled; the engine's isCrossfading guard
 *              already rejects calls from triggerMixNow(), but disabling the
 *              button provides immediate visual feedback and prevents the user
 *              from wondering why nothing happened on a second tap.
 *
 * ── Why OutlinedButton ───────────────────────────────────────────────────────
 * The play/pause FAB is already at the bottom of the screen as the primary
 * action. "Mix Now" is a secondary action — OutlinedButton preserves the visual
 * hierarchy while still being obvious enough to discover.
 */
@Composable
fun MixNowRow(
    isCrossfading: Boolean,
    onMixNow:      () -> Unit,
    modifier:      Modifier = Modifier
) {
    AnimatedContent(
        targetState     = isCrossfading,
        transitionSpec  = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label           = "mix_now_state"
    ) { crossfading ->
        if (crossfading) {
            // ── Mixing indicator ──────────────────────────────────────────────
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue   = 0.55f,
                targetValue    = 1.0f,
                animationSpec  = infiniteRepeatable(
                    animation  = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_alpha"
            )
            Row(
                modifier              = modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color       = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text          = "MIXING…",
                    style         = MaterialTheme.typography.labelMedium,
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color         = MaterialTheme.colorScheme.primary,
                    modifier      = Modifier.alpha(pulse)
                )
            }
        } else {
            // ── Idle button ───────────────────────────────────────────────────
            OutlinedButton(
                onClick  = onMixNow,
                modifier = modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(percent = 50),
                border   = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    imageVector        = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                    tint               = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text          = "MIX NOW",
                    fontWeight    = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color         = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}