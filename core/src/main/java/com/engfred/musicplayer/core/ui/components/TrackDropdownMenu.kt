package com.engfred.musicplayer.core.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile

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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 200.dp) // Ensures a consistent, premium width
            .clip(RoundedCornerShape(20.dp)) // Slightly more rounded for modern look
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp))
    ) {
        // --- SECTION 1: QUICK ACTIONS ---
        DropdownMenuItem(
            text = { Text("Play Next", fontWeight = FontWeight.Medium) },
            onClick = { onPlayNext(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text("Add to Playlist", fontWeight = FontWeight.Medium) },
            onClick = { onAddToPlaylist(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text("Share", fontWeight = FontWeight.Medium) },
            onClick = { onShare(); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.Share, null, modifier = Modifier.size(20.dp)) }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), thickness = 0.5.dp)

        // --- SECTION 2: FILE UTILITIES ---
        DropdownMenuItem(
            text = { Text("Edit Metadata") },
            onClick = { onEditInfo(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(20.dp)) }
        )
        DropdownMenuItem(
            text = { Text("Trim Audio") },
            onClick = { onTrimAudio(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.ContentCut, null, modifier = Modifier.size(20.dp)) }
        )

        if (!isFromLibrary && audioFile.albumArtUri.toString().isNotEmpty() && onSetAsPlaylistCover != null) {
            DropdownMenuItem(
                text = { Text("Use as Playlist Cover") },
                onClick = { onSetAsPlaylistCover(audioFile); onDismiss() },
                leadingIcon = { Icon(Icons.Rounded.Image, null, modifier = Modifier.size(20.dp)) }
            )
        }

        // --- SECTION 3: DANGER ZONE (Always at the bottom) ---
        if (!isFromAutomaticPlaylist) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), thickness = 0.5.dp)
            DropdownMenuItem(
                text = { Text(if (isFromLibrary) "Delete from Device" else "Remove from Playlist") },
                onClick = { onRemoveOrDelete(audioFile); onDismiss() },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
            )
        }
    }
}