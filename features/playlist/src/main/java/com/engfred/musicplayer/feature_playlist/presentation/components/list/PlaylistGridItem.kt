package com.engfred.musicplayer.feature_playlist.presentation.components.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.domain.model.Playlist
import com.engfred.musicplayer.core.util.TextUtils
import com.engfred.musicplayer.feature_playlist.R
import com.engfred.musicplayer.feature_playlist.presentation.components.DeleteConfirmationDialog
import com.engfred.musicplayer.feature_playlist.utils.findFirstAlbumArtUri
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
fun PlaylistGridItem(
    playlist: Playlist,
    onClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isDeletable: Boolean = true
) {
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(playlist.id) }
            .padding(4.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top
    ) {
        // 1. Standalone Artwork Card (1:1 aspect ratio with rounded corners & shadow)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp),
                    clip = false
                )
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF2C283E), Color(0xFF161426))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val displayArt = playlist.findFirstAlbumArtUri()

            if (displayArt != null) {
                CoilImage(
                    imageModel = { displayArt },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop
                    ),
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    },
                    failure = {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "No album art available",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                )
            } else if (!isDeletable) {
                Image(
                    painter = painterResource(id = R.drawable.favorites),
                    contentDescription = "Favorites",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = "No album art available",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                )
            }

            if (isDeletable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Playlist options",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(20.dp)
                        )
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete Playlist",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 13.5.sp
                                )
                            },
                            onClick = {
                                showDeleteConfirmationDialog = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Playlist Name & Song Count directly below artwork (Clean typography!)
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = TextUtils.pluralize(playlist.songs.size, "song"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .fillMaxWidth()
        )
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