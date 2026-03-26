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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile

@Composable
fun SmartQueueItem(
    position: Int,
    song: AudioFile,
    bpm: Float?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    isPlayed: Boolean = false,
    analysisFailed: Boolean = false
) {
    val bgColor = if (isCurrent)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isPlayed && !isCurrent) 0.45f else 1f)
            .background(bgColor)
            .clickable(onClick = onClick)
    ) {
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .align(Alignment.CenterStart)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Position number or played check
            if (isPlayed && !isCurrent) {
                Icon(
                    imageVector        = Icons.Rounded.CheckCircle,
                    contentDescription = "Played",
                    tint               = Color.White.copy(alpha = 0.30f),
                    modifier           = Modifier.size(16.dp).width(24.dp)
                )
            } else {
                Text(
                    text       = String.format("%02d", position),
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color      = if (isCurrent) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.30f),
                    modifier   = Modifier.width(24.dp)
                )
            }

            // Title + artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = song.title,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.Black else FontWeight.SemiBold,
                    color      = when {
                        isCurrent      -> MaterialTheme.colorScheme.primary
                        analysisFailed -> Color.White.copy(alpha = 0.45f)
                        else           -> Color.White
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text     = song.artist ?: "Unknown Artist",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = Color.White.copy(alpha = 0.50f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // BPM / warning badge
            when {
                analysisFailed -> Icon(
                    imageVector        = Icons.Rounded.WarningAmber,
                    contentDescription = "BPM analysis failed",
                    tint               = Color(0xFFF57C00).copy(alpha = 0.70f),
                    modifier           = Modifier.size(18.dp)
                )
                bpm != null && bpm > 0f -> Text(
                    text       = bpm.toInt().toString(),
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Black,
                    color      = if (isCurrent) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.40f)
                )
                else -> Text(
                    text  = "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.20f)
                )
            }

            if (!isCurrent) {
                IconButton(
                    onClick  = onRemove,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Rounded.Close,
                        contentDescription = "Remove from queue",
                        tint               = Color.White.copy(alpha = 0.28f),
                        modifier           = Modifier.size(15.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(36.dp))
            }
        }
    }
}