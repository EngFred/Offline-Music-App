package com.engfred.musicplayer.feature_playlist.presentation.components.detail
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.AudioFileItem
@Composable
fun PlaylistSongs(
    songs: List<AudioFile>,
    currentPlayingId: Long?,
    isAudioPlaying: Boolean,
    isFromAutomaticPlaylist: Boolean,
    onSongClick: (AudioFile) -> Unit,
    onSongRemove: (AudioFile) -> Unit,
    onAddToPlaylist: (AudioFile) -> Unit,
    onPlayNext: (AudioFile) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    playCountMap: Map<Long, Int>? = null,
    onEditInfo: (AudioFile) -> Unit,
    onTrimAudio: (AudioFile) -> Unit,
    isSelectionMode: Boolean = false,
    selectedSongs: Set<AudioFile> = emptySet(),
    onToggleSelection: (AudioFile) -> Unit = {},
    onLongPress: (AudioFile) -> Unit = {}
) {
    AnimatedVisibility(
        visible = songs.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = songs,
                key = { it.id }
            ) { audioFile ->
                val isSelected = selectedSongs.contains(audioFile)
                AudioFileItem(
                    audioFile = audioFile,
                    isCurrentPlayingAudio = (audioFile.id == currentPlayingId),
                    onRemoveOrDelete = onSongRemove,
                    modifier = Modifier.animateItem(),
                    isAudioPlaying = isAudioPlaying,
                    onAddToPlaylist = onAddToPlaylist,
                    onPlayNext = onPlayNext,
                    isFromAutomaticPlaylist = isFromAutomaticPlaylist,
                    playCount = playCountMap?.get(audioFile.id),
                    onEditInfo = onEditInfo,
                    onTrimAudio = onTrimAudio,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onToggleSelect = { onToggleSelection(audioFile) },
                    onItemTap = { onSongClick(audioFile) },
                    onItemLongPress = { onLongPress(audioFile) }
                )
            }
        }
    }
}