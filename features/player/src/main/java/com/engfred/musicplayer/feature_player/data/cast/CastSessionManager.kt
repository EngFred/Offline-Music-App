package com.engfred.musicplayer.feature_player.data.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.engfred.musicplayer.core.data.server.LocalMediaHttpServer
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.CastState
import com.google.android.gms.cast.MediaInfo
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
) {

    private val _castState = MutableStateFlow(CastState.DISCONNECTED)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private var currentCastSession: CastSession? = null
    private var remoteMediaClient: RemoteMediaClient? = null

    var onSessionConnected: ((CastSession, Long) -> Unit)? = null
    var onSessionDisconnected: ((Long) -> Unit)? = null
    var onRemoteStatusChanged: ((RemoteMediaClient) -> Unit)? = null

    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            remoteMediaClient?.let { client ->
                onRemoteStatusChanged?.invoke(client)
            }
        }

        override fun onQueueStatusUpdated() {
            remoteMediaClient?.let { client ->
                onRemoteStatusChanged?.invoke(client)
            }
        }

        override fun onMetadataUpdated() {
            remoteMediaClient?.let { client ->
                onRemoteStatusChanged?.invoke(client)
            }
        }

        override fun onPreloadStatusUpdated() {
            remoteMediaClient?.let { client ->
                onRemoteStatusChanged?.invoke(client)
            }
        }
    }

    private val progressListener = RemoteMediaClient.ProgressListener { _, _ ->
        remoteMediaClient?.let { client ->
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

        onSessionDisconnected?.invoke(lastPosition)
    }

    fun isConnected(): Boolean = _castState.value == CastState.CONNECTED

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

    /**
     * Loads and starts playing a single audio file on the Cast receiver.
     */
    fun loadMedia(audioFile: AudioFile, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        val client = remoteMediaClient ?: return
        val mediaInfo = buildMediaInfo(audioFile)

        val loadOptions = com.google.android.gms.cast.MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setCurrentTime(startPositionMs.coerceAtLeast(0L))
            .setAutoplay(autoPlay)
            .build()

        client.load(loadOptions)
    }

    /**
     * Loads the entire queue onto the Cast receiver starting at [startIndex].
     */
    fun loadQueue(
        queue: List<AudioFile>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        repeatAll: Boolean = false
    ) {
        val client = remoteMediaClient ?: return
        if (queue.isEmpty()) return

        val items = queue.map { file ->
            MediaQueueItem.Builder(buildMediaInfo(file))
                .setAutoplay(true)
                .build()
        }.toTypedArray()

        val validIndex = startIndex.coerceIn(0, queue.size - 1)
        val repeatMode = if (repeatAll) {
            MediaStatus.REPEAT_MODE_REPEAT_ALL
        } else {
            MediaStatus.REPEAT_MODE_REPEAT_OFF
        }

        client.queueLoad(
            items,
            validIndex,
            repeatMode,
            startPositionMs.coerceAtLeast(0L),
            null
        )
    }

    fun play() {
        remoteMediaClient?.play()
    }

    fun pause() {
        remoteMediaClient?.pause()
    }

    fun togglePlayPause() {
        val client = remoteMediaClient ?: return
        if (client.isPlaying) {
            client.pause()
        } else {
            client.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val options = MediaSeekOptions.Builder()
            .setPosition(positionMs.coerceAtLeast(0L))
            .build()
        remoteMediaClient?.seek(options)
    }

    fun skipToNext() {
        remoteMediaClient?.queueNext(null)
    }

    fun skipToPrevious() {
        remoteMediaClient?.queuePrev(null)
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

            audioFile.albumArtUri?.let { artUri ->
                val artUrl = localMediaServer.registerArt(
                    id = audioFile.id.toString(),
                    contentUri = artUri
                )
                addImage(WebImage(Uri.parse(artUrl)))
            }
        }

        return MediaInfo.Builder(mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(metadata)
            .setStreamDuration(audioFile.duration)
            .build()
    }
}
