package com.muddassir.clearview.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Tools Screen: clean secondary functionality hub.
 *
 * Flat list organization:
 * - Productivity (Todo, Phone Limit, Dhikr)
 * - Spiritual & Media (Live Broadcasts, Saved Verses)
 * - Digital Protection (ClearView Shield)
 * - Settings & Preferences
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onOpenTodo: () -> Unit,
    onOpenPhoneLimit: () -> Unit,
    onOpenDhikr: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenShield: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tools & Utilities",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item {
                ToolsSectionHeader("PRODUCTIVITY & FOCUS")
                ToolsGroup {
                    ToolsRow(
                        icon = Icons.Filled.CheckCircle,
                        title = "Tasks & Reminders",
                        subtitle = "Daily todos, alarms and habits",
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onOpenTodo
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp
                    )
                    ToolsRow(
                        icon = Icons.Filled.HourglassBottom,
                        title = "Phone Focus Limit",
                        subtitle = "Daily screen timer and lock",
                        iconTint = Color(0xFFF59E0B),
                        onClick = onOpenPhoneLimit
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp
                    )
                    ToolsRow(
                        icon = Icons.Filled.Fingerprint,
                        title = "Dhikr Counter",
                        subtitle = "Digital tasbih for daily remembrance",
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onOpenDhikr
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            item {
                ToolsSectionHeader("SPIRITUAL & BROADCASTS")
                ToolsGroup {
                    ToolsRow(
                        icon = Icons.Filled.LiveTv,
                        title = "Makkah & Madinah Live",
                        subtitle = "Official 24/7 Haramain broadcasts",
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onOpenLive
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp
                    )
                    ToolsRow(
                        icon = Icons.Filled.Bookmark,
                        title = "Saved Verses",
                        subtitle = "Your bookmarked reflections",
                        iconTint = Color(0xFFF59E0B),
                        onClick = onOpenBookmarks
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            item {
                ToolsSectionHeader("SECURITY & SELF-CONTROL")
                ToolsGroup {
                    ToolsRow(
                        icon = Icons.Filled.Shield,
                        title = "ClearView Shield",
                        subtitle = "Keyword blocker, strict mode & app lock",
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onOpenShield
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            item {
                ToolsSectionHeader("APPLICATION")
                ToolsGroup {
                    ToolsRow(
                        icon = Icons.Filled.Notifications,
                        title = "Notification History",
                        subtitle = "Channel updates and reminder inbox",
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenNotifications
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp
                    )
                    ToolsRow(
                        icon = Icons.Filled.Settings,
                        title = "Settings",
                        subtitle = "Reading frequency, theme and data",
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onOpenSettings
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ToolsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun ToolsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun ToolsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = iconTint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
