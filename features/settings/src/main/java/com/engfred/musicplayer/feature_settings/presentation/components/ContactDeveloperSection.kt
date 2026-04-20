package com.engfred.musicplayer.feature_settings.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engfred.musicplayer.feature_settings.presentation.screens.DEVELOPER_PHONE_LOCAL

/**
 * "Contact the developer" section offering three touch-points on the same
 * number: WhatsApp chat, a direct phone call, and an SMS message.
 *
 * The section header follows the same icon + title + subtitle pattern used
 * throughout the page so it feels visually consistent.
 */
@Composable
fun ContactDeveloperSection(
    onWhatsApp: () -> Unit,
    onCall: () -> Unit,
    onSms: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Section header ────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SupportAgent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Contact Developer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Reach out for feedback, bugs, or just to say hi",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── WhatsApp ──────────────────────────────────────────────────────────
        ContactActionRow(
            emoji = "💬",
            badgeColor = Color(0xFF25D366),           // WhatsApp brand green
            title = "WhatsApp",
            subtitle = "Open a chat directly in WhatsApp",
            onClick = onWhatsApp
        )

        // ── Phone call ────────────────────────────────────────────────────────
        ContactActionRow(
            icon = Icons.Rounded.Call,
            badgeColor = MaterialTheme.colorScheme.primary,
            title = "Call",
            subtitle = "Give me a ring on $DEVELOPER_PHONE_LOCAL",
            onClick = onCall
        )

        // ── SMS ───────────────────────────────────────────────────────────────
        ContactActionRow(
            icon = Icons.Rounded.Message,
            badgeColor = MaterialTheme.colorScheme.secondary,
            title = "SMS",
            subtitle = "Send a text message instead",
            onClick = onSms
        )
    }
}