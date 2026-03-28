package com.engfred.musicplayer.feature_playlist.presentation.components.detail

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import com.engfred.musicplayer.core.ui.components.shimmerBrush

@Composable
fun ShimmerPlaylistDetailHeaderSection(isCompact: Boolean, modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    val imageSize = if (isCompact) 200.dp else 240.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = if (isCompact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.size(20.dp))
        if (!isCompact) Spacer(Modifier.size(34.dp))

        // Album Art Placeholder
        Box(
            modifier = Modifier
                .size(imageSize)
                .clip(RoundedCornerShape(if (isCompact) 16.dp else 20.dp))
                .background(brush)
        )

        Spacer(modifier = Modifier.height(if (isCompact) 13.dp else 32.dp))

        // Title Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(if (isCompact) 28.dp else 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(brush)
        )
    }
}

@Composable
fun ShimmerPlaylistActionButtons(isCompact: Boolean, modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    val tileHeight = if (isCompact) 56.dp else 68.dp
    val cornerRadius = if (isCompact) 12.dp else 16.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (isCompact) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play & Shuffle Button Placeholders
        repeat(2) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(tileHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(brush)
            )
        }
    }
}

@Composable
fun ShimmerPlaylistSongsHeader(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // "Songs (X)" Placeholder
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
        )
        // Sort Button Placeholder
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(brush)
        )
    }
}