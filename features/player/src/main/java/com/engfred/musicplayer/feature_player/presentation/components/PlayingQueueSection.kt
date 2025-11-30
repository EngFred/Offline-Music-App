package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile

@Composable
fun PlayingQueueSection(
    modifier: Modifier = Modifier,
    playingQueue: List<AudioFile>,
    playingAudio: AudioFile?,
    onPlayItem: (AudioFile) -> Unit,
    onRemoveItem: (AudioFile) -> Unit,
    isCompact: Boolean,
    isPlaying: Boolean
) {

    val currentSongIndex = remember(playingAudio, playingQueue) {
        if (playingAudio == null || playingQueue.isEmpty()) {
            -1
        } else {
            playingQueue.indexOfFirst { it.id == playingAudio.id }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Playing Queue",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = LocalContentColor.current,
            modifier = Modifier.padding(bottom = 10.dp, top = 10.dp , start = 10.dp, end= 10.dp)
        )
        if (playingQueue.isEmpty()) {
            Text(
                text = "Queue is empty.",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 24.dp, start = 8.dp)
            )
        } else {
            val listState = rememberLazyListState()
            LazyColumn(state = listState) {
                itemsIndexed(playingQueue) { index, audioFile ->
                    QueueItem(
                        audioFile = audioFile,
                        isCurrentlyPlaying = index == currentSongIndex,
                        onPlayClick = { onPlayItem(audioFile) },
                        onRemoveClick = { onRemoveItem(audioFile) },
                        showAlbumArt = isCompact,
                        isLastItem = index == playingQueue.lastIndex,
                        isPlaying = isPlaying
                    )
                }
            }
            LaunchedEffect(currentSongIndex) {
                if (currentSongIndex >= 0) {
                    listState.scrollToItem(currentSongIndex)
                }
            }
        }
    }
}