package com.engfred.musicplayer.feature_playlist.presentation.components

import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reusable AlertDialog for confirming a delete action.
 *
 * @param itemName The name of the item to be deleted (e.g., "My Playlist").
 * @param onConfirm The action to perform when the user confirms deletion.
 * @param onDismiss The action to perform when the user dismisses the dialog.
 * @param modifier Modifier for the AlertDialog.
 */
@Composable
fun DeleteConfirmationDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Playlist?") },
        text = { Text("Are you sure you want to delete '$itemName'? This action cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}