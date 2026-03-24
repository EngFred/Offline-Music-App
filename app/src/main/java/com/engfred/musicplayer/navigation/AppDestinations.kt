package com.engfred.musicplayer.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class AppDestinations(val route: String) {
    data object MainGraph : AppDestinations("main_graph")
    data object NowPlaying : AppDestinations("now_playing")

    data object TrimAudio : AppDestinations("trim/{audioUri}") {
        fun createRoute(audioUri: String) = "trim/${Uri.encode(audioUri)}"
    }

    data object PlaylistDetail : AppDestinations("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist_detail/$playlistId"
    }

    data object EditAudioInfo : AppDestinations("edit_song/{audioId}") {
        fun createRoute(audioId: Long) = "edit_song/$audioId"
    }

    data object CreatePlaylist : AppDestinations("create_playlist")

    /** DJ Mix screen — entered from PlaylistDetailScreen for user-created playlists. */
    data object DjMix : AppDestinations("dj_mix/{playlistId}") {
        fun createRoute(playlistId: Long) = "dj_mix/$playlistId"
    }

    sealed class BottomNavItem(val baseRoute: String, val icon: ImageVector, val label: String) {
        data object Library : BottomNavItem("library", Icons.Rounded.LibraryMusic, "Library")
        data object Playlists : BottomNavItem("playlists", Icons.AutoMirrored.Rounded.List, "Playlists")
        data object Settings : BottomNavItem("settings", Icons.Rounded.Settings, "Settings")
    }
}