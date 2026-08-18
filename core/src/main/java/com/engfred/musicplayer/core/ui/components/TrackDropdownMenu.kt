package com.engfred.musicplayer.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.AudioFile

/**
 * World-Class Elevated Track Options Popover Menu
 */
@Composable
fun TrackDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    audioFile: AudioFile,
    isFromLibrary: Boolean,
    isFromAutomaticPlaylist: Boolean,
    onPlayNext: (AudioFile) -> Unit,
    onAddToPlaylist: (AudioFile) -> Unit,
    onSetAsPlaylistCover: ((AudioFile) -> Unit)?,
    onRemoveOrDelete: (AudioFile) -> Unit,
    onEditInfo: (AudioFile) -> Unit,
    onTrimAudio: (AudioFile) -> Unit,
    onShare: () -> Unit
) {
    val cleanTitle = remember(audioFile.title) {
        audioFile.title.replace('_', ' ').trim()
    }
    val cleanArtist = remember(audioFile.artist) {
        audioFile.artist?.replace('_', ' ')?.trim() ?: "Unknown Artist"
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .widthIn(min = 210.dp, max = 260.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        // ── Track Header Info ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = cleanTitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = cleanArtist,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
            thickness = 1.dp
        )

        // ── Quick Actions ───────────────────────────────────────────────────
        DropdownMenuItem(
            text = {
                Text(
                    text = "Play Next",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                )
            },
            onClick = { onPlayNext(audioFile); onDismiss() },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.QueuePlayNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        DropdownMenuItem(
            text = {
                Text(
                    text = "Add to Playlist",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                )
            },
            onClick = { onAddToPlaylist(audioFile); onDismiss() },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        DropdownMenuItem(
            text = {
                Text(
                    text = "Share",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                )
            },
            onClick = { onShare(); onDismiss() },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // ── File Utilities ─────────────────────────────────────────────────
        DropdownMenuItem(
            text = {
                Text(
                    text = "Edit Metadata",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp)
                )
            },
            onClick = { onEditInfo(audioFile); onDismiss() },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        DropdownMenuItem(
            text = {
                Text(
                    text = "Trim Audio",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp)
                )
            },
            onClick = { onTrimAudio(audioFile); onDismiss() },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ContentCut,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        if (!isFromLibrary && audioFile.albumArtUri.toString().isNotEmpty() && onSetAsPlaylistCover != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Use as Playlist Cover",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp)
                    )
                },
                onClick = { onSetAsPlaylistCover(audioFile); onDismiss() },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        // ── Danger Zone ───────────────────────────────────────────────────
        if (!isFromAutomaticPlaylist) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (isFromLibrary) "Delete from Device" else "Remove from Playlist",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.5.sp
                        )
                    )
                },
                onClick = { onRemoveOrDelete(audioFile); onDismiss() },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
            )
        }
    }
}