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
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.util.MediaUtils
import com.skydoves.landscapist.coil.CoilImage
import java.util.Locale

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
    playCount: Int? = null, // Null by default; won't show in standard library
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

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else Color.Transparent,
        label = "selection_bg_color"
    )

    val titleColor by animateColorAsState(
        targetValue = if (isCurrentPlayingAudio) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
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
            .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // 1. Album Art
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

            if (isCurrentPlayingAudio) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isAudioPlaying) VisualizerBars()
                    else Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(20.dp))
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

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = audioFile.artist ?: "Unknown Artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Only show badge if playCount is provided and > 0 (e.g., in "Most Played" screen)
                if (playCount != null && playCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = formatPlayCount(playCount),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 3. Trailing Actions
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
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )

                Box(contentAlignment = Alignment.CenterEnd) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

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

/**
 * Expert UI Helper: Formats numbers for clean display.
 * 1,240 becomes 1.2k
 * 1,000,000 becomes 1M
 */
private fun formatPlayCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", count / 1_000f)
        else -> count.toString()
    }
}

@Composable
private fun DefaultAlbumArtIcon() {
    Icon(
        imageVector = Icons.Rounded.MusicNote,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.size(28.dp)
    )
}

@Composable
private fun VisualizerBars() {
    val transition = rememberInfiniteTransition(label = "visualizer")
    val heights = List(3) { i ->
        transition.animateFloat(
            initialValue = 6f,
            targetValue = 18f + (i * 2),
            animationSpec = infiniteRepeatable(
                animation = tween(450 + (i * 150)),
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
                    .width(3.dp)
                    .height(anim.value.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}