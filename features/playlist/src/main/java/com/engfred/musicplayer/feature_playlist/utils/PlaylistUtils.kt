package com.engfred.musicplayer.feature_playlist.utils

import android.net.Uri
import com.engfred.musicplayer.core.domain.model.Playlist

fun Playlist.findFirstAlbumArtUri(): Uri? {
    // Return custom art if it exists, otherwise fallback to first song
    return this.customArtUri ?: this.songs.firstOrNull { it.albumArtUri != Uri.EMPTY }?.albumArtUri
}