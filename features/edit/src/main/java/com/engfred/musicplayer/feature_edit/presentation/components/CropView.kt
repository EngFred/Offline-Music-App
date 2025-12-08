package com.engfred.musicplayer.feature_edit.presentation.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageView
import com.engfred.musicplayer.core.ui.components.CustomTopBar
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
    var cropImageView by remember { mutableStateOf<CropImageView?>(null) }
    var isCropping by remember { mutableStateOf(false) }

    BackHandler {
        onCancel()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
        topBar = {
            CustomTopBar(
                title = "Crop Image",
                showNavigationIcon = true,
                onNavigateBack = onCancel,
                backgroundColor = Color.Black,
                contentColor = Color.White,
                actions = {
                    IconButton(
                        onClick = {
                            if (!isCropping) {
                                isCropping = true
                                scope.launch {
                                    val bitmap = cropImageView?.getCroppedImage()
                                    if (bitmap != null) {
                                        saveBitmapAndCrop(context, bitmap, onCrop, onCancel)
                                    } else {
                                        onCancel()
                                    }
                                    isCropping = false
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Save Crop",
                            tint = Color.White // Ensure icon is white
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CropImageView(ctx).apply {
                        setAspectRatio(1, 1)
                        setFixedAspectRatio(true)
                        guidelines = CropImageView.Guidelines.ON
                        isShowProgressBar = true
                        setBackgroundColor(0xFF000000.toInt())
                    }
                },
                update = { view ->
                    if (view.imageUri != imageUri) {
                        view.setImageUriAsync(imageUri)
                    }
                    cropImageView = view
                }
            )
        }
    }
}

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