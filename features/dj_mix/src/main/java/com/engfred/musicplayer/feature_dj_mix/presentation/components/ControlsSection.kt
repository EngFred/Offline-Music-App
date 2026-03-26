package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ControlsSection(
    isPlaying: Boolean,
    crossfadeDurationSec: Int,
    bpmTolerance: Float,
    isRealMixMode: Boolean,
    maxTrackDurationSec: Int,
    useManualMaxDuration: Boolean,
    loopQueue: Boolean,
    onPlayPause: () -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit,
    onBpmToleranceChanged: (Float) -> Unit,
    onToggleRealMixMode: (Boolean) -> Unit,
    onToggleManualMaxDuration: (Boolean) -> Unit,
    onMaxDurationChanged: (Int) -> Unit,
    onToggleLoopQueue: (Boolean) -> Unit,
    canSkipBack: Boolean,
    onSkipBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Removed all background layers and borders. Controls float purely on the canvas.
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Massive pro Play/Pause button
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Skip back
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSkipBack) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .clickable(enabled = canSkipBack, onClick = onSkipBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Skip back",
                    tint = if (canSkipBack) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Play/Pause (keep existing 80dp box as-is)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }

            // Balancing spacer (same size as skip-back)
            Spacer(modifier = Modifier.size(52.dp))
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Clean, flat toggle list with minimal dividers
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PremiumToggleRow(
                title = "Auto-Mix Mode",
                subtitle = "Starts the next track before this one ends",
                isChecked = isRealMixMode,
                onCheckedChange = onToggleRealMixMode
            )

            if (isRealMixMode) {
                PremiumToggleRow(
                    title = "Manual Mix Point",
                    subtitle = if (useManualMaxDuration) "You choose when to trigger the mix" else "Mixes at the halfway point",
                    isChecked = useManualMaxDuration,
                    onCheckedChange = onToggleManualMaxDuration
                )

                if (useManualMaxDuration) {
                    SliderWithLabel(
                        label = "Playtime Limit",
                        valueLabel = "${maxTrackDurationSec / 60}m ${maxTrackDurationSec % 60}s",
                        value = maxTrackDurationSec.toFloat(),
                        valueRange = 60f..300f,
                        steps = 24,
                        onValueChange = { onMaxDurationChanged(it.toInt()) }
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            PremiumToggleRow(
                title = "Loop Session",
                subtitle = "When the last track ends, loop back to the start",
                isChecked = loopQueue,
                onCheckedChange = onToggleLoopQueue
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            SliderWithLabel(
                label = "Blend Length",
                valueLabel = "${crossfadeDurationSec} SEC",
                value = crossfadeDurationSec.toFloat(),
                valueRange = 2f..12f,
                steps = 9,
                description = "How long the two tracks overlap during a mix",
                onValueChange = { onCrossfadeDurationChanged(it.toInt()) }
            )

            SliderWithLabel(
                label = "Mix Tightness",
                valueLabel = "±${bpmTolerance.toInt()} BPM",
                value = bpmTolerance,
                valueRange = 5f..20f,
                steps = 14,
                description = when {
                    bpmTolerance <= 8f  -> "Strict — only very similar BPMs mix together"
                    bpmTolerance <= 14f -> "Balanced — most tracks will mix smoothly"
                    else                -> "Flexible — any two tracks can mix, may sound rough"
                },
                onValueChange = onBpmToleranceChanged
            )
        }
    }
}

@Composable
private fun PremiumToggleRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

@Composable
private fun SliderWithLabel(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    description: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
        // New optional description rendered below the slider
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}