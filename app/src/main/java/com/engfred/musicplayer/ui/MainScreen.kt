package com.engfred.musicplayer.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.core.ui.components.MiniPlayer
import com.engfred.musicplayer.core.ui.components.PlayShuffleBar
import com.engfred.musicplayer.core.util.TextUtils.formatCount
import com.engfred.musicplayer.core.util.TextUtils.pluralize
import com.engfred.musicplayer.feature_dj_mix.presentation.screens.DjMixLauncherScreen
import com.engfred.musicplayer.feature_library.presentation.screens.LibraryScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.PlaylistsScreen
import com.engfred.musicplayer.feature_settings.presentation.screens.SettingsScreen
import com.engfred.musicplayer.navigation.AppDestinations
import com.google.accompanist.permissions.ExperimentalPermissionsApi

@OptIn(ExperimentalPermissionsApi::class)
@UnstableApi
@Composable
fun MainScreen(
    onNavigateToNowPlaying: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onContactDeveloper: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrev: () -> Unit,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean,
    onEditSong: (AudioFile) -> Unit,
    onTrimAudio: (AudioFile) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    audioItems: List<AudioFile>,
    onReleasePlayer: () -> Unit,
    onCreatePlaylist: () -> Unit,
    lastPlaybackAudio: AudioFile?,
    stopAfterCurrent: Boolean,
    onToggleStopAfterCurrent: () -> Unit,
    playbackPositionMs: Long,
    totalDurationMs: Long,
    isDjMixActive: Boolean,
    onNavigateToDjMix: () -> Unit,
    onOpenMixOfTheDay: (Long) -> Unit,
    /** Called when the user picks a playlist from the DJ Mix Launcher tab. */
    onDjMixPlaylistSelected: (Long) -> Unit,
) {
    val bottomNavController = rememberNavController()
    val bottomNavItems = listOf(
        AppDestinations.BottomNavItem.Library,
        AppDestinations.BottomNavItem.Playlists,
        AppDestinations.BottomNavItem.DjMix,
        AppDestinations.BottomNavItem.Settings,
    )
    var showDropdownMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permissionHandler = remember { PermissionHandlerUseCase(context) }
    var hasPermission by remember {
        mutableStateOf(
            permissionHandler.hasAudioPermission() && permissionHandler.hasWriteStoragePermission()
        )
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = permissionHandler.hasAudioPermission() &&
                        permissionHandler.hasWriteStoragePermission()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            val isOnLibrary  = currentDestination?.hierarchy?.any { it.route == AppDestinations.BottomNavItem.Library.baseRoute   } == true
            val isOnPlaylist = currentDestination?.hierarchy?.any { it.route == AppDestinations.BottomNavItem.Playlists.baseRoute  } == true
            val isOnDjMix    = currentDestination?.hierarchy?.any { it.route == AppDestinations.BottomNavItem.DjMix.baseRoute      } == true
            val isOnSettings = currentDestination?.hierarchy?.any { it.route == AppDestinations.BottomNavItem.Settings.baseRoute   } == true

            val mainTitle = when {
                isOnLibrary  -> AppDestinations.BottomNavItem.Library.label
                isOnPlaylist -> AppDestinations.BottomNavItem.Playlists.label
                isOnDjMix    -> AppDestinations.BottomNavItem.DjMix.label
                isOnSettings -> AppDestinations.BottomNavItem.Settings.label
                else         -> "Music"
            }
            val subtitle = if (audioItems.isNotEmpty() && isOnLibrary) {
                "${formatCount(audioItems.size)} ${pluralize(audioItems.size, "Audio files", "Audio files", showCount = false)}"
            } else null

            Box(modifier = Modifier.statusBarsPadding()) {
                CustomTopBar(
                    modifier = Modifier.padding(start = 10.dp),
                    title = mainTitle,
                    subtitle = subtitle,
                    showNavigationIcon = false,
                    onNavigateBack = null,
//                    actions = {
//                        IconButton(onClick = { showDropdownMenu = true }) {
//                            Icon(Icons.Rounded.MoreVert, contentDescription = "more icon")
//                        }
//                        DropdownMenu(
//                            expanded = showDropdownMenu,
//                            onDismissRequest = { showDropdownMenu = false },
//                            offset = DpOffset(x = (-16).dp, y = 0.dp),
//                            modifier = Modifier
//                                .clip(RoundedCornerShape(12.dp))
//                                .background(MaterialTheme.colorScheme.surface)
//                        ) {
//                            DropdownMenuItem(
//                                text = { Text("Contact Developer", color = MaterialTheme.colorScheme.onSurface) },
//                                onClick = {
//                                    showDropdownMenu = false
//                                    onContactDeveloper()
//                                }
//                            )
//                        }
//                    }
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                if (isDjMixActive) {
                    DjMixBar(
                        onClick = onNavigateToDjMix,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    if (playingAudioFile != null || lastPlaybackAudio != null) {
                        MiniPlayer(
                            onClick = onNavigateToNowPlaying,
                            modifier = Modifier.fillMaxWidth(),
                            onPlayPause = onPlayPause,
                            onPlayNext = onPlayNext,
                            onPlayPrev = onPlayPrev,
                            isPlaying = isPlaying,
                            playingAudioFile = playingAudioFile ?: lastPlaybackAudio,
                            onToggleStopAfterCurrent = onToggleStopAfterCurrent,
                            stopAfterCurrent = stopAfterCurrent,
                            playbackPositionMs = playbackPositionMs,
                            totalDurationMs = totalDurationMs,
                        )
                    } else {
                        if (audioItems.isNotEmpty()) {
                            PlayShuffleBar(
                                onPlayAll = onPlayAll,
                                onShuffleAll = onShuffleAll,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.baseRoute } == true
                        CustomBottomNavItem(
                            item = item,
                            isSelected = selected,
                            onClick = {
                                if (hasPermission || item.baseRoute == AppDestinations.BottomNavItem.Library.baseRoute) {
                                    bottomNavController.navigate(item.baseRoute) {
                                        popUpTo(bottomNavController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Grant storage permission in Library to access this feature.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = bottomNavController,
            startDestination = AppDestinations.BottomNavItem.Library.baseRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(AppDestinations.BottomNavItem.Library.baseRoute) {
                LibraryScreen(
                    onEditSong = onEditSong,
                    onTrimAudio = onTrimAudio,
                    onOpenMixOfTheDay = onOpenMixOfTheDay
                )
            }
            composable(AppDestinations.BottomNavItem.Playlists.baseRoute) {
                PlaylistsScreen(
                    onPlaylistClick = onPlaylistClick,
                    onCreatePlaylist = onCreatePlaylist
                )
            }
            // ── DJ Mix Launcher tab ───────────────────────────────────────────
            composable(AppDestinations.BottomNavItem.DjMix.baseRoute) {
                DjMixLauncherScreen(
                    onPlaylistSelected = onDjMixPlaylistSelected,
                )
            }
            composable(AppDestinations.BottomNavItem.Settings.baseRoute) {
                SettingsScreen()
            }
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Private composables (unchanged from original)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CustomBottomNavItem(
    item: AppDestinations.BottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val animatedBackgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = 300),
        label = "background_color_animation"
    )
    val animatedContentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "content_color_animation"
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(animatedBackgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(animationSpec = tween(durationMillis = 300)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = animatedContentColor,
            modifier = Modifier.size(24.dp)
        )
        if (isSelected) {
            Text(
                text = item.label,
                color = animatedContentColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DjMixBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "DJ Auto-Mix Active",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Text(
            text = "Tap to control",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}