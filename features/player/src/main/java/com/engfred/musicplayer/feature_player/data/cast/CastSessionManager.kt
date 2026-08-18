package com.engfred.musicplayer.feature_player.data.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.engfred.musicplayer.core.data.server.LocalMediaHttpServer
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.CastState
import com.engfred.musicplayer.core.domain.model.VideoFile
import com.engfred.musicplayer.core.domain.repository.RepeatMode
import com.engfred.musicplayer.core.domain.repository.ShuffleMode
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.engfred.musicplayer.core.domain.cast.VideoCastManager
import com.engfred.musicplayer.core.domain.cast.VideoCastPlaybackState
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CastSessionManager"

/**
 * Manages Google Cast session lifecycle, LocalMediaHttpServer coordination,
 * and remote media commands.
 */
@Singleton
class CastSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localMediaServer: LocalMediaHttpServer
) : VideoCastManager {

    private val _castState = MutableStateFlow(CastState.DISCONNECTED)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private val _videoCastPlaybackState = MutableStateFlow(VideoCastPlaybackState())
    override val videoCastPlaybackState: StateFlow<VideoCastPlaybackState> = _videoCastPlaybackState.asStateFlow()

    override val castStateFlow: StateFlow<CastState> = _castState.asStateFlow()

    private val _currentVideoFile = MutableStateFlow<VideoFile?>(null)
    override val currentVideoFile: StateFlow<VideoFile?> = _currentVideoFile.asStateFlow()
    override fun getCurrentVideo(): VideoFile? = _currentVideoFile.value

    private var currentCastSession: CastSession? = null
    private var remoteMediaClient: RemoteMediaClient? = null

    var onSessionConnected: ((CastSession, Long) -> Unit)? = null
    var onSessionDisconnected: ((Long) -> Unit)? = null
    var onRemoteStatusChanged: ((RemoteMediaClient) -> Unit)? = null

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            remoteMediaClient?.let { client ->
                updateVideoCastState()
                onRemoteStatusChanged?.invoke(client)
            }
        }

        override fun onQueueStatusUpdated() {
            remoteMediaClient?.let { client ->
                updateVideoCastState()
                onRemoteStatusChanged?.invoke(client)
            }
        }

        override fun onMetadataUpdated() {
            remoteMediaClient?.let { client ->
                updateVideoCastState()
                onRemoteStatusChanged?.invoke(client)
            }
        }

        override fun onPreloadStatusUpdated() {
            remoteMediaClient?.let { client ->
                updateVideoCastState()
                onRemoteStatusChanged?.invoke(client)
            }
        }
    }

    private val progressListener = RemoteMediaClient.ProgressListener { _, _ ->
        remoteMediaClient?.let { client ->
            updateVideoCastState()
            onRemoteStatusChanged?.invoke(client)
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            Log.d(TAG, "Cast session starting")
            _castState.value = CastState.CONNECTING
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d(TAG, "Cast session started: $sessionId")
            handleSessionConnected(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e(TAG, "Cast session start failed with error: $error")
            handleSessionDisconnected()
        }

        override fun onSessionEnding(session: CastSession) {
            Log.d(TAG, "Cast session ending")
            _castState.value = CastState.CONNECTING
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d(TAG, "Cast session ended")
            handleSessionDisconnected()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            Log.d(TAG, "Cast session resuming: $sessionId")
            _castState.value = CastState.CONNECTING
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.d(TAG, "Cast session resumed, wasSuspended=$wasSuspended")
            handleSessionConnected(session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.e(TAG, "Cast session resume failed: $error")
            handleSessionDisconnected()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.d(TAG, "Cast session suspended: $reason")
            _castState.value = CastState.CONNECTING
        }
    }

    init {
        initializeCastContext()
    }

    private fun initializeCastContext() {
        try {
            val castContext = CastContext.getSharedInstance(context)
            castContext.sessionManager.addSessionManagerListener(
                sessionManagerListener,
                CastSession::class.java
            )
            castContext.sessionManager.currentCastSession?.let { existingSession ->
                if (existingSession.isConnected) {
                    handleSessionConnected(existingSession)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast context unavailable: ${e.message}")
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireLocks() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MusicPlayer:CastWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicPlayer:CastWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
            Log.d(TAG, "Acquired WifiLock and WakeLock for active Cast session")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            Log.d(TAG, "Released WifiLock and WakeLock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release locks: ${e.message}")
        }
    }

    private fun handleSessionConnected(session: CastSession) {
        currentCastSession = session
        remoteMediaClient = session.remoteMediaClient
        remoteMediaClient?.registerCallback(remoteMediaClientCallback)
        remoteMediaClient?.addProgressListener(progressListener, 500L)

        acquireLocks()
        localMediaServer.startServer()
        _castState.value = CastState.CONNECTED

        val currentPosition = remoteMediaClient?.approximateStreamPosition ?: 0L
        onSessionConnected?.invoke(session, currentPosition)
    }

    private fun handleSessionDisconnected() {
        val lastPosition = remoteMediaClient?.approximateStreamPosition ?: 0L
        remoteMediaClient?.removeProgressListener(progressListener)
        remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        remoteMediaClient = null
        currentCastSession = null

        releaseLocks()
        localMediaServer.stopServer()
        _castState.value = CastState.DISCONNECTED
        _videoCastPlaybackState.value = VideoCastPlaybackState()
        _currentVideoFile.value = null

        onSessionDisconnected?.invoke(lastPosition)
    }

    override fun isConnected(): Boolean = _castState.value == CastState.CONNECTED

    override fun isCurrentMediaVideo(): Boolean {
        if (_currentVideoFile.value != null) return true
        val client = remoteMediaClient ?: return false
        val mediaInfo = client.mediaInfo ?: client.mediaStatus?.mediaInfo ?: return false
        val type = mediaInfo.metadata?.mediaType
        return type == MediaMetadata.MEDIA_TYPE_MOVIE || mediaInfo.contentType?.startsWith("video/") == true
    }

    private fun updateVideoCastState() {
        val client = remoteMediaClient
        if (client != null && isCurrentMediaVideo()) {
            val isPlaying = client.isPlaying
            val isBuffering = client.isBuffering
            val playerState = client.playerState
            val idleReason = client.idleReason
            val isEnded = playerState == MediaStatus.PLAYER_STATE_IDLE && idleReason == MediaStatus.IDLE_REASON_FINISHED
            val pos = client.approximateStreamPosition.coerceAtLeast(0L)
            val dur = (client.mediaInfo?.streamDuration ?: client.streamDuration).coerceAtLeast(0L)
            _videoCastPlaybackState.update {
                it.copy(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isEnded = isEnded,
                    currentPositionMs = if (isEnded) dur else pos,
                    durationMs = dur
                )
            }
        }
    }

    fun getRemoteClient(): RemoteMediaClient? = remoteMediaClient

    fun getRemotePositionMs(): Long {
        return remoteMediaClient?.approximateStreamPosition ?: 0L
    }

    fun getRemoteDurationMs(): Long {
        return remoteMediaClient?.streamDuration ?: 0L
    }

    fun isRemotePlaying(): Boolean {
        return remoteMediaClient?.isPlaying ?: false
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun runOnMainThread(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * Loads and starts playing a single audio file on the Cast receiver.
     */
    fun loadMedia(audioFile: AudioFile, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        _currentVideoFile.value = null
        _videoCastPlaybackState.value = VideoCastPlaybackState()
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            val mediaInfo = buildMediaInfo(audioFile)

            val loadOptions = com.google.android.gms.cast.MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setCurrentTime(startPositionMs.coerceAtLeast(0L))
                .setAutoplay(autoPlay)
                .build()

            client.load(loadOptions)
        }
    }

    /**
     * Loads the entire queue onto the Cast receiver starting at [startIndex].
     */
    fun loadQueue(
        queue: List<AudioFile>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        repeatMode: RepeatMode = RepeatMode.OFF
    ) {
        _currentVideoFile.value = null
        _videoCastPlaybackState.value = VideoCastPlaybackState()
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            if (queue.isEmpty()) return@runOnMainThread

            val items = queue.map { file ->
                MediaQueueItem.Builder(buildMediaInfo(file))
                    .setAutoplay(true)
                    .build()
            }.toTypedArray()

            val validIndex = startIndex.coerceIn(0, queue.size - 1)
            val castRepeatMode = when (repeatMode) {
                RepeatMode.OFF -> MediaStatus.REPEAT_MODE_REPEAT_OFF
                RepeatMode.ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
                RepeatMode.ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
            }

            client.queueLoad(
                items,
                validIndex,
                castRepeatMode,
                startPositionMs.coerceAtLeast(0L),
                null
            )
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            val castRepeatMode = when (mode) {
                RepeatMode.OFF -> MediaStatus.REPEAT_MODE_REPEAT_OFF
                RepeatMode.ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
                RepeatMode.ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
            }
            client.queueSetRepeatMode(castRepeatMode, null)?.setResultCallback {
                client.requestStatus()
            }
        }
    }

    fun setShuffleMode(mode: ShuffleMode, queue: List<AudioFile>, currentSong: AudioFile?) {
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            val mediaStatus = client.mediaStatus
            val currentItemId = mediaStatus?.currentItemId ?: MediaQueueItem.INVALID_ITEM_ID
            val queueItems = mediaStatus?.queueItems

            if (queueItems != null && queueItems.isNotEmpty() && currentItemId != MediaQueueItem.INVALID_ITEM_ID) {
                val upcomingItemIds = queueItems.map { it.itemId }.filter { it != currentItemId }
                if (upcomingItemIds.isNotEmpty()) {
                    val targetOrder = if (mode == ShuffleMode.ON) {
                        upcomingItemIds.shuffled()
                    } else {
                        val idToQueueIndex = queue.mapIndexed { index, audio -> audio.id.toString() to index }.toMap()
                        upcomingItemIds.sortedBy { itemId ->
                            val item = queueItems.find { it.itemId == itemId }
                            val audioId = item?.media?.contentId?.substringAfter("/media/")?.substringBefore("?")
                            idToQueueIndex[audioId] ?: itemId
                        }
                    }
                    client.queueReorderItems(targetOrder.toIntArray(), MediaQueueItem.INVALID_ITEM_ID, null)?.setResultCallback {
                        client.requestStatus()
                    }
                    return@runOnMainThread
                }
            }

            val targetRepeatMode = if (mode == ShuffleMode.ON) {
                MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE
            } else {
                MediaStatus.REPEAT_MODE_REPEAT_OFF
            }
            client.queueSetRepeatMode(targetRepeatMode, null)?.setResultCallback {
                client.requestStatus()
            }
        }
    }

    fun addAudioToQueueNext(audioFile: AudioFile) {
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            val mediaStatus = client.mediaStatus
            val currentItemId = mediaStatus?.currentItemId ?: MediaQueueItem.INVALID_ITEM_ID
            val queueItems = mediaStatus?.queueItems ?: emptyList()

            val item = MediaQueueItem.Builder(buildMediaInfo(audioFile))
                .setAutoplay(true)
                .build()

            if (currentItemId != MediaQueueItem.INVALID_ITEM_ID && queueItems.isNotEmpty()) {
                val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }
                val insertBeforeItemId = if (currentIndex != -1 && currentIndex + 1 < queueItems.size) {
                    queueItems[currentIndex + 1].itemId
                } else {
                    MediaQueueItem.INVALID_ITEM_ID
                }
                client.queueInsertItems(arrayOf(item), insertBeforeItemId, null)?.setResultCallback {
                    client.requestStatus()
                }
            } else {
                client.queueAppendItem(item, null)?.setResultCallback {
                    client.requestStatus()
                }
            }
        }
    }

    override fun play() {
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            if (isCurrentMediaVideo()) {
                val playerState = client.playerState
                val idleReason = client.idleReason
                if (playerState == MediaStatus.PLAYER_STATE_IDLE && idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    val video = _currentVideoFile.value
                    if (video != null) {
                        loadVideo(video, 0L)
                        return@runOnMainThread
                    }
                }
            }
            client.play()
        }
    }

    override fun pause() {
        runOnMainThread {
            remoteMediaClient?.pause()
        }
    }

    override fun togglePlayPause() {
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            if (client.isPlaying) {
                client.pause()
            } else {
                client.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        runOnMainThread {
            val options = MediaSeekOptions.Builder()
                .setPosition(positionMs.coerceAtLeast(0L))
                .build()
            remoteMediaClient?.seek(options)
        }
    }

    fun skipToNext() {
        runOnMainThread {
            remoteMediaClient?.queueNext(null)
        }
    }

    fun skipToPrevious() {
        runOnMainThread {
            remoteMediaClient?.queuePrev(null)
        }
    }

    private fun buildMediaInfo(audioFile: AudioFile): MediaInfo {
        val mimeType = "audio/mpeg"
        val mediaUrl = localMediaServer.registerMedia(
            id = audioFile.id.toString(),
            contentUri = audioFile.uri,
            mimeType = mimeType
        )

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, audioFile.title)
            putString(MediaMetadata.KEY_ARTIST, audioFile.artist ?: "Unknown Artist")
            putString(MediaMetadata.KEY_ALBUM_TITLE, audioFile.album ?: "Unknown Album")

            val albumArtUri = audioFile.albumArtUri
            val artUrl = if (albumArtUri != null) {
                localMediaServer.registerArt(
                    id = audioFile.id.toString(),
                    contentUri = albumArtUri
                )
            } else {
                localMediaServer.getDefaultArtUrl()
            }
            addImage(WebImage(Uri.parse(artUrl)))
        }

        return MediaInfo.Builder(mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(metadata)
            .setStreamDuration(audioFile.duration)
            .build()
    }

    /**
     * Streams a video file to the connected Cast receiver device.
     */
    override fun loadVideo(videoFile: VideoFile, startPositionMs: Long) {
        _currentVideoFile.value = videoFile
        _videoCastPlaybackState.value = VideoCastPlaybackState(
            isPlaying = true,
            isBuffering = true,
            currentPositionMs = startPositionMs
        )
        remoteMediaClient?.let { client ->
            onRemoteStatusChanged?.invoke(client)
        }
        runOnMainThread {
            val client = remoteMediaClient ?: return@runOnMainThread
            val mediaInfo = buildVideoMediaInfo(videoFile)
            val requestData = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(startPositionMs.coerceAtLeast(0L))
                .build()

            client.load(requestData)?.setResultCallback {
                client.requestStatus()
            }
        }
    }

    private fun buildVideoMediaInfo(videoFile: VideoFile): MediaInfo {
        val mimeType = if (videoFile.mimeType.isNotBlank() && videoFile.mimeType != "video/*") {
            videoFile.mimeType
        } else {
            "video/mp4"
        }

        val mediaUrl = localMediaServer.registerMedia(
            id = videoFile.id.toString(),
            contentUri = videoFile.uri,
            mimeType = mimeType
        )

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, videoFile.title)
            val folder = videoFile.folderName
            if (!folder.isNullOrBlank()) {
                putString(MediaMetadata.KEY_SUBTITLE, folder)
            }
            val artUrl = localMediaServer.getDefaultArtUrl()
            addImage(WebImage(Uri.parse(artUrl)))
        }

        return MediaInfo.Builder(mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(metadata)
            .setStreamDuration(videoFile.duration)
            .build()
    }
}
