package com.engfred.musicplayer.feature_settings.presentation.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFile
import com.engfred.musicplayer.core.ui.components.CustomTopBar
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.DuplicatesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onNavigateUp: () -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Observe Toast Messages
    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Scoped Storage Delete Launcher
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleDeletionResult(true, null)
        } else {
            viewModel.handleDeletionResult(false, "Deletion cancelled.")
        }
    }

    // Observe Intent Requests
    LaunchedEffect(viewModel.deleteRequest) {
        viewModel.deleteRequest.collect { intentSenderRequest ->
            deleteLauncher.launch(intentSenderRequest)
        }
    }

    Scaffold(
        // Set transparent so the Box's gradient background bleeds beautifully under system bars
        containerColor = Color.Transparent,
        topBar = {
            // Respects the edge-to-edge notch/status bar area
            Box(modifier = Modifier.statusBarsPadding()) {
                CustomTopBar(
                    title = "Duplicate Finder",
                    subtitle = "Manage duplicated files",
                    showNavigationIcon = true, // FIX: Explicitly tell it to show the back arrow
                    onNavigateBack = onNavigateUp
                    // FIX: Removed modifier = Modifier.padding(start = 10.dp) so the back button aligns natively
                )
            }
        },
        bottomBar = {
            if (uiState.duplicateGroups.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Ensure the bottom bar respects the gesture navigation bar
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.selectedFiles.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = { viewModel.requestDeletion() },
                            enabled = uiState.selectedFiles.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    )
                )
                // innerPadding handles the space occupied by the TopBar and BottomBar
                .padding(innerPadding)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.duplicateGroups.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your library is clean!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "No duplicate files found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    uiState.duplicateGroups.forEachIndexed { index, group ->
                        item {
                            DuplicateGroupHeader(
                                title = group.first().title,
                                count = group.size
                            )
                        }
                        items(group) { file ->
                            DuplicateFileItem(
                                audioFile = file,
                                isSelected = uiState.selectedFiles.contains(file),
                                onClick = { viewModel.toggleSelection(file) }
                            )
                        }
                        if (index < uiState.duplicateGroups.size - 1) {
                            item {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DuplicateGroupHeader(title: String, count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$count copies found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DuplicateFileItem(
    audioFile: AudioFile,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val dateString = formatter.format(Date(audioFile.dateAdded))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = audioFile.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Added: $dateString",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Size: ${audioFile.size?.let { it / (1024 * 1024) } ?: 0} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}