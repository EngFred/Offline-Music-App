package com.engfred.musicplayer.navigation

import android.content.Context
import android.content.Intent
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
import com.engfred.musicplayer.feature_dj_mix.presentation.screens.DjMixScreen
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixArgs
import com.engfred.musicplayer.feature_trim.presentation.TrimScreen
import com.engfred.musicplayer.feature_edit.presentation.screen.EditScreen
import com.engfred.musicplayer.feature_player.presentation.screens.NowPlayingScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.CreatePlaylistScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.PlaylistDetailScreen
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail.PlaylistDetailArgs
import com.engfred.musicplayer.ui.MainScreen

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
    totalDurationMs: Long,
    // NEW parameters:
    isDjMixActive: Boolean,
    onNavigateToDjMix: () -> Unit
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
        composable(AppDestinations.MainGraph.route) {
            MainScreen(
                onNavigateToNowPlaying = onNavigateToNowPlaying,
                onPlaylistClick = { playlistId ->
                    rootNavController.navigate(AppDestinations.PlaylistDetail.createRoute(playlistId))
                },
                onContactDeveloper = { launchWhatsapp(context = context) },
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
                totalDurationMs = totalDurationMs,
                isDjMixActive = isDjMixActive,
                onNavigateToDjMix = onNavigateToDjMix
            )
        }

        composable(
            route = AppDestinations.NowPlaying.route,
            enterTransition = {
                slideInVertically(initialOffsetY = { it }, animationSpec = tween(400))
            },
            exitTransition = {
                slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInVertically(initialOffsetY = { -it }, animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
            }
        ) {
            NowPlayingScreen(onNavigateUp = { rootNavController.navigateUp() })
        }

        // ── Playlist Detail ── (CHANGED: added onDjMixClick) ────────────────
        composable(
            route = AppDestinations.PlaylistDetail.route,
            arguments = listOf(
                navArgument(PlaylistDetailArgs.PLAYLIST_ID) { type = NavType.LongType }
            )
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
                totalDurationMs = totalDurationMs,
                // NEW ↓
                onDjMixClick = { playlistId ->
                    rootNavController.navigate(AppDestinations.DjMix.createRoute(playlistId))
                }
            )
        }

        // ── DJ Mix ── (NEW) ──────────────────────────────────────────────────
        composable(
            route = AppDestinations.DjMix.route,
            arguments = listOf(
                navArgument(DjMixArgs.PLAYLIST_ID) { type = NavType.LongType }
            ),
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400))
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400))
            }
        ) {
            DjMixScreen(onNavigateBack = { rootNavController.navigateUp() })
        }

        composable(
            route = AppDestinations.EditAudioInfo.route,
            arguments = listOf(navArgument("audioId") { type = NavType.LongType }),
            enterTransition = {
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) null
                else slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400))
            },
            exitTransition = {
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) null
                else slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400))
            },
            popEnterTransition = {
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) null
                else slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400))
            },
            popExitTransition = {
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) null
                else slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400))
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

        composable(
            route = AppDestinations.CreatePlaylist.route,
            enterTransition = {
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) null
                else slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400))
            },
            exitTransition = {
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) null
                else slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400))
            },
            popEnterTransition = {
                val from = initialState.destination.route ?: ""
                if (from == AppDestinations.NowPlaying.route) null
                else slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400))
            },
            popExitTransition = {
                val to = targetState.destination.route ?: ""
                if (to == AppDestinations.NowPlaying.route) null
                else slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400))
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

        composable(
            route = AppDestinations.TrimAudio.route,
            arguments = listOf(navArgument("audioUri") { type = NavType.StringType }),
            enterTransition = {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400))
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400))
            }
        ) {
            TrimScreen(onNavigateUp = { rootNavController.navigateUp() })
        }
    }
}

private fun launchWhatsapp(context: Context) {
    try {
        Toast.makeText(context, "Opening whatsapp...", Toast.LENGTH_SHORT).show()
        val url = "https://wa.me/256754348118"
        val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Error opening whatsapp: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}