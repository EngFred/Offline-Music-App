package com.engfred.musicplayer.feature_library.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * PermissionRequestContent
 *
 * - shouldShowRationale: whether the OS recommends showing a rationale.
 * - isPermanentlyDenied: true if user has permanently denied permission (can't request again).
 * - isPermissionDialogShowing: true while the system permission dialog is expected to be open.
 * - onRequestPermission: callback to call when user asks to request permission.
 * - onOpenAppSettings: open application's settings page.
 */
@Composable
fun PermissionRequestContent(
    shouldShowRationale: Boolean,
    isPermanentlyDenied: Boolean,
    isPermissionDialogShowing: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    // Local UI state for the help & rationale dialogs (UI Logic only)
    var showHelpDialog by remember { mutableStateOf(false) }
    var showRationaleDialog by remember { mutableStateOf(false) }

    // Help dialog content (used for "Need help?" in permanently denied state)
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = "Permission help") },
            text = {
                Column {
                    Text(
                        text = "If you have permanently denied storage access, you can re-enable it from the app settings. " +
                                "Tap ‘Open App Settings’ below, find Music (this app) and enable the storage or media permission."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "After enabling, return to Music — the library will refresh automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHelpDialog = false
                        // Go to App Settings
                        onOpenAppSettings()
                    }
                ) {
                    Text("Open App Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Rationale dialog content (used for "Why we need this" or "Why allow access?")
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text(text = "Why Music needs access") },
            text = {
                Column {
                    Text(
                        text = "Music requests access to your audio files so you can discover, browse and play songs stored on your device. " +
                                "We only read audio metadata and file URIs for playback and playlists — we do not share your files externally."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tapping Grant Access will open the system permission dialog. If you previously denied, choose Allow to proceed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationaleDialog = false
                        // Re-attempt permission request
                        onRequestPermission()
                    }
                ) {
                    Text("Grant Access")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Main Content
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Permission information",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Music needs access to your audio files",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = when {
                isPermissionDialogShowing -> {
                    "Please check the permission dialog to grant access to your music library."
                }
                isPermanentlyDenied -> {
                    "You have permanently denied access to the music library. To enable full functionality, open the app settings and grant storage permission to Music."
                }
                shouldShowRationale -> {
                    "Music needs permission to access your audio files so you can discover, browse and play your songs. Please grant access to continue."
                }
                else -> {
                    "To discover and play your songs, Music requires permission to access audio files on your device. Tap Grant Access to allow."
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Actions
        if (isPermissionDialogShowing) {
            CircularProgressIndicator()
        } else {
            if (isPermanentlyDenied) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { onOpenAppSettings() },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Text(text = "Open App Settings", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showHelpDialog = true },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Text(text = "Need help?", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledTonalButton(
                        onClick = { onRequestPermission() },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Text(text = "Grant Access", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showRationaleDialog = true },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Text(text = if (shouldShowRationale) "Why we need this" else "Why allow access?")
                    }
                }
            }
        }
    }
}