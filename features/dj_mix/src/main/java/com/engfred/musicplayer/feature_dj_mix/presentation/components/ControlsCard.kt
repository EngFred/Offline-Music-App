package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
fun ControlsCard(
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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // Central Play/Pause button (Hardware feel)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // -- Modes Group --
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PremiumToggleRow(
                    title = "Smart Mix",
                    subtitle = "Crossfade before tracks finish",
                    isChecked = isRealMixMode,
                    onCheckedChange = onToggleRealMixMode
                )

                if (isRealMixMode) {
                    PremiumToggleRow(
                        title = "Custom Max Time",
                        subtitle = if (useManualMaxDuration) "Set a precise mix trigger" else "Mix at 50% duration",
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
                    subtitle = "Restart queue when finished",
                    isChecked = loopQueue,
                    onCheckedChange = onToggleLoopQueue
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // -- Tuning Sliders --
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SliderWithLabel(
                    label = "Crossfade Duration",
                    valueLabel = "${crossfadeDurationSec} SEC",
                    value = crossfadeDurationSec.toFloat(),
                    valueRange = 2f..12f,
                    steps = 9,
                    onValueChange = { onCrossfadeDurationChanged(it.toInt()) }
                )

                SliderWithLabel(
                    label = "BPM Tolerance",
                    valueLabel = "±${bpmTolerance.toInt()}",
                    value = bpmTolerance,
                    valueRange = 5f..20f,
                    steps = 14,
                    onValueChange = onBpmToleranceChanged
                )
            }
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
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
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
                color = Color.White.copy(alpha = 0.6f)
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
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}