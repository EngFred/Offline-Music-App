package com.engfred.musicplayer.feature_player.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.core.domain.model.AudioFile
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    playingQueue: List<AudioFile>,
    onPlayQueueItem: (AudioFile) -> Unit,
    onRemoveQueueItem: (AudioFile) -> Unit,
    playingAudio: AudioFile?,
    isPlaying: Boolean
) {
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val currentSongIndex = remember(playingAudio, playingQueue) {
        if (playingAudio == null || playingQueue.isEmpty()) {
            -1
        } else {
            playingQueue.indexOfFirst { it.id == playingAudio.id }
        }
    }

    LaunchedEffect(playingAudio, currentSongIndex, playingQueue.size) {
        if (currentSongIndex != -1 && playingQueue.isNotEmpty()) {
            if (currentSongIndex >= 0 && currentSongIndex < playingQueue.size) {
                coroutineScope.launch {
                    val itemHeightPx = with(density) { 64.dp.toPx() }

                    val viewportHeightPx = lazyListState.layoutInfo.viewportSize.height

                    val offsetPx = (viewportHeightPx / 2f - itemHeightPx / 2f).toInt()

                    lazyListState.scrollToItem(
                        index = currentSongIndex,
                        scrollOffset = -offsetPx
                    )
                }
            }
        }
    }


    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(48.dp))
                Text(
                    text = "Playing Queue",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close Queue",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (playingQueue.isEmpty()) {
                Text(
                    text = "Queue is empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState
                ) {
                    itemsIndexed(playingQueue){ index, audioFile ->
                        QueueItem(
                            audioFile = audioFile,
                            isCurrentlyPlaying = audioFile.id == playingAudio?.id,
                            onPlayClick = { onPlayQueueItem(audioFile) },
                            onRemoveClick = { onRemoveQueueItem(audioFile) },
                            isLastItem = index == playingQueue.lastIndex,
                            isPlaying = isPlaying
                        )

                    }
                }
            }
        }
    }
}