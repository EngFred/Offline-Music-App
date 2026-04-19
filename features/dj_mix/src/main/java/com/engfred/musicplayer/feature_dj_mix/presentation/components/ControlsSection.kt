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
import androidx.compose.material.icons.rounded.Shuffle
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
    crossfadeDurationSec: Int,
    onToggleRealMixMode: (Boolean) -> Unit,
    onToggleAutoSampler: (Boolean) -> Unit,
    onSampleVolumeChanged: (Float) -> Unit,
    onCuePointOffsetChanged: (Int) -> Unit,
    onCrossfadeDurationChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ── Crossfade Duration — always visible, affects both modes ───────────
        CrossfadeDurationSlider(
            durationSec = crossfadeDurationSec,
            onValueChange = onCrossfadeDurationChanged
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        )

        // ── Auto-Mix Mode toggle ──────────────────────────────────────────────
        PremiumToggleRow(
            title    = "Auto-Mix Mode",
            subtitle = "Starts the next track before this one ends",
            isChecked       = isRealMixMode,
            onCheckedChange = onToggleRealMixMode
        )

        // ── Real-Mix-only controls — animate in/out ───────────────────────────
        AnimatedVisibility(
            visible = isRealMixMode,
            enter   = fadeIn(tween(220)) + expandVertically(tween(260)),
            exit    = fadeOut(tween(160)) + shrinkVertically(tween(200))
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
                    title    = "Mix Sound Effects",
                    subtitle = if (autoSamplerEnabled)
                        "App drops air horns, sweeps & hits at the perfect moment"
                    else
                        "Transitions play silently — no effects",
                    isChecked       = autoSamplerEnabled,
                    onCheckedChange = onToggleAutoSampler
                )

                if (autoSamplerEnabled) {
                    SliderWithLabel(
                        label        = "Effects Volume",
                        valueLabel   = "${(sampleVolume * 100).roundToInt()}%",
                        value        = sampleVolume,
                        valueRange   = 0f..1f,
                        steps        = 19,
                        description  = "How loud the transition effects are relative to the music",
                        onValueChange = onSampleVolumeChanged
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Crossfade Duration Slider
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Slider controlling the base crossfade duration (3–15 s).
 *
 * The engine multiplies this by a strategy factor:
 *   HARMONIC × 0.80 → e.g. 10 s base = 8 s effective
 *   WIDE_TRANSITION × 1.60 → e.g. 9 s base = 14 s effective (capped)
 *
 * Rendered always (not gated on isRealMixMode) because continuous-play
 * crossfade at track end also uses this duration.
 */
@Composable
private fun CrossfadeDurationSlider(
    durationSec: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
                    modifier           = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text          = "CROSSFADE DURATION",
                    style         = MaterialTheme.typography.labelSmall,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color         = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
                )
            }
            Text(
                text       = "${durationSec}s",
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color      = MaterialTheme.colorScheme.primary
            )
        }

        Slider(
            // Slider works on Float; we snap to Int on release via steps.
            // steps = (max - min - 1) = 15 - 3 - 1 = 11 discrete stops.
            value         = durationSec.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange    = 3f..15f,
            steps         = 11,   // 13 stops total → 12 intervals → steps = 11
            colors        = SliderDefaults.colors(
                thumbColor         = MaterialTheme.colorScheme.primary,
                activeTrackColor   = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
        )

        Text(
            text     = "How long the outgoing and incoming tracks overlap during a mix",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cue Point Offset Row  (unchanged from before, kept here for completeness)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CuePointOffsetRow(
    selectedSec: Int,
    onSelected:  (Int) -> Unit
) {
    val primary   = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val surface   = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val chipShape = RoundedCornerShape(8.dp)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.padding(bottom = 4.dp)
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

        FlowRow(
            modifier              = Modifier.fillMaxWidth(),
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
                        .background(if (isSelected) primary else surface)
                        .border(
                            width = if (isSelected) 0.dp else 1.dp,
                            color = primary.copy(alpha = if (isSelected) 0f else 0.20f),
                            shape = chipShape
                        )
                        .clickable { onSelected(sec) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text          = label,
                        style         = MaterialTheme.typography.labelMedium,
                        fontWeight    = if (isSelected) FontWeight.Black else FontWeight.Medium,
                        color         = if (isSelected) onPrimary else onSurface,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared private composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PremiumToggleRow(
    title:          String,
    subtitle:       String,
    isChecked:      Boolean,
    onCheckedChange:(Boolean) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
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
            checked         = isChecked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
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
    label:        String,
    valueLabel:   String,
    value:        Float,
    valueRange:   ClosedFloatingPointRange<Float>,
    steps:        Int,
    description:  String? = null,
    onValueChange:(Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
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
            value         = value,
            onValueChange = onValueChange,
            valueRange    = valueRange,
            steps         = steps,
            colors        = SliderDefaults.colors(
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