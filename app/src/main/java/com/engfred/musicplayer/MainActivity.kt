package com.engfred.musicplayer

import android.Manifest
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.engfred.musicplayer.core.common.Resource
import com.engfred.musicplayer.core.data.SharedAudioDataSource
import com.engfred.musicplayer.core.domain.model.AppSettings
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.core.ui.theme.MusicPlayerAppTheme
import com.engfred.musicplayer.core.util.MediaUtils
import com.engfred.musicplayer.feature_library.data.worker.NewAudioScanWorker
import com.engfred.musicplayer.feature_settings.domain.usecases.GetAppSettingsUseCase
import com.engfred.musicplayer.helpers.IntentPermissionHelper
import com.engfred.musicplayer.helpers.PlaybackQueueHelper
import com.engfred.musicplayer.navigation.AppDestinations
import com.engfred.musicplayer.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var getAppSettingsUseCase: GetAppSettingsUseCase
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var sharedAudioDataSource: SharedAudioDataSource
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var permissionHandlerUseCase: PermissionHandlerUseCase

    private var externalPlaybackUri by mutableStateOf<Uri?>(null)
    private var pendingPlaybackUri: Uri? = null
    private var lastHandledUriString: String? = null

    // Launcher for handling External Intents only (Opening a file from file manager)
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var playbackState by mutableStateOf(PlaybackState())
    private var initialAppSettings: AppSettings? by mutableStateOf(null)
    private var appSettingsLoaded by mutableStateOf(false)

    private var lastPlaybackAudio: AudioFile? by mutableStateOf(null)
    private var lastPlaybackPosition: Long by mutableLongStateOf(0L)

    // State to trigger navigation to NowPlaying if launched from notification
    private var navigateToNowPlayingOnStart by mutableStateOf(false)

    private val uiScope get() = lifecycleScope

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: start")

        enableEdgeToEdge()

        // 1. Setup Permission Launcher (For External Intents only)
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Log.d(TAG, "Read permission granted (via Intent).")
                externalPlaybackUri = pendingPlaybackUri
                pendingPlaybackUri = null
            } else {
                Toast.makeText(this, "Permission required to play external audio files.", Toast.LENGTH_SHORT).show()
                pendingPlaybackUri = null
            }
        }

        scheduleBackgroundScan()

        uiScope.launch {
            try {
                getAppSettingsUseCase().collect { settings ->
                    initialAppSettings = settings
                    appSettingsLoaded = true
                    playbackController.setRepeatMode(settings.repeatMode)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to observe app settings: ${t.message}")
            }
        }

        uiScope.launch {
            try {
                playbackController.getPlaybackState().collect { state ->
                    playbackState = state
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to collect playback state: ${t.message}")
            }
        }

        uiScope.launch {
            try {
                val lastState = settingsRepository.getLastPlaybackState().first()
                lastPlaybackPosition = lastState.positionMs

                val start = withContext(Dispatchers.IO) {
                    PlaybackQueueHelper.preparePlayingQueue(
                        context = this@MainActivity,
                        settingsRepository = settingsRepository,
                        libRepo = libraryRepository,
                        sharedAudioDataSource = sharedAudioDataSource
                    )
                }

                lastPlaybackAudio = if (start != null) {
                    val isAccessible = MediaUtils.isAudioFileAccessible(
                        context = this@MainActivity,
                        audioFileUri = start.uri,
                        permissionHandlerUseCase = permissionHandlerUseCase
                    )
                    if (isAccessible) start else null
                } else null

                checkIntentForNewMusic(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to prepare playing queue: ${t.message}")
            }
        }

        handleIncomingIntent(intent)

        setContent {
            val audioItems by sharedAudioDataSource.deviceAudioFiles.collectAsState(initial = emptyList())
            val selectedTheme = initialAppSettings?.selectedTheme ?: AppThemeType.CLASSIC_BLUE

            MusicPlayerAppTheme(selectedTheme = selectedTheme) {
                val navController = androidx.navigation.compose.rememberNavController()

                // Navigate if requested by notification
                LaunchedEffect(navigateToNowPlayingOnStart) {
                    if (navigateToNowPlayingOnStart) {
                        navController.navigate(AppDestinations.NowPlaying.route)
                        navigateToNowPlayingOnStart = false
                    }
                }

                var isInitialResume by remember { mutableStateOf(true) }

                LaunchedEffect(playbackState.isLoading, playbackState.currentAudioFile) {
                    if (!playbackState.isLoading && playbackState.currentAudioFile != null) {
                        isInitialResume = false
                    }
                }

                AppNavHost(
                    rootNavController = navController,
                    onPlayPause = {
                        uiScope.launch {
                            if (playbackState.currentAudioFile != null) {
                                playbackController.playPause()
                            } else {
                                val lastState = settingsRepository.getLastPlaybackState().first()
                                val startUri = lastPlaybackAudio?.uri
                                if (startUri != null) {
                                    playbackController.initiatePlayback(startUri, lastState.positionMs)
                                }
                            }
                        }
                    },
                    onPlayNext = {
                        uiScope.launch {
                            if (playbackState.currentAudioFile != null) {
                                playbackController.skipToNext()
                            } else {
                                // Logic to start playback if nothing is playing
                                val lastState = settingsRepository.getLastPlaybackState().first()
                                val startUri = lastPlaybackAudio?.uri
                                if (startUri != null) {
                                    playbackController.initiatePlayback(startUri, lastState.positionMs)
                                    // Optimization: Wait briefly for ready before skipping
                                    if (playbackController.waitUntilReady(5000)) {
                                        playbackController.skipToNext()
                                    }
                                }
                            }
                        }
                    },
                    onPlayPrev = {
                        uiScope.launch {
                            if (playbackState.currentAudioFile != null) {
                                playbackController.skipToPrevious()
                            } else {
                                val lastState = settingsRepository.getLastPlaybackState().first()
                                val startUri = lastPlaybackAudio?.uri
                                if (startUri != null) {
                                    playbackController.initiatePlayback(startUri, lastState.positionMs)
                                    if (playbackController.waitUntilReady(5000)) {
                                        playbackController.skipToPrevious()
                                    }
                                }
                            }
                        }
                    },
                    playingAudioFile = playbackState.currentAudioFile,
                    isPlaying = playbackState.isPlaying,
                    context = this,
                    onNavigateToNowPlaying = {
                        uiScope.launch {
                            if (playbackState.currentAudioFile == null) {
                                val lastState = settingsRepository.getLastPlaybackState().first()
                                val startUri = lastPlaybackAudio?.uri
                                if (startUri != null) {
                                    playbackController.initiatePlayback(startUri, lastState.positionMs)
                                    if (!playbackController.waitUntilReady(5000)) {
                                        return@launch
                                    }
                                } else {
                                    return@launch
                                }
                            }
                            navController.navigate(AppDestinations.NowPlaying.route)
                        }
                    },
                    onPlayAll = { PlaybackQueueHelper.playAll(this, sharedAudioDataSource, playbackController, settingsRepository) },
                    onShuffleAll = { PlaybackQueueHelper.shuffleAll(this, sharedAudioDataSource, playbackController, settingsRepository) },
                    audioItems = audioItems,
                    onReleasePlayer = {
                        uiScope.launch { playbackController.releasePlayer() }
                    },
                    lastPlaybackAudio = lastPlaybackAudio,
                    stopAfterCurrent = playbackState.stopAfterCurrent,
                    onToggleStopAfterCurrent = {
                        if(playbackState.stopAfterCurrent.not()){
                            Toast.makeText(this, "Playback will stop when current song ends", Toast.LENGTH_SHORT).show()
                        }
                        playbackController.toggleStopAfterCurrent()
                    },
                    playbackPositionMs = if (playbackState.currentAudioFile != null) {
                        if (playbackState.isLoading) {
                            if (isInitialResume) lastPlaybackPosition else 0L
                        } else playbackState.playbackPositionMs
                    } else {
                        lastPlaybackPosition
                    },
                    totalDurationMs = if (playbackState.currentAudioFile != null) {
                        if (playbackState.isLoading) {
                            if (isInitialResume) lastPlaybackAudio?.duration ?: 0L else 0L
                        } else playbackState.totalDurationMs
                    } else {
                        lastPlaybackAudio?.duration ?: 0L
                    }
                )

                LaunchedEffect(externalPlaybackUri) {
                    val uri = externalPlaybackUri
                    if (uri != null) {
                        val success = withContext(Dispatchers.IO) { initiatePlaybackFromExternalUri(uri) }
                        if (success) {
                            navController.navigate(AppDestinations.NowPlaying.route)
                        }
                        externalPlaybackUri = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        setIntent(intent)
        handleIncomingIntent(intent)

        uiScope.launch {
            checkIntentForNewMusic(intent)
        }
    }

    // --- Work Manager & Notification Logic ---

    private fun scheduleBackgroundScan() {
        val workRequest = PeriodicWorkRequestBuilder<NewAudioScanWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            NewAudioScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private suspend fun checkIntentForNewMusic(intent: Intent?) {
        if (intent?.getBooleanExtra("PLAY_NEW_SONGS", false) == true) {
            if (sharedAudioDataSource.deviceAudioFiles.value.isEmpty()) {
                delay(500)
            }
            val allSongs = sharedAudioDataSource.deviceAudioFiles.value.ifEmpty {
                libraryRepository.getAllAudioFiles().first()
            }
            val newestSong = allSongs.maxByOrNull { it.dateAdded }

            if (newestSong != null) {
                val recentQueue = allSongs.sortedByDescending { it.dateAdded }.take(50)
                sharedAudioDataSource.setPlayingQueue(recentQueue)
                playbackController.initiatePlayback(newestSong.uri)
                navigateToNowPlayingOnStart = true
            }
        }
    }

    // --- Helpers ---

    private fun getRequiredReadPermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        try {
            IntentPermissionHelper.handleIncomingIntent(
                this,
                intent,
                ::getRequiredReadPermission,
                { uri -> this.externalPlaybackUri = uri },
                { pending -> this.pendingPlaybackUri = pending },
                permissionLauncher,
                ::tryOpenUriStream,
                { s -> this.lastHandledUriString = s },
                { s -> this.lastHandledUriString == s }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming intent: ${e.message}", e)
        }
    }

    private fun tryOpenUriStream(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to open URI: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Could not open URI stream: ${e.message}")
            false
        }
    }

    private suspend fun initiatePlaybackFromExternalUri(uri: Uri): Boolean {
        try {
            Log.d(TAG, "Attempt to initiate playback for external URI: $uri")

            if (!playbackController.waitUntilReady()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Player not ready. Please try again.", Toast.LENGTH_LONG).show()
                }
                return false
            }

            // 1. Try to find the file in our Database (Best case scenario)
            val audioFileFetchStatus = libraryRepository.getAudioFileByUri(uri)

            val audioFileToPlay: AudioFile? = when (audioFileFetchStatus) {
                is Resource.Success -> audioFileFetchStatus.data
                else -> null
            }

            // 2. Fallback: If not in DB, create a temporary AudioFile object from metadata
            val finalAudioFile = audioFileToPlay ?: extractAudioMetadataFromUri(uri)

            if (finalAudioFile == null) {
                Log.e(TAG, "Could not resolve audio file details.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not load audio file.", Toast.LENGTH_LONG).show()
                }
                return false
            }

            // 3. Play
            sharedAudioDataSource.setPlayingQueue(listOf(finalAudioFile))
            playbackController.initiatePlayback(uri)

            val startTime = System.currentTimeMillis()
            var success = false
            while (System.currentTimeMillis() - startTime < 3_000 && !success) {
                if (playbackState.currentAudioFile != null && (playbackState.isPlaying || playbackState.isLoading)) {
                    success = true
                }
                delay(200)
            }
            return success

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback for external URI: ${e.message}", e)
            return false
        }
    }

    /**
     * EXTRACTS METADATA ON THE FLY.
     * Essential for playing files not yet in the MediaStore (e.g. from WhatsApp, Downloads).
     */
    private suspend fun extractAudioMetadataFromUri(uri: Uri): AudioFile? {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, uri)

                // Get filename and size as fallback/extra info
                var fileName = "External Audio"
                var fileSize: Long? = null
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Could not get file details: ${e.message}") }

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "<Unknown>"
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L

                AudioFile(
                    id = uri.hashCode().toLong(), // Temporary ID
                    title = title,
                    artist = artist,
                    artistId = null, // No artist ID for non-MediaStore files
                    album = album,
                    duration = duration,
                    uri = uri,
                    albumArtUri = null, // Cannot easily generate a stable URI for embedded art on the fly
                    dateAdded = System.currentTimeMillis() / 1000,
                    size = fileSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting metadata from URI: ${e.message}")
                null
            } finally {
                retriever.release()
            }
        }
    }
}