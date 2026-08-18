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
import com.engfred.musicplayer.core.domain.ActivePlayerRegistry
import com.engfred.musicplayer.core.domain.model.AppSettings
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.domain.model.UpdateInfo
import com.engfred.musicplayer.core.domain.repository.LibraryRepository
import com.engfred.musicplayer.core.domain.repository.PlaybackController
import com.engfred.musicplayer.core.domain.repository.PlaybackState
import com.engfred.musicplayer.core.domain.repository.SettingsRepository
import com.engfred.musicplayer.core.domain.usecases.CheckForUpdateUseCase
import com.engfred.musicplayer.core.domain.usecases.PermissionHandlerUseCase
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.core.ui.theme.MusicPlayerAppTheme
import com.engfred.musicplayer.core.util.MediaUtils
import com.engfred.musicplayer.feature_library.data.worker.NewAudioScanWorker
import com.engfred.musicplayer.feature_playlist.data.worker.PlayEventPruneWorker
import com.engfred.musicplayer.feature_settings.domain.usecases.GetAppSettingsUseCase
import com.engfred.musicplayer.helpers.IntentPermissionHelper
import com.engfred.musicplayer.helpers.PlaybackQueueHelper
import com.engfred.musicplayer.navigation.AppNavHost
import com.engfred.musicplayer.ui.CustomSplashScreen
import com.engfred.musicplayer.update.UpdateCheckWorker
import com.engfred.musicplayer.update.UpdateDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var getAppSettingsUseCase: GetAppSettingsUseCase
    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var sharedAudioDataSource: SharedAudioDataSource
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var permissionHandlerUseCase: PermissionHandlerUseCase
    @Inject lateinit var activePlayerRegistry: ActivePlayerRegistry
    @Inject lateinit var checkForUpdateUseCase: CheckForUpdateUseCase

    private var externalPlaybackUri by mutableStateOf<Uri?>(null)
    private var pendingPlaybackUri: Uri? = null
    private var lastHandledUriString: String? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var playbackState by mutableStateOf(PlaybackState())
    private var initialAppSettings: AppSettings? by mutableStateOf(null)
    private var appSettingsLoaded by mutableStateOf(false)
    private var lastPlaybackAudio: AudioFile? by mutableStateOf(null)
    private var lastPlaybackPosition: Long by mutableLongStateOf(0L)

    private var showNowPlaying by mutableStateOf(false)

    private var navigateToNowPlayingOnStart by mutableStateOf(false)

    private val uiScope get() = lifecycleScope

    private var updateInfo by mutableStateOf<UpdateInfo?>(null)
    private var showUpdateDialog by mutableStateOf(false)

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                externalPlaybackUri = pendingPlaybackUri
                pendingPlaybackUri  = null
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
                    appSettingsLoaded  = true
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
                        context              = this@MainActivity,
                        settingsRepository   = settingsRepository,
                        libRepo              = libraryRepository,
                        sharedAudioDataSource = sharedAudioDataSource
                    )
                }

                val allFiles = withContext(Dispatchers.IO) {
                    libraryRepository.getAllAudioFiles().first()
                }
                if (allFiles.isNotEmpty()) {
                    sharedAudioDataSource.setDeviceAudioFiles(allFiles)
                }

                lastPlaybackAudio = if (start != null) {
                    val isAccessible = MediaUtils.isAudioFileAccessible(
                        context              = this@MainActivity,
                        audioFileUri         = start.uri,
                        permissionHandlerUseCase = permissionHandlerUseCase
                    )
                    if (isAccessible) start else null
                } else null
                checkIntentForNewMusic(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to prepare playing queue: ${t.message}")
            }
        }

        uiScope.launch {
            try {
                val lastCheck = settingsRepository.getLastUpdateCheckTimestamp()
                val now       = System.currentTimeMillis()
                val oneDayMs  = 24L * 60 * 60 * 1000
                if (now - lastCheck > oneDayMs) {
                    // checkForUpdateUseCase now THROWS on network/API errors instead of
                    // returning null, so we only reach the timestamp-save line when the
                    // call genuinely completes (update found OR app is up-to-date).
                    val info = checkForUpdateUseCase(BuildConfig.VERSION_NAME)

                    // Save timestamp ONLY on a successful API response so a transient
                    // network failure doesn't block retries for the next 24 hours.
                    settingsRepository.updateLastUpdateCheckTimestamp(now)

                    if (info != null) {
                        updateInfo       = info
                        showUpdateDialog = true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "On-launch update check failed: ${t.message}")
            }
        }

        handleIncomingIntent(intent)

        setContent {
            val audioItems by sharedAudioDataSource.deviceAudioFiles.collectAsState(initial = emptyList())

            var splashComplete by remember { mutableStateOf(hasShownSplashThisSession) }

            val selectedTheme = initialAppSettings?.selectedTheme ?: AppThemeType.NEON_DARK
            MusicPlayerAppTheme(selectedTheme = selectedTheme) {

                if (!splashComplete) {
                    CustomSplashScreen(
                        isReady = appSettingsLoaded,
                        onSplashComplete = {
                            splashComplete = true
                            hasShownSplashThisSession = true
                        },
                    )
                    return@MusicPlayerAppTheme
                }

                val navController = androidx.navigation.compose.rememberNavController()

                LaunchedEffect(navigateToNowPlayingOnStart) {
                    if (navigateToNowPlayingOnStart) {
                        showNowPlaying = true
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
                    onNavigateToNowPlaying = {
                        showNowPlaying = true
                    },
                    showNowPlaying    = showNowPlaying,
                    onShowNowPlaying  = { showNowPlaying = it },
                    onPlayPause = {
                        uiScope.launch { playbackController.playPause() }
                    },
                    onPlayNext = {
                        uiScope.launch { playbackController.skipToNext() }
                    },
                    onPlayPrev = {
                        uiScope.launch { playbackController.skipToPrevious() }
                    },
                    playingAudioFile  = playbackState.currentAudioFile,
                    isPlaying         = playbackState.isPlaying,
                    context           = this@MainActivity,
                    onPlayAll         = { PlaybackQueueHelper.playAll(this@MainActivity, sharedAudioDataSource, playbackController, settingsRepository) },
                    onShuffleAll      = { PlaybackQueueHelper.shuffleAll(this@MainActivity, sharedAudioDataSource, playbackController, settingsRepository) },
                    audioItems        = audioItems,
                    onReleasePlayer   = { uiScope.launch { playbackController.releasePlayer() } },
                    lastPlaybackAudio = lastPlaybackAudio,
                    stopAfterCurrent  = playbackState.stopAfterCurrent,
                    onToggleStopAfterCurrent = {
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
                    val uri = externalPlaybackUri ?: return@LaunchedEffect
                    val success = withContext(Dispatchers.IO) { initiatePlaybackFromExternalUri(uri) }

                    if (success) {
                        showNowPlaying = true
                    }

                    externalPlaybackUri = null
                }

                val currentUpdateInfo = updateInfo
                if (showUpdateDialog && currentUpdateInfo != null) {
                    UpdateDialog(
                        updateInfo    = currentUpdateInfo,
                        onDownload    = { showUpdateDialog = false; updateInfo = null },
                        onRemindLater = { showUpdateDialog = false; updateInfo = null },
                        onDismiss     = { showUpdateDialog = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        uiScope.launch { checkIntentForNewMusic(intent) }
    }

    private fun scheduleBackgroundScan() {
        val workRequest = PeriodicWorkRequestBuilder<NewAudioScanWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            NewAudioScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        UpdateCheckWorker.schedule(this)
        PlayEventPruneWorker.schedule(this)
    }

    private suspend fun checkIntentForNewMusic(intent: Intent?) {
        if (intent?.getBooleanExtra("PLAY_NEW_SONGS", false) == true) {
            if (sharedAudioDataSource.deviceAudioFiles.value.isEmpty()) delay(500)
            val allSongs   = sharedAudioDataSource.deviceAudioFiles.value.ifEmpty { libraryRepository.getAllAudioFiles().first() }
            val newestSong = allSongs.maxByOrNull { it.dateAdded } ?: return
            val recentQueue = allSongs.sortedByDescending { it.dateAdded }.take(50)
            sharedAudioDataSource.setPlayingQueue(recentQueue)
            playbackController.initiatePlayback(newestSong.uri)
            navigateToNowPlayingOnStart = true
        }
    }

    private fun getRequiredReadPermission(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    private fun handleIncomingIntent(intent: Intent?) {
        try {
            IntentPermissionHelper.handleIncomingIntent(
                this,
                intent,
                ::getRequiredReadPermission,
                { uri -> this.externalPlaybackUri  = uri },
                { pending -> this.pendingPlaybackUri = pending },
                permissionLauncher,
                ::tryOpenUriStream,
                { s -> this.lastHandledUriString = s },
                { s -> this.lastHandledUriString == s }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming intent: ${e.message}", e)
        }

        if (intent?.getBooleanExtra("SHOW_UPDATE_DIALOG", false) == true) {
            uiScope.launch {
                try {
                    val info = checkForUpdateUseCase(BuildConfig.VERSION_NAME)
                    if (info != null) { updateInfo = info; showUpdateDialog = true }
                } catch (t: Throwable) {
                    Log.w(TAG, "Notification-triggered update check failed: ${t.message}")
                }
            }
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

    /**
     * replaced the manual busy-wait loop
     *
     * while (System.currentTimeMillis() - start < 3_000 && !success) {
     * if (playbackState.currentAudioFile != null && ...) success = true
     * delay(200)
     * }
     *
     * with a reactive [withTimeoutOrNull] + [Flow.first] call. The old approach
     * spun for up to 3 seconds polling a Compose state variable on the wrong
     * dispatcher. The new approach suspends cleanly and cancels immediately once
     * the condition is met — no wasted CPU cycles on slow devices.
     */
    private suspend fun initiatePlaybackFromExternalUri(uri: Uri): Boolean {
        return try {
            Log.d(TAG, "Initiating external URI playback: $uri")

            if (!playbackController.waitUntilReady()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Player not ready. Please try again.", Toast.LENGTH_LONG).show()
                }
                return false
            }

            val audioFileFetchStatus = libraryRepository.getAudioFileByUri(uri)
            val audioFileToPlay: AudioFile? = (audioFileFetchStatus as? Resource.Success)?.data
            val finalAudioFile  = audioFileToPlay ?: extractAudioMetadataFromUri(uri)

            if (finalAudioFile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not load audio file.", Toast.LENGTH_LONG).show()
                }
                return false
            }

            sharedAudioDataSource.setPlayingQueue(listOf(finalAudioFile))
            playbackController.initiatePlayback(uri)

            // reactive wait — suspends until state satisfies condition or 3 s elapses.
            val started = withTimeoutOrNull(3_000.milliseconds) {
                playbackController.getPlaybackState()
                    .first { state ->
                        state.currentAudioFile != null && (state.isPlaying || state.isLoading)
                    }
            }
            started != null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start external URI playback: ${e.message}", e)
            false
        }
    }

    private suspend fun extractAudioMetadataFromUri(uri: Uri): AudioFile? {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, uri)
                var fileName = "External Audio"
                var fileSize: Long? = null
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx != -1) fileName = cursor.getString(nameIdx)
                            if (sizeIdx != -1) fileSize  = cursor.getLong(sizeIdx)
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Could not get file details: ${e.message}") }

                val title    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName
                val artist   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "<Unknown>"
                val album    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown"
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                AudioFile(
                    id          = uri.hashCode().toLong(),
                    title       = title,
                    artist      = artist,
                    artistId    = null,
                    album       = album,
                    duration    = duration,
                    uri         = uri,
                    albumArtUri = null,
                    dateAdded   = System.currentTimeMillis() / 1000,
                    size        = fileSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting metadata from URI: ${e.message}")
                null
            } finally {
                retriever.release()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        // Keeps track of the splash screen across the entire app process lifecycle
        private var hasShownSplashThisSession = false
    }
}