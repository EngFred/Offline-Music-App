package com.engfred.musicplayer.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.engfred.musicplayer.feature_dj_mix.presentation.screens.MixStudioLauncherScreen
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
                MixStudioLauncherScreen(
                    onPlaylistSelected = onDjMixPlaylistSelected,
                )
            }
            composable(AppDestinations.BottomNavItem.Settings.baseRoute) {
                SettingsScreen()
            }
        }
    }

}