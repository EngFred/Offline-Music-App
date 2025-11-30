package com.engfred.musicplayer.feature_playlist.presentation.screens

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.PlaylistSortOption
import com.engfred.musicplayer.core.ui.components.ErrorIndicator
import com.engfred.musicplayer.core.ui.components.InfoIndicator
import com.engfred.musicplayer.core.ui.components.LoadingIndicator
import com.engfred.musicplayer.feature_playlist.presentation.components.list.AutomaticPlaylistItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.PlaylistGridItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.PlaylistListItem
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.list.PlaylistEvent
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.list.PlaylistViewModel
import kotlin.math.max

/**
 * Main screen for displaying and managing playlists.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PlaylistsScreen(
    viewModel: PlaylistViewModel = hiltViewModel(),
    onPlaylistClick: (Long) -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    // State and context initialization
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = configuration.screenWidthDp

    // Sort Menu State
    var isSortMenuExpanded by rememberSaveable { mutableStateOf(false) }

    // Listen for one-time UI events (like Toast messages)
    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Dynamic layout calculations
    val contentHorizontalPadding = if (isLandscape) 24.dp else 12.dp
    val minColumnWidthDp = if (isLandscape) 200f else 160f
    val computedColumns = ((screenWidthDp.toFloat() / minColumnWidthDp).toInt()).coerceIn(2, 6)
    val gridColumns = max(2, computedColumns)

    // Main screen container
    Box(
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
    ) {
        // Handle different UI states
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorIndicator(
                        message = uiState.error ?: "",
                        onRetry = { viewModel.onEvent(PlaylistEvent.LoadPlaylists) }
                    )
                }
            }

            uiState.automaticPlaylists.isEmpty() && uiState.userPlaylists.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    InfoIndicator(
                        message = "No playlists found.\nTap the '+' button to create your first playlist!",
                        icon = Icons.Default.MusicOff
                    )
                }
            }

            else -> {
                // Main content: a single vertical scroller. Spacing between *sections* kept at 12.dp.
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = 96.dp // Leave space for FABs
                    ),
                    // we keep spacing between sections; the list content itself will control its dividers/spacing
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Automatic playlists row (unchanged)
                    if (uiState.automaticPlaylists.isNotEmpty()) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight().padding(horizontal = contentHorizontalPadding)
                            ) {
                                val automaticItemWidth = if (isLandscape) 200.dp else 160.dp
                                itemsIndexed(uiState.automaticPlaylists, key = { _, it -> it.id }) { _, playlist ->
                                    AutomaticPlaylistItem(
                                        playlist = playlist,
                                        onClick = onPlaylistClick,
                                        modifier = Modifier.width(automaticItemWidth)
                                    )
                                }
                            }
                        }
                    }

                    // My Playlists section (title as a separate section)
                    if (uiState.userPlaylists.isNotEmpty()) {
                        // Header with Sort Button
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = contentHorizontalPadding, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "My Playlists",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                // Sort Button & Menu
                                Box {
                                    IconButton(onClick = { isSortMenuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                                            contentDescription = "Sort Playlists",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = isSortMenuExpanded,
                                        onDismissRequest = { isSortMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Name (A-Z)") },
                                            onClick = {
                                                viewModel.onEvent(PlaylistEvent.ChangeSortOption(PlaylistSortOption.NAME_ASC))
                                                isSortMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (uiState.currentSortOption == PlaylistSortOption.NAME_ASC) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Name (Z-A)") },
                                            onClick = {
                                                viewModel.onEvent(PlaylistEvent.ChangeSortOption(PlaylistSortOption.NAME_DESC))
                                                isSortMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (uiState.currentSortOption == PlaylistSortOption.NAME_DESC) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Oldest First") },
                                            onClick = {
                                                viewModel.onEvent(PlaylistEvent.ChangeSortOption(PlaylistSortOption.DATE_CREATED_ASC))
                                                isSortMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (uiState.currentSortOption == PlaylistSortOption.DATE_CREATED_ASC) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Newest First") },
                                            onClick = {
                                                viewModel.onEvent(PlaylistEvent.ChangeSortOption(PlaylistSortOption.DATE_CREATED_DESC))
                                                isSortMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (uiState.currentSortOption == PlaylistSortOption.DATE_CREATED_DESC) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // If LIST layout selected -> rendering a flat Column inside a single LazyColumn item
                        if (uiState.currentLayout == PlaylistLayoutType.LIST) {
                            item {
                                // Render list items manually inside a Column so we can control dividers (no extra spacing)
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    val lastIndex = uiState.userPlaylists.lastIndex
                                    uiState.userPlaylists.forEachIndexed { index, playlist ->
                                        PlaylistListItem(
                                            modifier = Modifier.padding(start = contentHorizontalPadding, end = 10.dp),
                                            playlist = playlist,
                                            onClick = onPlaylistClick,
                                            onDeleteClick = { playlistId ->
                                                viewModel.onEvent(PlaylistEvent.DeletePlaylist(playlistId))
                                            },
                                            isDeletable = !playlist.name.equals("Favorites", ignoreCase = true),
                                            showDivider = index < lastIndex
                                        )
                                    }
                                }
                            }
                        } else {
                            // GRID layout
                            val chunks = uiState.userPlaylists.chunked(gridColumns)
                            itemsIndexed(chunks) { _, rowPlaylists ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    rowPlaylists.forEach { playlist ->
                                        PlaylistGridItem(
                                            playlist = playlist,
                                            onClick = onPlaylistClick,
                                            onDeleteClick = { playlistId ->
                                                viewModel.onEvent(PlaylistEvent.DeletePlaylist(playlistId))
                                            },
                                            modifier = Modifier.weight(1f),
                                            isDeletable = !playlist.name.equals("Favorites", ignoreCase = true)
                                        )
                                    }
                                    // Add spacers for incomplete rows to maintain alignment
                                    val emptySlots = gridColumns - rowPlaylists.size
                                    repeat(emptySlots) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        // Message when there are no user playlists
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Your own playlists will show up here. Create some!",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(horizontal = 30.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Action Buttons (FABs)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            // Add new playlist FAB
            FloatingActionButton(
                onClick = onCreatePlaylist,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    modifier = Modifier.size(36.dp),
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Create new playlist"
                )
            }

            // Toggle layout FAB
            FloatingActionButton(
                onClick = { viewModel.onEvent(PlaylistEvent.ToggleLayout) },
            ) {
                Icon(
                    modifier = Modifier.size(if (uiState.currentLayout == PlaylistLayoutType.LIST) 24.dp else 30.dp),
                    imageVector = if (uiState.currentLayout == PlaylistLayoutType.LIST) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.List,
                    contentDescription = "Toggle layout for My Playlists"
                )
            }
        }
    }
}