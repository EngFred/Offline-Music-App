package com.engfred.musicplayer.feature_player.presentation.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.engfred.musicplayer.core.domain.model.PlayerLayout

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
    dynamicContentColor: Color? = null,
    onShareAudio: (() -> Unit)? = null,
) {
    var showLayoutMenu by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current

    val currentSongI = currentSongIndex + 1
    val currentSongNumText = if (currentSongI > totalQueueSize) "" else currentSongI.toString()

    val contentColor = when (selectedLayout) {
        PlayerLayout.IMMERSIVE_CANVAS -> dynamicContentColor ?: MaterialTheme.colorScheme.onBackground
        else -> LocalContentColor.current
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
                    color = contentColor.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CastMediaRouteButton(tintColor = contentColor)
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                            tint = if (isFavorite) Color(0xFFFF5252) else contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Box {
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
        }

        PlayerLayout.IMMERSIVE_CANVAS -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateUp) {
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
                        color = contentColor.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CastMediaRouteButton(tintColor = contentColor)
                    Box {
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
                    color = contentColor.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CastMediaRouteButton(tintColor = contentColor)
                    if (!isLandscape) {
                        IconButton(onClick = onOpenQueue) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Open Queue",
                                tint = contentColor
                            )
                        }
                    }

                    Box {
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
        }
    }
}

@Composable
fun CastingStatusPill(
    visible: Boolean,
    deviceName: String?,
    contentColor: Color,
    style: CastingStatusStyle = CastingStatusStyle.ETHEREAL,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val destination = deviceName?.takeIf { it.isNotBlank() } ?: "TV"
    val shape = when (style) {
        CastingStatusStyle.MINIMALIST -> RoundedCornerShape(50)
        CastingStatusStyle.ETHEREAL -> RoundedCornerShape(18.dp)
        CastingStatusStyle.IMMERSIVE -> RoundedCornerShape(50)
    }
    val surfaceColor = when (style) {
        CastingStatusStyle.MINIMALIST -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
        CastingStatusStyle.ETHEREAL -> MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)
        CastingStatusStyle.IMMERSIVE -> Color.Black.copy(alpha = 0.44f)
    }
    val borderColor = when (style) {
        CastingStatusStyle.MINIMALIST -> MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        CastingStatusStyle.ETHEREAL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        CastingStatusStyle.IMMERSIVE -> Color.White.copy(alpha = 0.24f)
    }
    val iconColor = when (style) {
        CastingStatusStyle.MINIMALIST -> MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
        CastingStatusStyle.ETHEREAL -> MaterialTheme.colorScheme.primary
        CastingStatusStyle.IMMERSIVE -> Color.White.copy(alpha = 0.88f)
    }
    val labelColor = when (style) {
        CastingStatusStyle.MINIMALIST -> contentColor.copy(alpha = 0.78f)
        CastingStatusStyle.ETHEREAL -> contentColor.copy(alpha = 0.9f)
        CastingStatusStyle.IMMERSIVE -> Color.White.copy(alpha = 0.92f)
    }
    Surface(
        modifier = modifier,
        color = surfaceColor,
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = when (style) {
                    CastingStatusStyle.MINIMALIST -> 12.dp
                    CastingStatusStyle.ETHEREAL -> 10.dp
                    CastingStatusStyle.IMMERSIVE -> 12.dp
                },
                vertical = when (style) {
                    CastingStatusStyle.MINIMALIST -> 5.dp
                    CastingStatusStyle.ETHEREAL -> 6.dp
                    CastingStatusStyle.IMMERSIVE -> 6.dp
                }
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (style == CastingStatusStyle.MINIMALIST) 6.dp else 5.dp
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.CastConnected,
                contentDescription = "Casting to $destination",
                tint = iconColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Casting to $destination",
                color = labelColor,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

enum class CastingStatusStyle {
    MINIMALIST,
    ETHEREAL,
    IMMERSIVE
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
        properties = PopupProperties(focusable = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .width(200.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                text = "Player Style",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
            thickness = 1.dp
        )

        PlayerLayout.entries.forEach { layout ->
            val isSelected = selectedLayout == layout
            val icon = when (layout) {
                PlayerLayout.ETHEREAL_FLOW -> Icons.Rounded.AutoAwesome
                PlayerLayout.IMMERSIVE_CANVAS -> Icons.Rounded.Wallpaper
                PlayerLayout.MINIMALIST_GROOVE -> Icons.Rounded.Album
            }
            val label = when (layout) {
                PlayerLayout.ETHEREAL_FLOW -> "Ethereal"
                PlayerLayout.IMMERSIVE_CANVAS -> "Immersive"
                PlayerLayout.MINIMALIST_GROOVE -> "Minimalist"
            }

            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.5.sp
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onLayoutSelected(layout)
                    onDismissRequest()
                }
            )
        }

        if (onShareAudio != null) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Share Audio",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.5.sp
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onShareAudio()
                    onDismissRequest()
                }
            )
        }
    }
}
