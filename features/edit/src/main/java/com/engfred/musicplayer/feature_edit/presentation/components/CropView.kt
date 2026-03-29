package com.engfred.musicplayer.feature_edit.presentation.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun CropView(
    imageUri: Uri,
    onCrop: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // We hold a reference to the View to trigger the crop action
    var cropImageView by remember { mutableStateOf<CropImageView?>(null) }
    var isCropping by remember { mutableStateOf(false) }

    // Intercept physical/gesture back button
    BackHandler(enabled = !isCropping) {
        onCancel()
    }

    // A completely immersive, edge-to-edge black box
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // 1. The Core Image Cropper (Legacy View Interop)
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // Leave room at the bottom for our custom control bar
                .padding(bottom = 80.dp),
            factory = { ctx ->
                CropImageView(ctx).apply {
                    setAspectRatio(1, 1)
                    setFixedAspectRatio(true)
                    guidelines = CropImageView.Guidelines.ON
                    isShowProgressBar = false // We handle loading states in Compose
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                // Only load the URI if it's new to avoid flickering
                if (view.imageUri != imageUri) {
                    view.setImageUriAsync(imageUri)
                }
                cropImageView = view
            }
        )

        // 2. The Bottom Control Deck (Thumb-friendly)
        CropBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            isCropping = isCropping,
            onCancel = onCancel,
            onDone = {
                if (!isCropping && cropImageView != null) {
                    isCropping = true
                    scope.launch {
                        val bitmap = cropImageView?.getCroppedImage()
                        if (bitmap != null) {
                            saveBitmapAndCrop(context, bitmap, onCrop, onCancel)
                        } else {
                            isCropping = false
                            onCancel()
                        }
                    }
                }
            }
        )
    }
}

// Extracted the heavy file I/O to keep the composable clean
private suspend fun saveBitmapAndCrop(
    context: android.content.Context,
    bitmap: Bitmap,
    onCrop: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("crop_", ".jpg", context.cacheDir)
            val out = FileOutputStream(tempFile)
            // 90 quality is perfect for album art (balances size and clarity)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()

            val croppedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                tempFile
            )
            withContext(Dispatchers.Main) {
                onCrop(croppedUri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onCancel()
            }
        }
    }
}