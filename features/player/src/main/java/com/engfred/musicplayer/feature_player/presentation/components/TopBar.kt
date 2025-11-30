package com.engfred.musicplayer.feature_player.presentation.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import android.view.HapticFeedbackConstants
import androidx.compose.ui.semantics.customActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share

@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
    currentSongIndex: Int,
    totalQueueSize: Int,
    onOpenQueue: () -> Unit,
    selectedLayout: PlayerLayout,
    onLayoutSelected: (PlayerLayout) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    dynamicContentColor: Color? = null, // Optional dynamic content color for IMMERSIVE_CANVAS
    onShareAudio: (() -> Unit)? = null
) {
    var showLayoutMenu by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val currentSongI = currentSongIndex + 1
    val currentSongNumText = if(currentSongI > totalQueueSize) "" else currentSongI.toString()

    // Use dynamicContentColor only for IMMERSIVE_CANVAS; otherwise, use LocalContentColor
    val contentColor = when (selectedLayout) {
        PlayerLayout.IMMERSIVE_CANVAS -> dynamicContentColor ?: MaterialTheme.colorScheme.onBackground
        else -> LocalContentColor.current // Use LocalContentColor for ETHEREAL_FLOW and MINIMALIST_GROOVE
    }

    when (selectedLayout) {
        PlayerLayout.MINIMALIST_GROOVE -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Navigate up",
                        tint = contentColor
                    )
                }

                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor.copy(alpha = 0.8f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                            tint = if (isFavorite) Color(0xFFFF5252) else contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { showLayoutMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Change Player Layout",
                            tint = contentColor
                        )
                    }
                    LayoutDropdownMenu(
                        expanded = showLayoutMenu,
                        onDismissRequest = { showLayoutMenu = false },
                        selectedLayout = selectedLayout,
                        onLayoutSelected = onLayoutSelected,
                        onShareAudio = onShareAudio
                    )
                }
            }
        }

        PlayerLayout.IMMERSIVE_CANVAS -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Navigate up",
                        tint = contentColor
                    )
                }

                Spacer(modifier = Modifier.size(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${currentSongNumText}/$totalQueueSize",
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showLayoutMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Change Player Layout",
                            tint = contentColor
                        )
                    }
                    LayoutDropdownMenu(
                        expanded = showLayoutMenu,
                        onDismissRequest = { showLayoutMenu = false },
                        selectedLayout = selectedLayout,
                        onLayoutSelected = onLayoutSelected
                    )
                }
            }
        }

        PlayerLayout.ETHEREAL_FLOW -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Navigate up",
                        tint = contentColor
                    )
                }

                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "${currentSongNumText}/$totalQueueSize",
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor.copy(alpha = 0.8f),
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLandscape.not()) {
                        IconButton(onClick = onOpenQueue) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Open Queue",
                                tint = contentColor
                            )
                        }
                    }

                    IconButton(onClick = { showLayoutMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Change Player Layout",
                            tint = contentColor
                        )
                    }
                    LayoutDropdownMenu(
                        expanded = showLayoutMenu,
                        onDismissRequest = { showLayoutMenu = false },
                        selectedLayout = selectedLayout,
                        onLayoutSelected = onLayoutSelected,
                        onShareAudio
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedLayout: PlayerLayout,
    onLayoutSelected: (PlayerLayout) -> Unit,
    onShareAudio: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp)
            )
            .animateContentSize(animationSpec = tween(durationMillis = 200))
            .alpha(
                animateFloatAsState(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "dropdownFade"
                ).value
            ),
        offset = DpOffset(x = (-8).dp, y = 8.dp)
    ) {
        PlayerLayout.entries.forEachIndexed { index, layout ->
            Box(
                modifier = Modifier
                    .background(
                        if (selectedLayout == layout)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else
                            Color.Transparent
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onLayoutSelected(layout)
                        onDismissRequest()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = when (layout) {
                        PlayerLayout.ETHEREAL_FLOW -> "Ethereal"
                        PlayerLayout.IMMERSIVE_CANVAS -> "Immersive"
                        PlayerLayout.MINIMALIST_GROOVE -> "Minimalist"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selectedLayout == layout) FontWeight.Medium else FontWeight.Normal
                )
            }

            if (index < PlayerLayout.entries.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            }
        }

        // --- Add Share Audio Menu Item for specific layouts ---
        if (selectedLayout == PlayerLayout.MINIMALIST_GROOVE || selectedLayout == PlayerLayout.ETHEREAL_FLOW) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )

            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onShareAudio?.invoke()
                        onDismissRequest()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share Audio",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "Share Audio",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}