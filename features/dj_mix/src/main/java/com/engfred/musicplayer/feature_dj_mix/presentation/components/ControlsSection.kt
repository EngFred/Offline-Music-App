package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.feature_dj_mix.domain.model.CUE_POINT_OPTIONS_SEC
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsSection(
    isRealMixMode: Boolean,
    autoSamplerEnabled: Boolean,
    sampleVolume: Float,
    cuePointOffsetSec: Int,
    onToggleRealMixMode: (Boolean) -> Unit,
    onToggleAutoSampler: (Boolean) -> Unit,
    onSampleVolumeChanged: (Float) -> Unit,
    onCuePointOffsetChanged: (Int) -> Unit,
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

        // ── Real-Mix-only controls — animate in/out with the toggle ───────────
        AnimatedVisibility(
            visible = isRealMixMode,
            enter = fadeIn(tween(220)) + expandVertically(tween(260)),
            exit  = fadeOut(tween(160)) + shrinkVertically(tween(200))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                )

                // ── Cue Point Offset ──────────────────────────────────────────
                CuePointOffsetRow(
                    selectedSec = cuePointOffsetSec,
                    onSelected  = onCuePointOffsetChanged
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                )

                // ── Sampler ───────────────────────────────────────────────────
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
                        steps = 19,
                        description = "How loud the transition effects are relative to the music",
                        onValueChange = onSampleVolumeChanged
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cue Point Offset Row
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Displays a labelled chip-strip for selecting the cue-point offset.
 * Each chip shows the option in seconds (or "OFF" for 0 s).
 * Only rendered when Auto-Mix Mode is ON (enforced by caller via AnimatedVisibility).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CuePointOffsetRow(
    selectedSec: Int,
    onSelected: (Int) -> Unit
) {
    val primary        = MaterialTheme.colorScheme.primary
    val onPrimary      = MaterialTheme.colorScheme.onPrimary
    val surface        = MaterialTheme.colorScheme.surfaceVariant
    val onSurface      = MaterialTheme.colorScheme.onSurfaceVariant
    val chipShape      = RoundedCornerShape(8.dp)

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                imageVector        = Icons.Rounded.Timer,
                contentDescription = null,
                tint               = primary.copy(alpha = 0.80f),
                modifier           = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text          = "CUE POINT",
                style         = MaterialTheme.typography.labelSmall,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 1.sp,
                color         = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
            )
            Spacer(Modifier.weight(1f))
            // Live readout of the selected value
            Text(
                text       = if (selectedSec == 0) "OFF" else "${selectedSec}s",
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color      = primary
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text     = "Minimum position of the incoming track's first beat during a mix",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // ── Chip strip ────────────────────────────────────────────────────────
        // FlowRow so chips wrap gracefully on narrow screens
        FlowRow(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp)
        ) {
            CUE_POINT_OPTIONS_SEC.forEach { sec ->
                val isSelected = sec == selectedSec
                val label      = if (sec == 0) "OFF" else "${sec}s"

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(chipShape)
                        .background(
                            color = if (isSelected) primary else surface
                        )
                        .border(
                            width = if (isSelected) 0.dp else 1.dp,
                            color = primary.copy(alpha = if (isSelected) 0f else 0.20f),
                            shape = chipShape
                        )
                        .clickable { onSelected(sec) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text       = label,
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                        color      = if (isSelected) onPrimary else onSurface,
                        letterSpacing = 0.5.sp
                    )
                }
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
        Column(modifier = Modifier
            .weight(1f)
            .padding(end = 16.dp)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onSurface
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
                thumbColor         = MaterialTheme.colorScheme.primary,
                activeTrackColor   = MaterialTheme.colorScheme.primary,
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