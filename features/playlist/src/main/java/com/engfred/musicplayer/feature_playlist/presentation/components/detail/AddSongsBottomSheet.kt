package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    allAudioFiles: List<AudioFile>,
    currentPlaylistSongs: List<AudioFile>,
    onSongsSelected: (List<AudioFile>) -> Unit
) {

    // Keep track of selected songs by their String IDs
    val initialSelectedSongIdsSet = remember(currentPlaylistSongs) {
        currentPlaylistSongs.map { it.id }.toMutableSet()
    }
    val selectedSongIds = remember { mutableStateListOf<Long>().apply { addAll(initialSelectedSongIdsSet) } }

    var searchQuery by remember { mutableStateOf("") }

    val sortedAudioFiles = remember(allAudioFiles) { allAudioFiles.sortedByDescending { it.dateAdded } }

    val filteredSongs = remember(sortedAudioFiles, searchQuery) {
        if (searchQuery.isBlank()) {
            sortedAudioFiles
        } else {
            sortedAudioFiles.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        (it.artist?.contains(searchQuery, ignoreCase = true) ?: false) ||
                        (it.album?.contains(searchQuery, ignoreCase = true) ?: false)
            }
        }
    }

    // Filter out songs that are already in the current playlist
    val availableSongsForSelection = remember(filteredSongs, currentPlaylistSongs) {
        val currentPlaylistSongStringIds = currentPlaylistSongs.map { it.id.toString() }.toSet()
        filteredSongs.filter { it.id.toString() !in currentPlaylistSongStringIds }
    }


    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to playlist details",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Add Songs",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = {
                        val songsToAdd = allAudioFiles.filter {
                            it.id in selectedSongIds &&
                                    it.id !in initialSelectedSongIdsSet
                        }
                        onSongsSelected(songsToAdd)
                        onDismissRequest()
                    },
                    enabled = (selectedSongIds.size - initialSelectedSongIdsSet.size) > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    val newSongsCount = selectedSongIds.size - initialSelectedSongIdsSet.size
                    Text("Add ($newSongsCount)")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search songs...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = MaterialTheme.shapes.small
            )

            if (availableSongsForSelection.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching songs found." else "All songs are already in this playlist!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(availableSongsForSelection, key = { it.id }) { audioFile ->
                        val isSelected = selectedSongIds.contains(audioFile.id)

                        AddSongItem(
                            audioFile = audioFile,
                            onAddOrRemoveSong = {
                                if (isSelected) {
                                    selectedSongIds.remove(audioFile.id)
                                } else {
                                    selectedSongIds.add(audioFile.id)
                                }
                            },
                            isSelected = isSelected
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddSongItem(
    audioFile: AudioFile,
    onAddOrRemoveSong: () -> Unit,
    isSelected: Boolean
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        LocalContentColor.current
    }
    val artistColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        LocalContentColor.current.copy(alpha = 0.7f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onAddOrRemoveSong)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CoilImage(
            imageModel = { audioFile.albumArtUri },
            imageOptions = ImageOptions(
                contentDescription = "Album Art",
                contentScale = ContentScale.Crop
            ),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            failure = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        contentDescription = "No Album Art",
                    )
                }
            },
            loading = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        contentDescription = "No Album Art",
                    )
                }
            }
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = audioFile.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = audioFile.artist ?: "Unknown Artist",
                style = MaterialTheme.typography.bodyMedium,
                color = artistColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Option to remove item from queue
        Checkbox(
            colors = CheckboxDefaults.colors(
                uncheckedColor = artistColor
            ),
            checked = isSelected,
            onCheckedChange = {
                onAddOrRemoveSong()
            }
        )
    }
}