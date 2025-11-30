package com.engfred.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var playbackState by mutableStateOf(PlaybackState())
    private var initialAppSettings: AppSettings? by mutableStateOf(null)
    private var appSettingsLoaded by mutableStateOf(false)

    private var lastPlaybackAudio: AudioFile? by mutableStateOf(null)

    // State to trigger navigation to NowPlaying if launched from notification
    private var navigateToNowPlayingOnStart by mutableStateOf(false)

    private val uiScope get() = lifecycleScope

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: start")

        enableEdgeToEdge()

        // Setup permission launcher first
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Log.d(TAG, "Read permission granted by the user.")
                // 1. Schedule the Background Worker only if permission granted now
                scheduleBackgroundScan()

                externalPlaybackUri = pendingPlaybackUri
                pendingPlaybackUri = null
            } else {
                Toast.makeText(
                    this,
                    "Permission required to play external audio files.",
                    Toast.LENGTH_SHORT
                ).show()
                pendingPlaybackUri = null
            }
        }

        // 1. Check if permission already exists on app start
        if (ActivityCompat.checkSelfPermission(this, getRequiredReadPermission()) == PackageManager.PERMISSION_GRANTED) {
            scheduleBackgroundScan()
        }

        uiScope.launch {
            try {
                getAppSettingsUseCase().collect { settings ->
                    initialAppSettings = settings
                    appSettingsLoaded = true
                    playbackController.setRepeatMode(settings.repeatMode)
                    Log.d(TAG, "App settings loaded. repeat=${settings.repeatMode}")
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
                val start = withContext(Dispatchers.IO) {
                    PlaybackQueueHelper.preparePlayingQueue(
                        context = this@MainActivity,
                        settingsRepository = settingsRepository,
                        libRepo = libraryRepository,
                        sharedAudioDataSource = sharedAudioDataSource
                    )
                }
                // Validating if the last playback audio still exists and is accessible using MediaUtils
                lastPlaybackAudio = if (start != null) {
                    val isAccessible = MediaUtils.isAudioFileAccessible(
                        context = this@MainActivity,
                        audioFileUri = start.uri,
                        permissionHandlerUseCase = permissionHandlerUseCase
                    )
                    if (isAccessible) {
                        Log.d(TAG, "Last playback audio validated as accessible: ${start.title}")
                        start
                    } else {
                        Log.w(TAG, "Last playback audio no longer accessible")
                        null
                    }
                } else null

                // 2. Check if launched from Notification to play new songs
                checkIntentForNewMusic(intent)

                Log.d(TAG, "preparePlayingQueue returned startAudio=${lastPlaybackAudio?.id}")
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
                                val lastState = settingsRepository.getLastPlaybackState().first()
                                val startUri = lastPlaybackAudio?.uri
                                if (startUri != null) {
                                    playbackController.initiatePlayback(startUri, lastState.positionMs)
                                    if (playbackController.waitUntilReady(5000)) {
                                        playbackController.skipToNext()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Failed to start playback", Toast.LENGTH_SHORT).show()
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
                                    } else {
                                        Toast.makeText(this@MainActivity, "Failed to start playback", Toast.LENGTH_SHORT).show()
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
                                        Toast.makeText(this@MainActivity, "Failed to start playback", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                } else {
                                    Toast.makeText(this@MainActivity, "No previous playback", Toast.LENGTH_SHORT).show()
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
                    lastPlaybackAudio = lastPlaybackAudio
                )

                LaunchedEffect(externalPlaybackUri) {
                    val uri = externalPlaybackUri
                    if (uri != null) {
                        val success = withContext(Dispatchers.IO) { initiatePlaybackFromExternalUri(uri) }
                        if (success) navController.navigate(AppDestinations.NowPlaying.route)
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

        // Check if new intent is from notification click
        uiScope.launch {
            checkIntentForNewMusic(intent)
        }
    }

    // --- Work Manager & Notification Logic ---

    private fun scheduleBackgroundScan() {
        // --- TESTING MODE: ONE-TIME WORKER ---
        // Used to verify worker startup immediately on app launch
//        val testWorkRequest = OneTimeWorkRequestBuilder<NewAudioScanWorker>()
//            .build()
//
//        WorkManager.getInstance(this).enqueue(testWorkRequest)
//        Log.d(TAG, "scheduleBackgroundScan: Enqueued OneTimeWorkRequest for testing.")

        val workRequest = PeriodicWorkRequestBuilder<NewAudioScanWorker>(
            15, TimeUnit.MINUTES // Run every 15 minutes (minimum allowed interval)
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            NewAudioScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Don't restart if already running
            workRequest
        )
        Log.d(TAG, "scheduleBackgroundScan: Enqueued PeriodicWorkRequest.")
    }

    private suspend fun checkIntentForNewMusic(intent: Intent?) {
        if (intent?.getBooleanExtra("PLAY_NEW_SONGS", false) == true) {
            Log.d(TAG, "Launched from New Music Notification")

            // Get the most recently added song
            // We can fetch 'Recently Added' via LibraryRepo or just getAllAudioFiles sorted
            // For simplicity, let's just get the absolute latest song

            // Wait a moment for SharedAudio to populate if app was dead
            if (sharedAudioDataSource.deviceAudioFiles.value.isEmpty()) {
                delay(500)
            }

            val allSongs = sharedAudioDataSource.deviceAudioFiles.value.ifEmpty {
                // Fallback to direct fetch if shared is empty (cold start)
                libraryRepository.getAllAudioFiles().first()
            }

            val newestSong = allSongs.maxByOrNull { it.dateAdded }

            if (newestSong != null) {
                // Set queue to Recently Added (sorted by date descending)
                val recentQueue = allSongs.sortedByDescending { it.dateAdded }.take(50)
                sharedAudioDataSource.setPlayingQueue(recentQueue)

                // Play
                playbackController.initiatePlayback(newestSong.uri)

                // Trigger nav
                navigateToNowPlayingOnStart = true
            }
        }
    }

    // --- Helpers ---

    private fun getRequiredReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                Log.e(TAG, "Player not ready in time for external playback.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Player not ready. Please try again.", Toast.LENGTH_LONG).show()
                }
                return false
            }

            val audioFileFetchStatus = libraryRepository.getAudioFileByUri(uri)
            when (audioFileFetchStatus) {
                is Resource.Error -> {
                    Log.e(TAG, "Failed to fetch audio file for external URI: ${audioFileFetchStatus.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to play selected file: ${audioFileFetchStatus.message}", Toast.LENGTH_LONG).show()
                    }
                    return false
                }
                is Resource.Loading -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Opening file in Music..", Toast.LENGTH_SHORT).show()
                    }
                    return false
                }
                is Resource.Success -> {
                    val audioFile = audioFileFetchStatus.data ?: run {
                        Log.e(TAG, "Audio File not found!")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Audio File not found!", Toast.LENGTH_LONG).show()
                        }
                        return false
                    }

                    sharedAudioDataSource.setPlayingQueue(listOf(audioFile))
                    playbackController.initiatePlayback(uri)

                    val startTime = System.currentTimeMillis()
                    var success = false
                    while (System.currentTimeMillis() - startTime < 3_000 && !success) {
                        if (playbackState.currentAudioFile != null && (playbackState.isPlaying || playbackState.isLoading)) {
                            success = true
                        }
                        delay(200)
                    }

                    if (!success) {
                        Log.w(TAG, "Playback did not start successfully within timeout.")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to start playback.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return success
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback for external URI: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Failed to play selected file: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }
}