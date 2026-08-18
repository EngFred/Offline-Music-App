package com.engfred.musicplayer.feature_settings.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_settings.presentation.screens.DEVELOPER_PHONE_LOCAL
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Message

@Composable
fun ContactDeveloperSection(
    onWhatsApp: () -> Unit,
    onCall: () -> Unit,
    onSms: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ContactActionRow(
            emoji = "💬",
            badgeColor = Color(0xFF25D366),
            title = "WhatsApp",
            subtitle = "Open a chat directly in WhatsApp",
            onClick = onWhatsApp
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        ContactActionRow(
            icon = Icons.Rounded.Call,
            badgeColor = MaterialTheme.colorScheme.primary,
            title = "Call",
            subtitle = "Give me a ring on $DEVELOPER_PHONE_LOCAL",
            onClick = onCall
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        ContactActionRow(
            icon = Icons.Rounded.Message,
            badgeColor = MaterialTheme.colorScheme.secondary,
            title = "SMS",
            subtitle = "Send a text message instead",
            onClick = onSms
        )
    }
}