package com.engfred.musicplayer.feature_video.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engfred.musicplayer.core.ui.components.CastMediaRouteButton
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoSortOption

@Composable
fun VideoTopBar(
    totalVideosCount: Int,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchActiveToggle: (Boolean) -> Unit,
    currentSortOption: VideoSortOption,
    onSortOptionChange: (VideoSortOption) -> Unit,
    availableFolders: List<String>,
    selectedFolder: String?,
    onFolderSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Main Row: Title + Search Icon + Sort Icon + Cast Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isSearchActive) {
                Column {
                    Text(
                        text = "Videos",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (totalVideosCount == 1) "1 video" else "$totalVideosCount videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search videos...", fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    shape = CircleShape,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) {
                                onSearchQueryChange("")
                            } else {
                                onSearchActiveToggle(false)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close Search",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isSearchActive) {
                    IconButton(onClick = { onSearchActiveToggle(true) }) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Sort Dropdown Button
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "Sort",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        SortMenuItem("Recently added", VideoSortOption.DATE_ADDED_DESC, currentSortOption) {
                            onSortOptionChange(VideoSortOption.DATE_ADDED_DESC)
                            sortMenuExpanded = false
                        }
                        SortMenuItem("Oldest first", VideoSortOption.DATE_ADDED_ASC, currentSortOption) {
                            onSortOptionChange(VideoSortOption.DATE_ADDED_ASC)
                            sortMenuExpanded = false
                        }
                        SortMenuItem("Name (A to Z)", VideoSortOption.TITLE_ASC, currentSortOption) {
                            onSortOptionChange(VideoSortOption.TITLE_ASC)
                            sortMenuExpanded = false
                        }
                        SortMenuItem("Name (Z to A)", VideoSortOption.TITLE_DESC, currentSortOption) {
                            onSortOptionChange(VideoSortOption.TITLE_DESC)
                            sortMenuExpanded = false
                        }
                        SortMenuItem("Longest duration", VideoSortOption.DURATION_DESC, currentSortOption) {
                            onSortOptionChange(VideoSortOption.DURATION_DESC)
                            sortMenuExpanded = false
                        }
                        SortMenuItem("Largest size", VideoSortOption.SIZE_DESC, currentSortOption) {
                            onSortOptionChange(VideoSortOption.SIZE_DESC)
                            sortMenuExpanded = false
                        }
                    }
                }

                // Cast Media Route Button
                CastMediaRouteButton(
                    tintColor = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // Folder Chips Row (shown if there are folders)
        if (availableFolders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFolder == null,
                    onClick = { onFolderSelect(null) },
                    label = { Text("All", fontSize = 12.5.sp) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                availableFolders.forEach { folder ->
                    FilterChip(
                        selected = selectedFolder == folder,
                        onClick = { onFolderSelect(if (selectedFolder == folder) null else folder) },
                        label = { Text(folder, fontSize = 12.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    option: VideoSortOption,
    currentOption: VideoSortOption,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = if (option == currentOption) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        trailingIcon = {
            if (option == currentOption) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick
    )
}
