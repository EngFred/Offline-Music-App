package com.engfred.musicplayer.feature_playlist.presentation.components.list

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PlaylistGridPlaceholderIconSize = 88.dp
private val PlaylistListPlaceholderIconSize = 28.dp

@Composable
internal fun PlaylistGridArtworkPlaceholder(
    modifier: Modifier = Modifier,
    size: Dp = PlaylistGridPlaceholderIconSize,
    tint: Color = Color.White.copy(alpha = 0.58f)
) {
    PlaylistArtworkPlaceholder(
        modifier = modifier,
        size = size,
        tint = tint
    )
}

@Composable
internal fun PlaylistListArtworkPlaceholder(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.58f)
) {
    PlaylistArtworkPlaceholder(
        modifier = modifier,
        size = PlaylistListPlaceholderIconSize,
        tint = tint
    )
}

@Composable
private fun PlaylistArtworkPlaceholder(
    modifier: Modifier,
    size: Dp,
    tint: Color
) {
    Icon(
        imageVector = Icons.Rounded.MusicNote,
        contentDescription = "No album art available",
        tint = tint,
        modifier = modifier.then(Modifier.size(size))
    )
}
