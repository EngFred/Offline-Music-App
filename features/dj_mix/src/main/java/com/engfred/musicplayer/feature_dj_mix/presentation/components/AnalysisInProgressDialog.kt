package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═════════════════════════════════════════════════════════════════════════════
// ANALYSIS IN PROGRESS DIALOG
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Shown when the user taps START MIX while BPM analysis is still running.
 *
 * Presents two options:
 *  - "Auto-Start When Ready" — arms a deferred start; mix fires automatically
 *    the moment all tracks in the playlist have been analysed.
 *  - "Start Now" — begins the mix immediately; unanalysed tracks will be
 *    ordered by playlist position rather than BPM compatibility.
 *
 * Dismissing via the back button or tapping outside closes the dialog without
 * scheduling any action — the user retains full control.
 *
 * @param analysedCount Number of tracks whose BPM has been determined so far.
 * @param totalCount    Total number of tracks in the playlist.
 * @param progress      Analysis progress in [0.0, 1.0].
 * @param onDismiss     Called when the user dismisses without making a choice.
 * @param onWait        Called when the user chooses "Auto-Start When Ready".
 * @param onStartNow    Called when the user chooses "Start Now".
 */
@Composable
fun AnalysisInProgressDialog(
    analysedCount: Int,
    totalCount:    Int,
    progress:      Float,
    onDismiss:     () -> Unit,
    onWait:        () -> Unit,
    onStartNow:    () -> Unit,
) {
    val remaining = totalCount - analysedCount

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector        = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text       = "Still Scanning Your Tracks",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier            = Modifier.fillMaxWidth()
            ) {
                // ── Progress indicator + count ────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color           = MaterialTheme.colorScheme.primary,
                        trackColor      = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap       = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text  = "$analysedCount of $totalCount tracks scanned",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text  = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // ── Explanation ───────────────────────────────────────────────
                Text(
                    text = buildString {
                        append("BPM scanning is still running in the background. ")
                        if (remaining > 0) {
                            append("$remaining track${if (remaining == 1) "" else "s"} ")
                            append("still need to be analysed.\n\n")
                        }
                        append("Starting now means those tracks won't be sorted by ")
                        append("BPM compatibility — they'll follow the original ")
                        append("playlist order instead.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── "Wait" option highlight card ──────────────────────────────
                Surface(
                    shape  = RoundedCornerShape(12.dp),
                    color  = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "✦  Choose \"Auto-Start When Ready\" and the mix will kick off automatically — no need to come back.",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            // Primary action: wait for analysis, then auto-start
            Button(
                onClick = onWait,
                shape   = RoundedCornerShape(percent = 50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text       = "Auto-Start When Ready",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        },
        dismissButton = {
            // Secondary action: start immediately with partial data
            OutlinedButton(
                onClick  = onStartNow,
                shape    = RoundedCornerShape(percent = 50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = "Start Now Anyway",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape          = RoundedCornerShape(24.dp)
    )
}