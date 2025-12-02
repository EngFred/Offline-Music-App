package com.engfred.musicplayer.feature_edit.presentation.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
fun CropDialog(
    imageUri: Uri,
    onCrop: (Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // We keep a reference to the view to call getCroppedImage()
    var cropImageView by remember { mutableStateOf<CropImageView?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Crop Album Art") },
        text = {
            AndroidView(
                factory = { ctx ->
                    CropImageView(ctx).apply {
                        setAspectRatio(1, 1)
                        cropShape = CropImageView.CropShape.RECTANGLE
                        guidelines = CropImageView.Guidelines.ON
                    }
                },
                update = { view ->
                    view.setImageUriAsync(imageUri)
                    cropImageView = view
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val bitmap = cropImageView?.getCroppedImage()
                    if (bitmap != null) {
                        saveBitmapAndCrop(context, bitmap, onCrop, onCancel)
                    } else {
                        onCancel()
                    }
                }
            }) {
                Text("Crop")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

// Helper function to handle IO off the main thread
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
            // Switch back to Main for callback
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