package com.engfred.musicplayer.feature_library.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.engfred.musicplayer.core.domain.model.FilterOption
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search

@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit = {},
    currentFilter: FilterOption,
    onFilterSelected: (FilterOption) -> Unit,
    placeholder: String
) {
    var showFilterMenu by remember { mutableStateOf(false) }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .heightIn(min = 48.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Search icon"
            )
        },
        trailingIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = "Clear search"
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = "Filter songs",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                        properties = PopupProperties(focusable = true),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        // Local composable helper
                        @Composable
                        fun DropdownMenuItemRow(
                            label: String,
                            option: FilterOption,
                            onClickAction: () -> Unit
                        ) {
                            val isSelected = option == currentFilter
                            val dotColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Label on the left (emphasize selected with bold + primary color)
                                        Text(
                                            text = label,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 15.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        // We keep the label emphasis and leading radio-dot as the selection affordance
                                    }
                                },
                                onClick = {
                                    onClickAction()
                                    showFilterMenu = false
                                }
                            )
                        }

                        DropdownMenuItemRow("Newest First", FilterOption.DATE_ADDED_DESC) {
                            onFilterSelected(FilterOption.DATE_ADDED_DESC)
                        }
                        DropdownMenuItemRow("Oldest First", FilterOption.DATE_ADDED_ASC) {
                            onFilterSelected(FilterOption.DATE_ADDED_ASC)
                        }
                        DropdownMenuItemRow("Shortest First", FilterOption.LENGTH_ASC) {
                            onFilterSelected(FilterOption.LENGTH_ASC)
                        }
                        DropdownMenuItemRow("Longest First", FilterOption.LENGTH_DESC) {
                            onFilterSelected(FilterOption.LENGTH_DESC)
                        }
                        DropdownMenuItemRow("A → Z", FilterOption.ALPHABETICAL_ASC) {
                            onFilterSelected(FilterOption.ALPHABETICAL_ASC)
                        }
                        DropdownMenuItemRow("Z → A", FilterOption.ALPHABETICAL_DESC) {
                            onFilterSelected(FilterOption.ALPHABETICAL_DESC)
                        }
                    }
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(24.dp)
    )
}
