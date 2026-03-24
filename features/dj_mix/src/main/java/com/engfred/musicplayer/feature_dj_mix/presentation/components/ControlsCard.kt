package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ControlsCard(
    isPlaying: Boolean,
    crossfadeDurationSec: Int,
    bpmTolerance: Float,
    isRealMixMode: Boolean,
    maxTrackDurationSec: Int,
    useManualMaxDuration: Boolean,           // NEW
    loopQueue: Boolean,
    onPlayPause: () -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit,
    onBpmToleranceChanged: (Float) -> Unit,
    onToggleRealMixMode: (Boolean) -> Unit,
    onToggleManualMaxDuration: (Boolean) -> Unit,   // NEW
    onMaxDurationChanged: (Int) -> Unit,
    onToggleLoopQueue: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Play / Pause button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(64.dp)
                    .clickable(onClick = onPlayPause)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Real Mix Mode Toggle ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Real Mix Mode",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Mix tracks before they finish",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isRealMixMode,
                    onCheckedChange = onToggleRealMixMode
                )
            }

            if (isRealMixMode) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Use custom max time",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = useManualMaxDuration,
                        onCheckedChange = onToggleManualMaxDuration
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (useManualMaxDuration) {
                    SliderWithLabel(
                        label = "Max Song Playtime",
                        valueLabel = "${maxTrackDurationSec / 60}m ${maxTrackDurationSec % 60}s",
                        value = maxTrackDurationSec.toFloat(),
                        valueRange = 60f..300f,
                        steps = 24,
                        onValueChange = { onMaxDurationChanged(it.toInt()) }
                    )
                } else {
                    Text(
                        text = "Mix at halfway point of current track",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Loop Queue Toggle ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Repeat Mix",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Loop when queue finishes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = loopQueue,
                    onCheckedChange = onToggleLoopQueue
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))

            // Crossfade duration slider
            SliderWithLabel(
                label = "Crossfade Length",
                valueLabel = "${crossfadeDurationSec}s",
                value = crossfadeDurationSec.toFloat(),
                valueRange = 2f..12f,
                steps = 9,
                onValueChange = { onCrossfadeDurationChanged(it.toInt()) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // BPM tolerance slider
            SliderWithLabel(
                label = "BPM Tolerance",
                valueLabel = "±${bpmTolerance.toInt()} BPM",
                value = bpmTolerance,
                valueRange = 5f..20f,
                steps = 14,
                onValueChange = onBpmToleranceChanged
            )
        }
    }
}

@Composable
fun SliderWithLabel(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}