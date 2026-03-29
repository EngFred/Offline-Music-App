package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ControlsSection(
    crossfadeDurationSec: Int,
    bpmTolerance: Float,
    isRealMixMode: Boolean,
    maxTrackDurationSec: Int,
    useManualMaxDuration: Boolean,
    loopQueue: Boolean,
    autoSamplerEnabled: Boolean,
    sampleVolume: Float,
    onCrossfadeDurationChanged: (Int) -> Unit,
    onBpmToleranceChanged: (Float) -> Unit,
    onToggleRealMixMode: (Boolean) -> Unit,
    onToggleManualMaxDuration: (Boolean) -> Unit,
    onMaxDurationChanged: (Int) -> Unit,
    onToggleLoopQueue: (Boolean) -> Unit,
    onToggleAutoSampler: (Boolean) -> Unit,
    onSampleVolumeChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ── Auto-Mix Mode ─────────────────────────────────────────────────────
        PremiumToggleRow(
            title = "Auto-Mix Mode",
            subtitle = "Starts the next track before this one ends",
            isChecked = isRealMixMode,
            onCheckedChange = onToggleRealMixMode
        )

        if (isRealMixMode) {
            PremiumToggleRow(
                title = "Manual Mix Point",
                subtitle = if (useManualMaxDuration)
                    "You choose when to trigger the mix"
                else
                    "Mixes at the halfway point (adjusted for song start offset)",
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

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

        // ── Loop ──────────────────────────────────────────────────────────────
//        PremiumToggleRow(
//            title = "Loop Session",
//            subtitle = "When the last track ends, loop back to the start",
//            isChecked = loopQueue,
//            onCheckedChange = onToggleLoopQueue
//        )
//
//        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

//        // ── Blend / BPM ───────────────────────────────────────────────────────
//        SliderWithLabel(
//            label = "Blend Length",
//            valueLabel = "$crossfadeDurationSec SEC",
//            value = crossfadeDurationSec.toFloat(),
//            valueRange = 2f..12f,
//            steps = 9,
//            description = "How long the two tracks overlap during a mix",
//            onValueChange = { onCrossfadeDurationChanged(it.toInt()) }
//        )
//
//        SliderWithLabel(
//            label = "Mix Tightness",
//            valueLabel = "±${bpmTolerance.toInt()} BPM",
//            value = bpmTolerance,
//            valueRange = 5f..20f,
//            steps = 14,
//            description = when {
//                bpmTolerance <= 8f  -> "Strict — only very similar BPMs mix together"
//                bpmTolerance <= 14f -> "Balanced — most tracks will mix smoothly"
//                else                -> "Flexible — any two tracks can mix, may sound rough"
//            },
//            onValueChange = onBpmToleranceChanged
//        )
//
//        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

        // ── Sampler ───────────────────────────────────────────────────────────
        PremiumToggleRow(
            title = "Mix Sound Effects",
            subtitle = if (autoSamplerEnabled)
                "App drops air horns, sweeps & hits at the perfect moment"
            else
                "Transitions play silently — no effects",
            isChecked = autoSamplerEnabled,
            onCheckedChange = onToggleAutoSampler
        )

        if (autoSamplerEnabled) {
            SliderWithLabel(
                label = "Effects Volume",
                valueLabel = "${(sampleVolume * 100).roundToInt()}%",
                value = sampleVolume,
                valueRange = 0f..1f,
                steps = 19,           // 5% increments
                description = "How loud the transition effects are relative to the music",
                onValueChange = onSampleVolumeChanged
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
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text      = title,
                style     = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color     = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor   = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
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
                text          = label.uppercase(),
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.sp,
                color         = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text       = valueLabel,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color      = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor        = MaterialTheme.colorScheme.primary,
                activeTrackColor  = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
        )
        if (description != null) {
            Text(
                text     = description,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}