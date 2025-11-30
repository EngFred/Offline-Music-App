package com.engfred.musicplayer.feature_playlist.presentation.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.engfred.musicplayer.core.domain.model.AutomaticPlaylistType
import com.engfred.musicplayer.core.ui.components.InfoIndicator

@Composable
fun PlaylistEmptyState(
    modifier: Modifier = Modifier,
    playlistType: AutomaticPlaylistType?
) {

    val message = if (playlistType == AutomaticPlaylistType.MOST_PLAYED) {
        "Your most payed tracks of the week will appear here."
    } else if(playlistType == AutomaticPlaylistType.RECENTLY_ADDED) {
        "No recently added songs have been found."
    } else {
        "This playlist is empty. Use the menu above to add songs!"
    }
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        InfoIndicator(
            modifier = modifier
                .fillMaxWidth(),
            message = message,
            icon = Icons.Outlined.LibraryMusic
        )
    }
}