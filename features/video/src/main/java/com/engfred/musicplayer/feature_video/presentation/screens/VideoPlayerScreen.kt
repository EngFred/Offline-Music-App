package com.engfred.musicplayer.feature_video.presentation.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.engfred.musicplayer.feature_video.data.repository.VideoPlaybackControllerImpl
import com.engfred.musicplayer.feature_video.presentation.components.VideoPlayerControls
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoPlayerEvent
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoPlayerViewModel
import com.engfred.musicplayer.feature_video.presentation.viewmodel.VideoResizeMode

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoPlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Keep screen ON during video playback
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler {
        onNavigateBack()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // AndroidView rendering Media3 PlayerView
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false // Use custom Compose controls
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val controllerImpl = viewModel.videoPlaybackController as? VideoPlaybackControllerImpl
                        player = controllerImpl?.getPlayer()
                    }
                },
                update = { playerView ->
                    playerView.resizeMode = when (uiState.resizeMode) {
                        VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        VideoResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // If casting to TV, show a beautiful, clear Cast overlay instead of an empty/black video surface
            if (uiState.isCastConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F14)),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.Tv,
                                    contentDescription = "Casting to TV",
                                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))

                        androidx.compose.material3.Text(
                            text = "Playing on TV",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            color = Color.White
                        )

                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

                        uiState.videoFile?.title?.let { title ->
                            androidx.compose.material3.Text(
                                text = title.replace('_', ' ').trim(),
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }

                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))

                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Rounded.CastConnected,
                                contentDescription = null,
                                tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            androidx.compose.material3.Text(
                                text = "Cast Connected",
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                ),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Compose Controls Overlay
            VideoPlayerControls(
                state = uiState,
                onEvent = viewModel::onEvent,
                onNavigateBack = onNavigateBack
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
