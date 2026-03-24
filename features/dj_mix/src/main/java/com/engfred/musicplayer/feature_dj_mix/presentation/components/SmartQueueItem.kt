package com.engfred.musicplayer.feature_dj_mix.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.AudioFile

@Composable
fun SmartQueueItem(
    position: Int,
    song: AudioFile,
    bpm: Float?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isCurrent)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else
        Color.White.copy(alpha = 0.02f)

    val borderColor = if (isCurrent)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    else
        Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Position number
        Text(
            text = String.format("%02d", position),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.width(24.dp)
        )

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Black else FontWeight.SemiBold,
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist ?: "Unknown Artist",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Pill-shaped BPM badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = 0.1f)
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = bpm?.toInt()?.toString() ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.6f)
            )
        }
    }
}