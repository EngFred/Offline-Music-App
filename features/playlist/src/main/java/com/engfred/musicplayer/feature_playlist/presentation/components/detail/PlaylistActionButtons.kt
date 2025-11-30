package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

/**
 * Action buttons for playlist detail screen.
 *
 * - Both Play and Shuffle are outlined tiles for visual consistency.
 * - Play uses a distinct border color (primary) to differentiate it.
 */
@Composable
fun PlaylistActionButtons(
    onPlayAllClick: () -> Unit,
    onShuffleAllClick: () -> Unit,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    // Sizes tuned for compact vs expanded
    val tileHeight = if (isCompact) 56.dp else 68.dp
    val tileMinWidth = if (isCompact) 130.dp else 180.dp
    val cornerRadius = if (isCompact) 12.dp else 16.dp
    val iconSize = if (isCompact) 28.dp else 36.dp
    val labelStyle = MaterialTheme.typography.titleMedium

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (isCompact) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play tile - Outlined (looks like Shuffle) with primary-colored border
        OutlinedButton(
            onClick = onPlayAllClick,
            modifier = Modifier
                .weight(1f)
                .height(tileHeight)
                .widthIn(min = tileMinWidth)
                .clip(RoundedCornerShape(cornerRadius)),
            shape = RoundedCornerShape(cornerRadius),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tileHeight)
            ) {
                // Icon chip
                Surface(
                    shape = RoundedCornerShape(50),
                    tonalElevation = 2.dp,
                    modifier = Modifier.size(iconSize + 12.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play Playlist",
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Play",
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "All songs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Shuffle tile - Outlined style, lighter border (uses secondary)
        OutlinedButton(
            onClick = onShuffleAllClick,
            modifier = Modifier
                .weight(1f)
                .height(tileHeight)
                .widthIn(min = tileMinWidth)
                .clip(RoundedCornerShape(cornerRadius)),
            shape = RoundedCornerShape(cornerRadius),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Icon chip
                Surface(
                    shape = RoundedCornerShape(50),
                    tonalElevation = 2.dp,
                    modifier = Modifier.size(iconSize + 12.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle Playlist",
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        "Shuffle",
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Mixed order",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
