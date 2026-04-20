package com.engfred.musicplayer.navigation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.feature_dj_mix.presentation.screens.MixStudioScreen
import com.engfred.musicplayer.feature_dj_mix.presentation.viewmodel.DjMixArgs
import com.engfred.musicplayer.feature_trim.presentation.TrimScreen
import com.engfred.musicplayer.feature_edit.presentation.screen.EditScreen
import com.engfred.musicplayer.feature_player.presentation.screens.NowPlayingScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.CreatePlaylistScreen
import com.engfred.musicplayer.feature_playlist.presentation.screens.PlaylistDetailScreen
import com.engfred.musicplayer.feature_playlist.presentation.viewmodel.detail.PlaylistDetailArgs
import com.engfred.musicplayer.feature_settings.presentation.screens.DuplicatesScreen
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
    isDjMixActive: Boolean,
    onNavigateToDjMix: () -> Unit,
    // ── driven from MainActivity so that navigateToNowPlayingOnStart ──
    // and the onNavigateToNowPlaying lambda both open the same overlay.
    showNowPlaying: Boolean,
    onShowNowPlaying: (Boolean) -> Unit
) {
    // Intercept the system back button while the overlay is open so it collapses
    // back to the mini player instead of popping the nav back stack.
    BackHandler(enabled = showNowPlaying) {
        onShowNowPlaying(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                )
            )
    ) {
        // ── Main nav graph (NowPlaying is NO LONGER a destination here) ──────
        NavHost(
            navController = rootNavController,
            startDestination = AppDestinations.MainGraph.route,
        ) {
            composable(AppDestinations.MainGraph.route) {
                MainScreen(
                    // Wire up to the overlay state instead of navigating
                    onNavigateToNowPlaying = { onShowNowPlaying(true) },
                    onPlaylistClick = { playlistId ->
                        rootNavController.navigate(AppDestinations.PlaylistDetail.createRoute(playlistId))
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
                    totalDurationMs = totalDurationMs,
                    isDjMixActive = isDjMixActive,
                    onNavigateToDjMix = onNavigateToDjMix,
                    onOpenMixOfTheDay = { playlistId ->
                        rootNavController.navigate(AppDestinations.DjMix.createRoute(playlistId))
                    },
                    onDjMixPlaylistSelected = { playlistId ->
                        rootNavController.navigate(AppDestinations.DjMix.createRoute(playlistId))
                    },
                    onNavigateToDuplicates = {
                        rootNavController.navigate(AppDestinations.FindDuplicates.route)
                    }
                )
            }

            composable(
                route = AppDestinations.FindDuplicates.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400)) }
            ) {
                DuplicatesScreen(onNavigateUp = { rootNavController.navigateUp() })
            }

            // ── NowPlaying is intentionally REMOVED from here. ───────────────
            // It now lives as a full-screen overlay below the NavHost so that
            // the expand-from-mini-player animation is possible. If you ever
            // deep-link into NowPlaying via a notification etc., call
            // onShowNowPlaying(true) instead of navigating.

            composable(
                route = AppDestinations.PlaylistDetail.route,
                arguments = listOf(
                    navArgument(PlaylistDetailArgs.PLAYLIST_ID) { type = NavType.LongType }
                )
            ) {
                PlaylistDetailScreen(
                    onNavigateBack = { rootNavController.navigateUp() },
                    onNavigateToNowPlaying = { onShowNowPlaying(true) },
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
                    onDjMixClick = { playlistId ->
                        rootNavController.navigate(AppDestinations.DjMix.createRoute(playlistId))
                    }
                )
            }

            composable(
                route = AppDestinations.DjMix.route,
                arguments = listOf(
                    navArgument(DjMixArgs.PLAYLIST_ID) { type = NavType.LongType }
                ),
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400)) }
            ) {
                MixStudioScreen(onNavigateBack = { rootNavController.navigateUp() })
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
                    onMiniPlayerClick = { onShowNowPlaying(true) },
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
                    onMiniPlayerClick = { onShowNowPlaying(true) },
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
                enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400)) },
                popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400)) },
                popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400)) }
            ) {
                TrimScreen(onNavigateUp = { rootNavController.navigateUp() })
            }
        }

        // ── NowPlaying full-screen overlay ────────────────────────────────────
        //
        // Lives OUTSIDE the NavHost so we control the animation ourselves.
        //
        // Enter: expandVertically from Alignment.Bottom — the composable grows
        //   upward from the bottom of the screen (where the mini player lives),
        //   giving the impression that the mini player card is expanding.
        //
        // Exit: shrinkVertically back toward Alignment.Bottom — reverses the
        //   effect, collapsing the screen back down to the mini player position.
        //
        // The spring for enter has a tiny bounce to feel physical. Exit uses
        // DampingRatioNoBouncy so it snaps cleanly without overshooting.
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness    = Spring.StiffnessMedium
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 50)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness    = Spring.StiffnessMediumLow
                )
            ) + fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            // NowPlayingScreen is no longer inside a NavBackStackEntry, so
            // hiltViewModel() here scopes the ViewModel to the Activity —
            // which is correct; there is only ever one "now playing" session.
            NowPlayingScreen(onNavigateUp = { onShowNowPlaying(false) })
        }
    }
}