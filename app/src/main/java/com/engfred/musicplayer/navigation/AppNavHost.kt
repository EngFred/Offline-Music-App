package com.engfred.musicplayer.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_trim.presentation.TrimScreen
import com.engfred.musicplayer.feature_edit.presentation.screen.EditScreen
import com.engfred.musicplayer.feature_player.presentation.screens.NowPlayingScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.CreatePlaylistScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.PlaylistDetailScreen
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail.PlaylistDetailArgs
import com.engfred.musicplayer.ui.MainScreen

/**
 * Defines the main navigation graph for the application.
 */
@UnstableApi
@Composable
fun AppNavHost(
    rootNavController: NavHostController,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrev: () -> Unit,
    playingAudioFile: AudioFile?,
    isPlaying: Boolean,
    context: Context,
    onNavigateToNowPlaying: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    audioItems: List<AudioFile>,
    onReleasePlayer: () -> Unit,
    lastPlaybackAudio: AudioFile?,
    stopAfterCurrent: Boolean,
    onToggleStopAfterCurrent: () -> Unit,
    playbackPositionMs: Long,
    totalDurationMs: Long
) {

    NavHost(
        navController = rootNavController,
        startDestination = AppDestinations.MainGraph.route,
        modifier = Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            )
        )
    ) {

        // Main Graph (with bottom nav)
        composable(AppDestinations.MainGraph.route) {
            MainScreen(
                onNavigateToNowPlaying = onNavigateToNowPlaying,
                onPlaylistClick = { playlistId ->
                    rootNavController.navigate(AppDestinations.PlaylistDetail.createRoute(playlistId))
                },
                onContactDeveloper = {
                    launchWhatsapp(context = context )
                },
                onPlayPause = onPlayPause,
                onPlayNext = onPlayNext,
                onPlayPrev = onPlayPrev,
                isPlaying = isPlaying,
                playingAudioFile = playingAudioFile,
                onEditSong = { audioFile ->
                    rootNavController.navigate(AppDestinations.EditAudioInfo.createRoute(audioFile.id))
                },
                onTrimAudio = { audioFile ->
                    rootNavController.navigate(AppDestinations.TrimAudio.createRoute(audioFile.uri.toString()))
                },
                onPlayAll = onPlayAll,
                onShuffleAll = onShuffleAll,
                audioItems = audioItems,
                onReleasePlayer = onReleasePlayer,
                onCreatePlaylist = {
                    rootNavController.navigate(AppDestinations.CreatePlaylist.route)
                },
                lastPlaybackAudio = lastPlaybackAudio,
                stopAfterCurrent = stopAfterCurrent,
                onToggleStopAfterCurrent = onToggleStopAfterCurrent,
                playbackPositionMs = playbackPositionMs,
                totalDurationMs = totalDurationMs
            )
        }

        // Now playing screen
        composable(
            route = AppDestinations.NowPlaying.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(durationMillis = 400)
                )
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(durationMillis = 400)
                )
            },
            popEnterTransition = {
                slideInVertically(
                    initialOffsetY = { fullHeight -> -fullHeight },
                    animationSpec = tween(durationMillis = 400)
                )
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(durationMillis = 400)
                )
            }
        ) {
            NowPlayingScreen(
                onNavigateUp = {
                    rootNavController.navigateUp()
                }
            )
        }


        // Playlist Detail screen
        composable(
            route = AppDestinations.PlaylistDetail.route,
            arguments = listOf(
                navArgument(PlaylistDetailArgs.PLAYLIST_ID) {
                    type = NavType.LongType
                }
            ),
        ) {
            PlaylistDetailScreen(
                onNavigateBack = { rootNavController.navigateUp() },
                onNavigateToNowPlaying = onNavigateToNowPlaying,
                onEditInfo = {
                    rootNavController.navigate(AppDestinations.EditAudioInfo.createRoute(it.id))
                },
                onTrimAudio = { audioFile ->
                    rootNavController.navigate(AppDestinations.TrimAudio.createRoute(audioFile.uri.toString()))
                },
                stopAfterCurrent = stopAfterCurrent,
                onToggleStopAfterCurrent = onToggleStopAfterCurrent,
                playbackPositionMs = playbackPositionMs,
                totalDurationMs = totalDurationMs
            )
        }

        // Edit Audio Info
        composable(
            route = AppDestinations.EditAudioInfo.route,
            arguments = listOf(navArgument("audioId") { type = NavType.LongType }),
            enterTransition = {
                // If we're coming from NowPlaying -> disable enter animation for EditAudioInfo
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    // Navigate -> EditSong: slide in from right to left
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            },
            exitTransition = {
                // If we're navigating TO NowPlaying -> disable exit animation for EditAudioInfo
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    // Navigate away from EditSong: slide out to the left
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            },
            popEnterTransition = {
                // When popping back to EditSong, if we are coming from NowPlaying skip the enter animation
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    // When popping back to the previous screen, the previous screen should slide in from the left
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            },
            popExitTransition = {
                // When popping from EditSong, if the destination is NowPlaying skip exit animation
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    // Back press from EditSong -> previous: EditSong slides out to the right
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            }
        ) { backStackEntry ->
            val audioId = backStackEntry.arguments?.getLong("audioId") ?: -1L
            EditScreen(
                audioId = audioId,
                onFinish = { rootNavController.navigateUp() },
                onMiniPlayerClick = onNavigateToNowPlaying,
                onMiniPlayPauseClick = onPlayPause,
                onMiniPlayNext = onPlayNext,
                onMiniPlayPrevious = onPlayPrev,
                playingAudioFile = playingAudioFile,
                isPlaying = isPlaying,
                stopAfterCurrent = stopAfterCurrent,
                onToggleStopAfterCurrent = onToggleStopAfterCurrent,
                playbackPositionMs = playbackPositionMs,
                totalDurationMs = totalDurationMs
            )
        }

        // Create Playlist
        composable(
            route = AppDestinations.CreatePlaylist.route,
            enterTransition = {
                // If we're coming from NowPlaying -> disable enter animation for CreatePlaylist
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            },
            exitTransition = {
                // If we're navigating TO NowPlaying -> disable exit animation for CreatePlaylist
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            },
            popEnterTransition = {
                // When popping back to CreatePlaylist, if we are coming from NowPlaying skip the enter animation
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            },
            popExitTransition = {
                // When popping from CreatePlaylist, if the destination is NowPlaying skip exit animation
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) {
                    null
                } else {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400)
                    )
                }
            }
        ) {
            CreatePlaylistScreen(
                onNavigateBack = { rootNavController.navigateUp() },
                onMiniPlayerClick = onNavigateToNowPlaying,
                onMiniPlayPauseClick = onPlayPause,
                onMiniPlayNext = onPlayNext,
                onMiniPlayPrevious = onPlayPrev,
                playingAudioFile = playingAudioFile,
                isPlaying = isPlaying,
                stopAfterCurrent = stopAfterCurrent,
                onToggleStopAfterCurrent = onToggleStopAfterCurrent,
                playbackPositionMs = playbackPositionMs,
                totalDurationMs = totalDurationMs
            )
        }

        // Trim Audio Screen
        composable(
            route = AppDestinations.TrimAudio.route,
            arguments = listOf(navArgument("audioUri") { type = NavType.StringType }),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(durationMillis = 400)
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(durationMillis = 400)
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(durationMillis = 400)
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(durationMillis = 400)
                )
            }
        ) { backStackEntry ->
            val audioUriString = Uri.decode(backStackEntry.arguments?.getString("audioUri") ?: "")
            TrimScreen(
                onNavigateUp = {
                    rootNavController.navigateUp()
                }
            )
        }

    }
}

private fun launchWhatsapp(context: Context) {
    try {
        Toast.makeText(context, "Opening whatsapp...", Toast.LENGTH_SHORT).show()
        val url = "https://wa.me/256754348118"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = url.toUri()
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        //show toast
        Toast.makeText(context, "Error opening whatsapp: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}