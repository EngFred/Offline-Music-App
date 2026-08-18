package com.engfred.musicplayer.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

sealed class AppDestinations(val route: String) {
    data object MainGraph : AppDestinations("main_graph")
    data object NowPlaying : AppDestinations("now_playing")

    data object FindDuplicates : AppDestinations("find_duplicates")

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

    data object VideoPlayer : AppDestinations("video_player?videoId={videoId}&videoUri={videoUri}") {
        fun createRoute(videoId: Long? = null, videoUri: String? = null): String {
            val idParam = videoId ?: -1L
            val uriParam = if (videoUri != null) Uri.encode(videoUri) else ""
            return "video_player?videoId=$idParam&videoUri=$uriParam"
        }
    }

    sealed class BottomNavItem(val baseRoute: String, val icon: ImageVector, val label: String) {
        data object Library   : BottomNavItem("library",          Icons.Rounded.LibraryMusic,          "Library")
        data object Playlists : BottomNavItem("playlists",        Icons.AutoMirrored.Rounded.List,      "Playlists")
        data object Videos    : BottomNavItem("videos",           Icons.Rounded.VideoLibrary,          "Videos")
        data object Settings  : BottomNavItem("settings",         Icons.Rounded.Settings,              "Settings")
    }
}