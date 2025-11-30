package com.engfred.musicplayer.feature_playlist.presentation.components.list

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.feature_playlist.utils.formatDate
import com.engfred.musicplayer.feature_playlist.presentation.components.DeleteConfirmationDialog
import com.engfred.musicplayer.core.util.TextUtils
import com.engfred.musicplayer.feature_playlist.R
import com.engfred.musicplayer.feature_playlist.utils.findFirstAlbumArtUri
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/**
 * Flat list row version of a playlist (not a Card). Shows a horizontal divider if [showDivider] is true.
 */
@Composable
fun PlaylistListItem(
    playlist: Playlist,
    onClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    isDeletable: Boolean,
    showDivider: Boolean,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(playlist.id) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Album art / placeholder
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // When not deletable -> show favorites drawable
                if (!isDeletable) {
                    Image(
                        painter = painterResource(id = R.drawable.favorites),
                        contentDescription = "Favorites",
                        modifier = Modifier
                            .fillMaxWidth()
                            .width(56.dp)
                            .height(56.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // deletable: show first song album art (Coil) or fallback icon
                    val albumArtUri = playlist.findFirstAlbumArtUri()
                    if (albumArtUri != null) {
                        CoilImage(
                            imageModel = { albumArtUri },
                            modifier = Modifier
                                .fillMaxWidth()
                                .width(56.dp)
                                .height(56.dp),
                            imageOptions = ImageOptions(
                                contentScale = ContentScale.Crop
                            ),
                            loading = {
                                Box(modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant))
                            },
                            failure = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = "No album art available",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(35.dp)
                                    )
                                }
                            }
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "No album art available",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Textual info (name, songs count, created)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = TextUtils.pluralize(playlist.songs.size, "song"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Created: ${formatDate(playlist.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            // Options menu (delete)
            if (isDeletable) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Playlist options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete Playlist") },
                            onClick = {
                                showDeleteConfirmationDialog = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                            }
                        )
                    }
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        }
    }

    if (showDeleteConfirmationDialog) {
        DeleteConfirmationDialog(
            itemName = playlist.name,
            onConfirm = {
                onDeleteClick(playlist.id)
                showDeleteConfirmationDialog = false
            },
            onDismiss = { showDeleteConfirmationDialog = false }
        )
    }
}