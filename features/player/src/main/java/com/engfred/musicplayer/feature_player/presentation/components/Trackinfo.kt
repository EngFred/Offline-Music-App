package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.PlayerLayout

@Composable
fun TrackInfo(
    title: String?,
    artist: String?,
    playerLayout: PlayerLayout,
    modifier: Modifier = Modifier
) {
    val cleanTitle = remember(title, artist) {
        val rawTitle = title?.replace('_', ' ')?.trim() ?: "Unknown Title"
        val rawArtist = artist?.replace('_', ' ')?.trim()
        if ((rawArtist.isNullOrEmpty() || rawArtist == "Unknown Artist") && rawTitle.contains(" - ")) {
            val parts = rawTitle.split(" - ", limit = 2)
            parts.getOrNull(1) ?: rawTitle
        } else {
            rawTitle
        }
    }

    val cleanArtist = remember(title, artist) {
        val rawTitle = title?.replace('_', ' ')?.trim() ?: ""
        val rawArtist = artist?.replace('_', ' ')?.trim()
        if ((rawArtist.isNullOrEmpty() || rawArtist == "Unknown Artist") && rawTitle.contains(" - ")) {
            val parts = rawTitle.split(" - ", limit = 2)
            parts.getOrNull(0) ?: "Unknown Artist"
        } else {
            rawArtist ?: "Unknown Artist"
        }
    }

    Column(
        horizontalAlignment = if (playerLayout == PlayerLayout.IMMERSIVE_CANVAS) Alignment.Start else Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = cleanTitle,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = (-0.2).sp
            ),
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = cleanArtist,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                letterSpacing = 0.1.sp
            ),
            color = LocalContentColor.current.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}