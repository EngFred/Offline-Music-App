package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BpmAnalysisSection(
    progress: Float,
    analysedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    failedCount: Int = 0
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 600),
        label = "bpm_analysis_progress"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SYNCING AUDIO DATA...",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "$analysedCount / $totalCount",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            strokeCap = StrokeCap.Round,
            color = MaterialTheme.colorScheme.secondary,
            trackColor = Color.White.copy(alpha = 0.1f)
        )

        // ── non-alarming failure notice ─────────────────────────────────
        // Only visible once analysis is complete (progress == 1f) and there are
        // failures. Shown after the progress bar so it doesn't compete with the
        // "still working" state. Phrased to set expectations without alarming.
        if (failedCount > 0 && progress >= 1f) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector        = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint               = Color(0xFFF57C00).copy(alpha = 0.8f),
                    modifier           = Modifier.size(14.dp)
                )
                Text(
                    text = "$failedCount ${if (failedCount == 1) "track" else "tracks"} couldn't be analyzed" +
                            " — transitions may be less precise for ${if (failedCount == 1) "it" else "them"}",
                    style     = MaterialTheme.typography.labelSmall,
                    color     = Color.White.copy(alpha = 0.5f),
                    maxLines  = 2
                )
            }
        }
    }
}