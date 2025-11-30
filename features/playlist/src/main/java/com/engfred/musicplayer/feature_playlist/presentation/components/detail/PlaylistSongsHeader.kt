package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_playlist.domain.model.PlaylistSortOrder

@Composable
fun PlaylistSongsHeader(
    modifier: Modifier = Modifier,
    songCount: Int,
    currentSortOrder: PlaylistSortOrder,
    onSortOrderChange: (PlaylistSortOrder) -> Unit,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    isTopSongs: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp), // Keep consistent padding
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Songs ($songCount)",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Box {
            // Enhanced sort button appearance
            Button(
                onClick = { onSortMenuExpandedChange(true) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = "Sort songs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when(currentSortOrder) {
                        PlaylistSortOrder.DATE_ADDED -> "Date Added"
                        PlaylistSortOrder.ALPHABETICAL -> "Alphabetical"
                        PlaylistSortOrder.PLAY_COUNT -> "Play Count"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { onSortMenuExpandedChange(false) },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text("Date Added") },
                    onClick = {
                        onSortOrderChange(PlaylistSortOrder.DATE_ADDED)
                        onSortMenuExpandedChange(false)
                    },
                    leadingIcon = {
                        if (currentSortOrder == PlaylistSortOrder.DATE_ADDED) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Spacer(modifier = Modifier.size(Icons.Rounded.Check.defaultWidth, Icons.Rounded.Check.defaultHeight)) // Placeholder for alignment
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Alphabetical (A-Z)") },
                    onClick = {
                        onSortOrderChange(PlaylistSortOrder.ALPHABETICAL)
                        onSortMenuExpandedChange(false)
                    },
                    leadingIcon = {
                        if (currentSortOrder == PlaylistSortOrder.ALPHABETICAL) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Spacer(modifier = Modifier.size(Icons.Rounded.Check.defaultWidth, Icons.Rounded.Check.defaultHeight)) // Placeholder for alignment
                        }
                    }
                )
                if (isTopSongs) {
                    DropdownMenuItem(
                        text = { Text("Play Count (High to Low)") },
                        onClick = {
                            onSortOrderChange(PlaylistSortOrder.PLAY_COUNT)
                            onSortMenuExpandedChange(false)
                        },
                        leadingIcon = {
                            if (currentSortOrder == PlaylistSortOrder.PLAY_COUNT) {
                                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Spacer(modifier = Modifier.size(Icons.Rounded.Check.defaultWidth, Icons.Rounded.Check.defaultHeight)) // Placeholder for alignment
                            }
                        }
                    )
                }
            }
        }
    }
}