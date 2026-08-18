package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Songs ($songCount)",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Box {
            OutlinedButton(
                onClick = { onSortMenuExpandedChange(true) },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = "Sort songs",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when (currentSortOrder) {
                        PlaylistSortOrder.DATE_ADDED -> "Date Added"
                        PlaylistSortOrder.ALPHABETICAL -> "Alphabetical"
                        PlaylistSortOrder.PLAY_COUNT -> "Play Count"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                )
            }

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { onSortMenuExpandedChange(false) },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(
                        text = "Sort Songs",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    thickness = 1.dp
                )
                DropdownMenuItem(
                    text = { Text("Date Added", fontSize = 13.5.sp) },
                    onClick = {
                        onSortOrderChange(PlaylistSortOrder.DATE_ADDED)
                        onSortMenuExpandedChange(false)
                    },
                    trailingIcon = {
                        if (currentSortOrder == PlaylistSortOrder.DATE_ADDED) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Alphabetical (A-Z)", fontSize = 13.5.sp) },
                    onClick = {
                        onSortOrderChange(PlaylistSortOrder.ALPHABETICAL)
                        onSortMenuExpandedChange(false)
                    },
                    trailingIcon = {
                        if (currentSortOrder == PlaylistSortOrder.ALPHABETICAL) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                )
                if (isTopSongs) {
                    DropdownMenuItem(
                        text = { Text("Play Count (High to Low)", fontSize = 13.5.sp) },
                        onClick = {
                            onSortOrderChange(PlaylistSortOrder.PLAY_COUNT)
                            onSortMenuExpandedChange(false)
                        },
                        trailingIcon = {
                            if (currentSortOrder == PlaylistSortOrder.PLAY_COUNT) {
                                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }
            }
        }
    }
}