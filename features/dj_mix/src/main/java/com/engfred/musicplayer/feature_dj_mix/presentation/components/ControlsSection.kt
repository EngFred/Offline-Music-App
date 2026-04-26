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
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewStream
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
import kotlin.math.roundToInt

@Composable
fun ControlsSection(
    isRealMixMode: Boolean,
    autoSamplerEnabled: Boolean,
    sampleVolume: Float,
    cuePointOffsetSec: Int,
//    crossfadeDurationSec: Int,
    onToggleRealMixMode: (Boolean) -> Unit,
    onToggleAutoSampler: (Boolean) -> Unit,
    onSampleVolumeChanged: (Float) -> Unit,
    onCuePointOffsetChanged: (Int) -> Unit,
//    onCrossfadeDurationChanged: (Int) -> Unit,
    isDualDeckMode: Boolean = false,
    onToggleDeckLayout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ── NEW: Dual Deck View toggle (top of settings, always visible) ───────
        DualDeckToggleRow(
            isDualDeckMode   = isDualDeckMode,
            onToggle         = onToggleDeckLayout
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        )

        // ── Auto-Mix Mode toggle ──────────────────────────────────────────────
        PremiumToggleRow(
            title           = "Auto-Mix Mode",
            subtitle        = "Starts the next track before this one ends",
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
                        label         = "Effects Volume",
                        valueLabel    = "${(sampleVolume * 100).roundToInt()}%",
                        value         = sampleVolume,
                        valueRange    = 0f..1f,
                        steps         = 19,
                        description   = "How loud the transition effects are relative to the music",
                        onValueChange = onSampleVolumeChanged
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  NEW: Dual Deck View Toggle Row
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A rich toggle row for enabling/disabling the Dual Deck (Virtual-DJ) layout.
 * Shows the relevant icon and a description of what each mode looks like.
 * Moved here from the TopAppBar so it's discoverable inside Settings.
 */
@Composable
private fun DualDeckToggleRow(
    isDualDeckMode: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isDualDeckMode)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
            )
            .border(
                width = 1.dp,
                color = if (isDualDeckMode)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Icon reflecting current mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isDualDeckMode)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
                    )
            ) {
                Icon(
                    imageVector        = if (isDualDeckMode) Icons.Rounded.ViewStream
                    else Icons.Rounded.ViewColumn,
                    contentDescription = null,
                    tint               = if (isDualDeckMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text       = "Dual Deck View",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text  = if (isDualDeckMode)
                        "Two decks + VU meters + crossfader — Virtual DJ style"
                    else
                        "Classic single-deck view with vinyl & waveform",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        Switch(
            checked         = isDualDeckMode,
            onCheckedChange = { onToggle() },
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor   = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Crossfade Duration Slider
// ─────────────────────────────────────────────────────────────────────────────

//@Composable
//private fun CrossfadeDurationSlider(
//    durationSec: Int,
//    onValueChange: (Int) -> Unit
//) {
//    Column(modifier = Modifier.fillMaxWidth()) {
//        Row(
//            modifier              = Modifier.fillMaxWidth(),
//            verticalAlignment     = Alignment.CenterVertically,
//            horizontalArrangement = Arrangement.SpaceBetween
//        ) {
//            Row(verticalAlignment = Alignment.CenterVertically) {
//                Icon(
//                    imageVector        = Icons.Rounded.Shuffle,
//                    contentDescription = null,
//                    tint               = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
//                    modifier           = Modifier.size(16.dp)
//                )
//                Spacer(Modifier.width(6.dp))
//                Text(
//                    text          = "CROSSFADE DURATION",
//                    style         = MaterialTheme.typography.labelSmall,
//                    fontWeight    = FontWeight.Bold,
//                    letterSpacing = 1.sp,
//                    color         = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
//                )
//            }
//            Text(
//                text       = "${durationSec}s",
//                style      = MaterialTheme.typography.labelMedium,
//                fontWeight = FontWeight.Black,
//                color      = MaterialTheme.colorScheme.primary
//            )
//        }
//
//        Slider(
//            value         = durationSec.toFloat(),
//            onValueChange = { onValueChange(it.roundToInt()) },
//            valueRange    = 3f..10f, // Updated to 10 seconds max
//            steps         = 6,       // Updated steps: (10 - 3) - 1 = 6, ensuring clean 1-second increments
//            colors        = SliderDefaults.colors(
//                thumbColor         = MaterialTheme.colorScheme.primary,
//                activeTrackColor   = MaterialTheme.colorScheme.primary,
//                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
//            )
//        )
//
//        Text(
//            text     = "How long the outgoing and incoming tracks overlap during a mix",
//            style    = MaterialTheme.typography.bodySmall,
//            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
//            modifier = Modifier.padding(top = 2.dp)
//        )
//    }
//}

// ─────────────────────────────────────────────────────────────────────────────
//  Cue Point Offset Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CuePointOffsetRow(
    selectedSec: Int,
    onSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector        = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
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
            }
            Text(
                text       = if (selectedSec == 0) "OFF" else "${selectedSec}s",
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color      = MaterialTheme.colorScheme.primary
            )
        }

        Slider(
            value         = selectedSec.toFloat(),
            onValueChange = { onSelected(it.roundToInt()) },
            valueRange    = 0f..15f, // Max 15 seconds
            steps         = 14,      // (15 - 0) - 1 = 14 steps for clean 1-second increments
            colors        = SliderDefaults.colors(
                thumbColor         = MaterialTheme.colorScheme.primary,
                activeTrackColor   = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
        )

        Text(
            text     = "Minimum position of the incoming track's first beat during a mix",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared private composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PremiumToggleRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
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