package com.engfred.musicplayer.feature_video.data.repository

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.engfred.musicplayer.core.domain.model.SubtitleTrack
import com.engfred.musicplayer.core.domain.model.SubtitleType
import com.engfred.musicplayer.core.domain.model.VideoPlaybackState
import com.engfred.musicplayer.core.domain.repository.VideoPlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(UnstableApi::class)
class VideoPlaybackControllerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : VideoPlaybackController {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private val _playbackState = MutableStateFlow(VideoPlaybackState())
    override val playbackState: StateFlow<VideoPlaybackState> = _playbackState.asStateFlow()
    override val currentMediaUri: Uri?
        get() = exoPlayer?.currentMediaItem?.localConfiguration?.uri

    private var availableSubtitleTracks: List<SubtitleTrack> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    _playbackState.update { it.copy(isLoading = true, isEnded = false) }
                }
                Player.STATE_READY -> {
                    _playbackState.update {
                        it.copy(
                            isLoading = false,
                            isEnded = false,
                            totalDurationMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
                            bufferedPositionMs = exoPlayer?.bufferedPosition ?: 0L
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    _playbackState.update { it.copy(isPlaying = false, isEnded = true) }
                }
                Player.STATE_IDLE -> {
                    _playbackState.update { it.copy(isLoading = false) }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.update {
                it.copy(
                    isPlaying = isPlaying,
                    currentPositionMs = exoPlayer?.currentPosition ?: 0L,
                    bufferedPositionMs = exoPlayer?.bufferedPosition ?: 0L
                )
            }
            if (isPlaying) {
                startProgressUpdates()
            } else {
                stopProgressUpdates()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateAvailableSubtitleTracks(tracks)
        }
    }

    fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            trackSelector = DefaultTrackSelector(context)
            exoPlayer = ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector!!)
                .build()
                .apply {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build()
                    setAudioAttributes(audioAttributes, true)
                    addListener(playerListener)
                }
        }
        return exoPlayer!!
    }

    override fun prepare(uri: Uri, startPositionMs: Long, playWhenReady: Boolean) {
        val player = getPlayer()
        
        // Build media item with external subtitles if available
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        
        // Add external subtitle configurations from available tracks
        val subtitleConfigurations = availableSubtitleTracks
            .filter { it.type == SubtitleType.EXTERNAL && it.uri != null }
            .map { subtitle ->
                val mimeType = subtitle.mimeType ?: MimeTypes.APPLICATION_SUBRIP
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri!!))
                    .setMimeType(mimeType)
                    .setLanguage(subtitle.language ?: "und")
                    .setId(subtitle.id)
                    .build()
            }
        
        if (subtitleConfigurations.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }
        
        val mediaItem = mediaItemBuilder.build()
        player.setMediaItem(mediaItem)
        
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.prepare()
        player.playWhenReady = playWhenReady
        _playbackState.update {
            it.copy(
                isLoading = true,
                currentPositionMs = startPositionMs,
                error = null,
                isEnded = false,
                availableSubtitleTracks = emptyList(), // Will be populated when tracks are loaded
                selectedSubtitleTrackId = null,
                currentSubtitleText = ""
            )
        }
    }

    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_playbackState.value.isEnded) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _playbackState.update { it.copy(currentPositionMs = positionMs) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    override fun release() {
        stopProgressUpdates()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
        availableSubtitleTracks = emptyList()
        _playbackState.update { VideoPlaybackState() }
    }

    override fun setSubtitleTracks(tracks: List<SubtitleTrack>) {
        availableSubtitleTracks = tracks
        _playbackState.update { it.copy(availableSubtitleTracks = tracks) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        val player = exoPlayer ?: return
        val selector = trackSelector ?: return
        
        if (trackId == null) {
            // Disable subtitles
            val parameters = selector.parameters
                .buildUpon()
                .setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT))
                .build()
            selector.setParameters(parameters)
            _playbackState.update { it.copy(selectedSubtitleTrackId = null) }
        } else {
            // Enable and select specific subtitle track
            val track = availableSubtitleTracks.find { it.id == trackId }
            if (track != null) {
                if (track.type == SubtitleType.EXTERNAL) {
                    // For external subtitles, we need to rebuild the media item
                    // This is handled by the caller reloading the media
                    _playbackState.update { it.copy(selectedSubtitleTrackId = trackId) }
                } else {
                    // For embedded subtitles, use track selection
                    val tracks = player.currentTracks
                    val textGroup = tracks.groups.find { it.type == C.TRACK_TYPE_TEXT }
                    
                    if (textGroup != null) {
                        // Parse the ID format "groupIndex-trackIndex"
                        val idParts = trackId.split("-")
                        if (idParts.size == 2) {
                            val groupIndex = idParts[0].toIntOrNull()
                            val trackIndex = idParts[1].toIntOrNull()
                            
                            if (groupIndex != null && trackIndex != null && trackIndex < textGroup.length) {
                                val trackOverride = TrackSelectionOverride(
                                    textGroup.mediaTrackGroup,
                                    trackIndex
                                )
                                val parameters = selector.parameters
                                    .buildUpon()
                                    .setOverrideForType(trackOverride)
                                    .build()
                                selector.setParameters(parameters)
                                _playbackState.update { it.copy(selectedSubtitleTrackId = trackId) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getAvailableSubtitleTracks(): List<SubtitleTrack> {
        return availableSubtitleTracks
    }

    private fun updateAvailableSubtitleTracks(tracks: Tracks) {
        val subtitleTracks = mutableListOf<SubtitleTrack>()
        
        // Add embedded subtitle tracks
        tracks.groups.forEachIndexed { groupIndex, trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val track = SubtitleTrack(
                        id = "$groupIndex-$i", // Use group and track index as ID
                        label = format.label ?: "Track ${i + 1}",
                        language = format.language,
                        type = SubtitleType.EMBEDDED,
                        mimeType = format.sampleMimeType,
                        uri = null,
                        isSelected = false
                    )
                    subtitleTracks.add(track)
                }
            }
        }
        
        // Add external subtitle tracks if not already present
        availableSubtitleTracks.forEach { externalTrack ->
            if (externalTrack.type == SubtitleType.EXTERNAL && 
                subtitleTracks.none { it.id == externalTrack.id }) {
                subtitleTracks.add(externalTrack)
            }
        }
        
        availableSubtitleTracks = subtitleTracks
        _playbackState.update { it.copy(availableSubtitleTracks = subtitleTracks) }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    _playbackState.update {
                        it.copy(
                            currentPositionMs = player.currentPosition,
                            bufferedPositionMs = player.bufferedPosition,
                            totalDurationMs = player.duration.coerceAtLeast(0L)
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }
}
