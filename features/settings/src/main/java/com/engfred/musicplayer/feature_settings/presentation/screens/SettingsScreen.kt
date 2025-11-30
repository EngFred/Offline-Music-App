package com.engfred.musicplayer.feature_settings.presentation.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.musicplayer.core.domain.model.AudioFileTypeFilter
import com.engfred.musicplayer.core.domain.model.AudioPreset
import com.engfred.musicplayer.core.domain.model.PlayerLayout
import com.engfred.musicplayer.core.domain.model.PlaylistLayoutType
import com.engfred.musicplayer.core.domain.model.WidgetBackgroundMode
import com.engfred.musicplayer.core.ui.theme.AppThemeType
import com.engfred.musicplayer.feature_settings.presentation.components.AppVersionSection
import com.engfred.musicplayer.feature_settings.presentation.components.SettingsSection
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsEvent
import com.engfred.musicplayer.feature_settings.presentation.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Error message if any
            if (uiState.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "Error: ${uiState.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Settings sections
            SettingsSection(
                title = "App Theme",
                subtitle = "Choose a look that suits you",
                icon = Icons.Rounded.Brush,
                items = AppThemeType.entries,
                selectedItem = uiState.selectedTheme,
                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
                onSelect = { viewModel.onEvent(SettingsEvent.UpdateTheme(it)) }
            )

            //Audio file type filter toggle
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Audio File Types",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Turn on to show all audio formats (default); turn off to show only MP3 files in the Library screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Switch: ON => ALL, OFF => MP3_ONLY
                    val checked = uiState.audioFileTypeFilter == AudioFileTypeFilter.ALL
                    Switch(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            val newFilter = if (isChecked) AudioFileTypeFilter.ALL else AudioFileTypeFilter.MP3_ONLY
                            viewModel.onEvent(SettingsEvent.UpdateAudioFileTypeFilter(newFilter))
                        },
                    )
                }
            }

            //Widget background toggle
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Widget Background",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Let the home screen widget follow system light/dark mode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Switch: ON => THEME_AWARE, OFF => STATIC
                    val checked = uiState.widgetBackgroundMode == WidgetBackgroundMode.THEME_AWARE
                    Switch(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            val newMode = if (isChecked) WidgetBackgroundMode.THEME_AWARE else WidgetBackgroundMode.STATIC
                            viewModel.onEvent(SettingsEvent.UpdateWidgetBackgroundMode(newMode, context))
                        },
                    )
                }
            }

            SettingsSection(
                title = "Audio Preset",
                subtitle = "Select an equalizer preset for playback",
                icon = Icons.Rounded.Equalizer,
                items = AudioPreset.entries,
                selectedItem = uiState.audioPreset,
                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
                onSelect = { viewModel.onEvent(SettingsEvent.UpdateAudioPreset(it)) }
            )

//            SettingsSection(
//                title = "Now Playing Layout",
//                subtitle = "Layout shown on the player screen",
//                icon = Icons.Rounded.PlayArrow,
//                items = PlayerLayout.entries,
//                selectedItem = uiState.selectedPlayerLayout,
//                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
//                onSelect = { viewModel.onEvent(SettingsEvent.UpdatePlayerLayout(it)) }
//            )

//            SettingsSection(
//                title = "Playlist Layout",
//                subtitle = "How your playlists are displayed",
//                icon = Icons.AutoMirrored.Rounded.QueueMusic,
//                items = PlaylistLayoutType.entries,
//                selectedItem = uiState.playlistLayoutType,
//                displayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.titlecase() } },
//                onSelect = { viewModel.onEvent(SettingsEvent.UpdatePlaylistLayout(it)) }
//            )

            Spacer(modifier = Modifier.weight(1f).width(8.dp))

            //app version + copyright section
            AppVersionSection(
                copyrightText = "© 2025 Engineer Fred",
            )
        }
    }
}