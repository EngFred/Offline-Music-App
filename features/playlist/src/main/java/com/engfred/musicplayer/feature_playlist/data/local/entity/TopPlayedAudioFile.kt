package com.engfred.musicplayer.feature_playlist.data.local.entity

/**
 * Data Transfer Object (DTO) for the result of a top played audio files query,
 * containing the audio file ID and its corresponding play count.
 */
data class TopPlayedAudioFileId(
    val audioFileId: Long,
    val playCount: Int
)