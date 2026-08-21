package com.engfred.musicplayer.feature_playlist.presentation.screens

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.PlaylistSortOption
import com.engfred.musicplayer.core.ui.components.ErrorIndicator
import com.engfred.musicplayer.core.ui.components.InfoIndicator
import com.engfred.musicplayer.feature_playlist.presentation.components.list.AutomaticPlaylistItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.PlaylistGridItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.PlaylistListItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.ShimmerAutomaticPlaylistItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.ShimmerPlaylistGridItem
import com.engfred.musicplayer.feature_playlist.presentation.components.list.ShimmerPlaylistListItem
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    // 1. Shimmer Automatic Playlists (Row)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = contentHorizontalPadding)
                    ) {
                        val automaticItemWidth = if (isLandscape) 200.dp else 160.dp
                        // Show 3 placeholders for the automatic playlists
                        repeat(3) {
                            ShimmerAutomaticPlaylistItem(
                                modifier = Modifier.width(automaticItemWidth)
                            )
                        }
                    }

                    // 2. Shimmer "My Playlists" Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = contentHorizontalPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Title placeholder
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        )
                        // Sort Icon placeholder
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        )
                    }

                    // 3. Shimmer User Playlists (Matches Grid or List state)
                    if (uiState.currentLayout == PlaylistLayoutType.LIST) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            repeat(8) { index ->
                                ShimmerPlaylistListItem(
                                    modifier = Modifier.padding(start = contentHorizontalPadding, end = 10.dp),
                                    showDivider = index < 7
                                )
                            }
                        }
                    } else {
                        // GRID layout placeholder
                        Column(modifier = Modifier.fillMaxWidth()) {
                            repeat(4) { // 4 rows of grids
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    repeat(gridColumns) {
                                        ShimmerPlaylistGridItem(
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                    item {
                        Text(
                            text = "Playlists",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }

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
                        // Header with Layout Toggle & Sort Button
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

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Layout Toggle Button
                                    IconButton(onClick = { viewModel.onEvent(PlaylistEvent.ToggleLayout) }) {
                                        Icon(
                                            imageVector = if (uiState.currentLayout == PlaylistLayoutType.LIST) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.List,
                                            contentDescription = "Toggle layout",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

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
                                            onDismissRequest = { isSortMenuExpanded = false },
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(20.dp)
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                                Text(
                                                    text = "Sort By",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp,
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            androidx.compose.material3.HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                                thickness = 1.dp
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Name (A-Z)") },
                                                onClick = {
                                                    viewModel.onEvent(PlaylistEvent.ChangeSortOption(PlaylistSortOption.NAME_ASC))
                                                    isSortMenuExpanded = false
                                                },
                                                trailingIcon = {
                                                    if (uiState.currentSortOption == PlaylistSortOption.NAME_ASC) {
                                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
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

        // Floating Action Button
        FloatingActionButton(
            onClick = onCreatePlaylist,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Create new playlist",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
