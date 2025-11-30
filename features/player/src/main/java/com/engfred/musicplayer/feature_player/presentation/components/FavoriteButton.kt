package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.PlayerLayout

/**
 * A dedicated Composable for a toggleable favorite button with a subtle scale animation.
 *
 * @param isFavorite Boolean indicating if the item is currently marked as favorite.
 * @param onToggleFavorite Callback to be invoked when the favorite status is toggled.
 * @param playerLayout The current player layout to determine button size.
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    playerLayout: PlayerLayout
) {
    val favoriteScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "favoriteScale"
    )

    // Determine size based on player layout
    val buttonSize = when (playerLayout) {
        PlayerLayout.IMMERSIVE_CANVAS, PlayerLayout.ETHEREAL_FLOW -> 60.dp // Larger for ImmersiveCanvas and EtherealFlow
        PlayerLayout.MINIMALIST_GROOVE -> 50.dp // Original size for MinimalistGroove
    }
    val iconSize = when (playerLayout) {
        PlayerLayout.IMMERSIVE_CANVAS, PlayerLayout.ETHEREAL_FLOW -> 32.dp // Larger icon for visibility
        PlayerLayout.MINIMALIST_GROOVE -> 24.dp // Original icon size
    }

    IconButton(
        onClick = onToggleFavorite,
        modifier = Modifier.size(buttonSize) // Touch target size
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
            tint = if (isFavorite) Color(0xFFE91E63) else LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = favoriteScale
                    scaleY = favoriteScale
                }
        )
    }
}