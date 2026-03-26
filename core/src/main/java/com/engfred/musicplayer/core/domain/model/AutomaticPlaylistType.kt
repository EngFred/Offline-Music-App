package com.engfred.musicplayer.core.domain.model

enum class AutomaticPlaylistType {
    RECENTLY_ADDED,
    MOST_PLAYED,
    ARTIST,
    MIX_OF_THE_DAY;

    companion object {
        /** Reserved playlist ID for the daily mix. Negative = automatic, -999 = mix. */
        const val MIX_OF_THE_DAY_PLAYLIST_ID = -999L
    }
}