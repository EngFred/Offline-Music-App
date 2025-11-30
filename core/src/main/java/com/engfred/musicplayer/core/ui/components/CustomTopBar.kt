package com.engfred.musicplayer.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A custom top bar with a FIXED height to prevent UI shifting.
 * If a subtitle is present, the title moves up to make room.
 * If no subtitle, the title centers vertically.
 */
@Composable
fun CustomTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    showNavigationIcon: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // Standard Material 3 Medium/Large top bar height or a custom fixed size.
    // 64.dp is usually enough for Title + small Subtitle.
    val fixedBarHeight = 64.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fixedBarHeight) // <--- FIXED HEIGHT
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, // Center the Row contents
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (showNavigationIcon) {
                IconButton(
                    onClick = onNavigateBack ?: {},
                    enabled = onNavigateBack != null
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(12.dp)) // Add a little start padding if no icon
            }

            Column(
                horizontalAlignment = Alignment.Start,
                // This centers the block vertically.
                // If subtitle is gone, Title is centered.
                // If subtitle exists, the whole block is centered.
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight() // Ensure column takes full height to allow centering
                    .padding(start = if (showNavigationIcon) 0.dp else 4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // We keep the fade animation for the text itself, but not the container height
                AnimatedVisibility(
                    visible = subtitle != null,
                    enter = fadeIn(animationSpec = tween(300)) + slideInVertically { it / 2 },
                    exit = fadeOut(animationSpec = tween(300)) + slideOutVertically { it / 2 }
                ) {
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 0.dp)
                        )
                    }
                }
            }

            // Actions slot
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}