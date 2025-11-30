package com.engfred.musicplayer.core.ui.components

import android.graphics.Color
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.util.MediaUtils
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.isActive

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
    val rotationAnim = remember { Animatable(0f) }

    LaunchedEffect(isCurrentPlayingAudio, isAudioPlaying) {
        if (isCurrentPlayingAudio && isAudioPlaying) {
            while (isActive) {
                val target = rotationAnim.value + 360f
                rotationAnim.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
                )
            }
        } else {
            if (!isCurrentPlayingAudio) rotationAnim.snapTo(0f)
        }
    }
    val rotationDegrees = if (isCurrentPlayingAudio) (rotationAnim.value % 360f) else 0f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onItemTap,
                onLongClick = onItemLongPress
            ).padding(top = 4.dp, bottom = 4.dp, start = if(isSelectionMode.not())15.dp else 3.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .size(56.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .graphicsLayer { rotationZ = rotationDegrees },
                contentAlignment = Alignment.Center
            ) {
                CoilImage(
                    imageModel = { audioFile.albumArtUri },
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = "Loading icon",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(45.dp)
                            )
                        }
                    },
                    failure = {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = "No album art available",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(45.dp)
                            )
                        }
                    }
                )
            }
            if (playCount != null) {
                val badgeSize = 20.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 0.dp, y = 3.dp)
                        .size(badgeSize)
                        .zIndex(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (playCount > 9) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    } else {
                        Text(
                            text = playCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = audioFile.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            val artistText = audioFile.artist ?: "Unknown Artist"
            Text(
                text = artistText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = MediaUtils.formatDuration(audioFile.duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 0.dp)
            )
            if (isCurrentPlayingAudio && isAudioPlaying) VisualizerBars()
        }

        if (!isSelectionMode) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options for ${audioFile.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next") },
                        onClick = {
                            onPlayNext(audioFile)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.QueuePlayNext, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Playlist") },
                        onClick = {
                            onAddToPlaylist(audioFile)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    )

                    // Use as Playlist Cover Option
                    // Only show if:
                    // 1. Not in automatic playlist (Favorites/Recently Added)
                    // 2. Not in Library screen (isFromLibrary flag)
                    // 3. Audio file actually has artwork
                    if (!isFromAutomaticPlaylist && !isFromLibrary && audioFile.albumArtUri != null && onSetAsPlaylistCover != null ) {
                        DropdownMenuItem(
                            text = { Text("Use as Playlist Cover") },
                            onClick = {
                                onSetAsPlaylistCover(audioFile)
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                            }
                        )
                    }

                    if (!isFromAutomaticPlaylist) {
                        DropdownMenuItem(
                            text = { Text(if (isFromLibrary) "Delete File" else "Remove Audio") },
                            onClick = {
                                onRemoveOrDelete(audioFile)
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                            }
                        )
                    }

                    // ... (Keep Edit Info, Trim, Share) ...
                    DropdownMenuItem(
                        text = { Text("Edit Info") },
                        onClick = {
                            showMenu = false
                            onEditInfo(audioFile)
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Trim Audio") },
                        onClick = {
                            showMenu = false
                            onTrimAudio(audioFile)
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Share Audio") },
                        onClick = {
                            MediaUtils.shareAudioFile(context, audioFile)
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

@Composable
private fun VisualizerBars() {
    val transition = rememberInfiniteTransition(label = "visualizer")
    val heights = List(4) { i ->
        transition.animateFloat(
            initialValue = 4f,
            targetValue = 12f + (i * 4),
            animationSpec = infiniteRepeatable(
                animation = tween(400 + (i * 100)),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }
    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { anim ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(anim.value.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}