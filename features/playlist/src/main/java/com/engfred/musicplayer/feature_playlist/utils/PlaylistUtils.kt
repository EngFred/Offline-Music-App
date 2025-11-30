package com.engfred.musicplayer.feature_playlist.utils

import android.net.Uri
import com.engfred.musicplayer.core.domain.model.Playlist

fun Playlist.findFirstAlbumArtUri(): Uri? {
    return songs.firstOrNull { it.albumArtUri != null }?.albumArtUri
}