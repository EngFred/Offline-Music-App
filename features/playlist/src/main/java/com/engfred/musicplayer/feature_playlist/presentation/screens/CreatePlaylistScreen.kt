package com.engfred.musicplayer.feature_playlist.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.core.ui.components.LoadingIndicator
import com.engfred.musicplayer.core.ui.components.MiniPlayer
import com.engfred.musicplayer.feature_playlist.presentation.components.detail.AddSongItem
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.create.CreatePlaylistEvent
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.create.CreatePlaylistViewModel
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.create.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaylistScreen(
    viewModel: CreatePlaylistViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayPauseClick: () -> Unit,
    onMiniPlayNext: () -> Unit,
    onMiniPlayPrevious: () -> Unit,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean
) {
    val uiState by viewModel.uiState.collectAsState()
    // filtered songs exposed from ViewModel (debounced + sorted)
    val filteredSongs by viewModel.filteredSongs.collectAsState(initial = uiState.allSongs)
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isFilterMenuOpen by viewModel.isFilterMenuOpen.collectAsState()
    val currentSort by viewModel.sortOrder.collectAsState()

    val context = LocalContext.current

    // LazyListState used to programmatically scroll the list
    val listState = rememberLazyListState()

    // When the sort order changes, scroll the list to top if it's not already at the top
    LaunchedEffect(key1 = currentSort) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(key1 = viewModel) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is String -> Toast.makeText(context, event, Toast.LENGTH_SHORT).show()
                is Unit -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            CustomTopBar(
                modifier = Modifier.statusBarsPadding().padding(end = 10.dp),
                title = "Create Playlist",
                showNavigationIcon = true,
                onNavigateBack = onNavigateBack,
                actions = {
                    // if saving show a small progress in top bar instead of blocking the whole list
                    if (uiState.isSaving) {
                        Box(
                            modifier = Modifier.padding(end = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.onEvent(CreatePlaylistEvent.SavePlaylist) }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = "Save Playlist"
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (playingAudioFile != null) {
                MiniPlayer(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = onMiniPlayerClick,
                    onPlayPause = onMiniPlayPauseClick,
                    onPlayNext = onMiniPlayNext,
                    onPlayPrev = onMiniPlayPrevious,
                    playingAudioFile = playingAudioFile,
                    isPlaying = isPlaying
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            )
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { viewModel.onEvent(CreatePlaylistEvent.UpdateName(it)) },
                label = { Text("Playlist Name") },
                isError = uiState.error != null,
                supportingText = {
                    if (uiState.error != null) {
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Title row with filter icon on the extreme right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Add Songs (Optional)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Wrap filter icon and dropdown in Box for proper anchoring
                Box {
                    IconButton(
                        onClick = { viewModel.onEvent(CreatePlaylistEvent.ToggleFilterMenu) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = "Filter / Sort songs"
                        )
                    }

                    // Dropdown menu anchored to the Box (menu visibility controlled by viewModel)
                    DropdownMenu(
                        expanded = isFilterMenuOpen,
                        onDismissRequest = { viewModel.onEvent(CreatePlaylistEvent.DismissFilterMenu) },
                        modifier = Modifier.align(Alignment.TopEnd),
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Latest first") },
                            onClick = {
                                viewModel.onEvent(CreatePlaylistEvent.SetSortOrder(SortOrder.LATEST))
                                viewModel.onEvent(CreatePlaylistEvent.DismissFilterMenu)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Oldest first") },
                            onClick = {
                                viewModel.onEvent(CreatePlaylistEvent.SetSortOrder(SortOrder.OLDEST))
                                viewModel.onEvent(CreatePlaylistEvent.DismissFilterMenu)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("A → Z") },
                            onClick = {
                                viewModel.onEvent(CreatePlaylistEvent.SetSortOrder(SortOrder.A_Z))
                                viewModel.onEvent(CreatePlaylistEvent.DismissFilterMenu)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Z → A") },
                            onClick = {
                                viewModel.onEvent(CreatePlaylistEvent.SetSortOrder(SortOrder.Z_A))
                                viewModel.onEvent(CreatePlaylistEvent.DismissFilterMenu)
                            }
                        )
                    }
                }
            }

            TextField(
                value = searchQuery,
                onValueChange = { viewModel.onEvent(CreatePlaylistEvent.UpdateSearchQuery(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
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

            Spacer(modifier = Modifier.height(8.dp))

            // Keep the list visible while saving — only block navigation or changes if necessary
            if (uiState.isSaving && filteredSongs.isEmpty()) {
                // fallback if there are literally no songs to show
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredSongs,
                        key = { it.id }
                    ) { audioFile ->
                        val isSelected = uiState.selectedSongIds.contains(audioFile.id)

                        AddSongItem(
                            audioFile = audioFile,
                            onAddOrRemoveSong = {
                                viewModel.onEvent(CreatePlaylistEvent.ToggleSongSelection(audioFile.id))
                            },
                            isSelected = isSelected
                        )
                    }
                }
            }
        }
    }
}
