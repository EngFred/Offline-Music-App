package com.engfred.musicplayer.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.util.MediaUtils
import com.skydoves.landscapist.coil.CoilImage

@Composable
fun AudioFileItem(
    modifier: Modifier = Modifier,
    audioFile: AudioFile,
    isCurrentPlayingAudio: Boolean,
    isAudioPlaying: Boolean,
    onPlayNext: (AudioFile) -> Unit = {},
    onAddToPlaylist: (AudioFile) -> Unit,
    onRemoveOrDelete: (AudioFile) -> Unit,
    isFromAutomaticPlaylist: Boolean = false,
    isFromLibrary: Boolean = false,
    playCount: Int? = null,
    onEditInfo: (AudioFile) -> Unit,
    onTrimAudio: (AudioFile) -> Unit,
    onSetAsPlaylistCover: ((AudioFile) -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onItemTap: () -> Unit = {},
    onItemLongPress: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Subtle background highlight when selected
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
        label = "selection_bg_color"
    )

    // Dynamic Title Color for Playing State
    val titleColor by animateColorAsState(
        targetValue = if (isCurrentPlayingAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        label = "title_color"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .combinedClickable(
                onClick = { if (isSelectionMode) onToggleSelect() else onItemTap() },
                onLongClick = { if (!isSelectionMode) onItemLongPress() }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // 1. Premium Album Art
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CoilImage(
                imageModel = { audioFile.albumArtUri },
                modifier = Modifier.fillMaxSize(),
                loading = { DefaultAlbumArtIcon() },
                failure = { DefaultAlbumArtIcon() }
            )

            // Overlaid Visualizer when playing (The "Spotify/Apple" feel)
            if (isCurrentPlayingAudio) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isAudioPlaying) {
                        VisualizerBars()
                    } else {
                        // Show a paused indicator if it's the current track but paused
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = "Paused",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 2. Metadata Column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = audioFile.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Cleanly integrated artist and play count
            val artistText = audioFile.artist ?: "Unknown Artist"
            val metaText = if (playCount != null && playCount > 0) "$artistText • $playCount plays" else artistText

            Text(
                text = metaText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 3. Trailing Actions (Duration / Menu / Checkbox)
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = MediaUtils.formatDuration(audioFile.duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Modularized Menu to keep main composable clean
                    TrackDropdownMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        audioFile = audioFile,
                        isFromLibrary = isFromLibrary,
                        isFromAutomaticPlaylist = isFromAutomaticPlaylist,
                        onPlayNext = onPlayNext,
                        onAddToPlaylist = onAddToPlaylist,
                        onSetAsPlaylistCover = onSetAsPlaylistCover,
                        onRemoveOrDelete = onRemoveOrDelete,
                        onEditInfo = onEditInfo,
                        onTrimAudio = onTrimAudio,
                        onShare = { MediaUtils.shareAudioFile(context, audioFile) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultAlbumArtIcon() {
    Icon(
        imageVector = Icons.Rounded.MusicNote,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(32.dp)
    )
}

@Composable
private fun VisualizerBars() {
    val transition = rememberInfiniteTransition(label = "visualizer")
    val heights = List(3) { i ->
        transition.animateFloat(
            initialValue = 6f,
            targetValue = 18f + (i * 2), // Slightly taller for the center overlay
            animationSpec = infiniteRepeatable(
                animation = tween(400 + (i * 150)),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }
    Row(
        modifier = Modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { anim ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(anim.value.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White) // White pops best against dark overlay
            )
        }
    }
}

@Composable
private fun TrackDropdownMenu(
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
    ) {
        DropdownMenuItem(
            text = { Text("Play Next") },
            onClick = { onPlayNext(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Add to Playlist") },
            onClick = { onAddToPlaylist(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) }
        )

        if (!isFromLibrary && audioFile.albumArtUri.toString().isNotEmpty() && onSetAsPlaylistCover != null) {
            DropdownMenuItem(
                text = { Text("Use as Playlist Cover") },
                onClick = { onSetAsPlaylistCover(audioFile); onDismiss() },
                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) }
            )
        }

        if (!isFromAutomaticPlaylist) {
            DropdownMenuItem(
                text = { Text(if (isFromLibrary) "Delete File" else "Remove Audio") },
                onClick = { onRemoveOrDelete(audioFile); onDismiss() },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error) // Fixed here!
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

        DropdownMenuItem(
            text = { Text("Edit Metadata") },
            onClick = { onEditInfo(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Trim Audio") },
            onClick = { onTrimAudio(audioFile); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Share") },
            onClick = { onShare(); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) }
        )
    }
}