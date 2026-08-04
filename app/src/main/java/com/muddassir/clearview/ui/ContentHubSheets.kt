package com.muddassir.clearview.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.muddassir.clearview.R
import com.muddassir.clearview.media.model.MediaChannelUpdate
import kotlin.math.roundToInt

/**
 * Settings bottom sheet (opened from the Quran tab's gear icon): the verse
 * refresh interval (presets + a custom slider) and the Media / Quran
 * notification toggles. Permission is requested when a toggle is turned ON
 * without the OS permission, and once on first open if a toggle already
 * defaults ON.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuranSettingsSheet(state: ContentHubState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val presets = VERSE_INTERVAL_OPTIONS
    val interval = state.refreshIntervalHours
    // The slider mirrors the current interval when it's custom; it starts at a
    // non-preset value (7) for presets so a fresh "Custom" drag begins
    // somewhere that is visibly custom.
    var customSlider by remember(interval) {
        mutableStateOf(if (interval in presets) 7f else interval.toFloat())
    }

    // Notification permission (Android 13+). The launcher remembers WHICH
    // toggle asked, so a grant applies it to the right setting.
    val deniedMessage = stringResource(R.string.media_notification_permission_denied)
    var pendingPermissionApply by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPermissionApply(true)
        } else {
            // Turn the requesting toggle back OFF so we never re-prompt on the
            // next visit, then explain why.
            pendingPermissionApply(false)
            Toast.makeText(context, deniedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    // Fresh-install courtesy: a toggle defaults ON but the OS permission may be
    // missing — ask once while the settings sheet is open.
    LaunchedEffect(Unit) {
        if ((state.mediaNotificationsEnabled || state.quranNotificationsEnabled) &&
            needsNotificationPermission(context)
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.quran_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.quran_verse_refresh_frequency),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { hours ->
                    FilterChip(
                        selected = hours == interval,
                        onClick = { if (hours != interval) state.changeInterval(context, hours) },
                        label = { Text(stringResource(R.string.quran_verse_hour_short, hours)) }
                    )
                }
                FilterChip(
                    selected = interval !in presets,
                    onClick = {
                        if (interval in presets) {
                            state.changeInterval(context, customSlider.roundToInt().coerceIn(1, 72))
                        }
                    },
                    label = { Text(stringResource(R.string.quran_verse_custom)) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(R.plurals.quran_verse_refresh_note_hours, interval, interval),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Custom range slider (1–72 hours), applied live.
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.quran_verse_custom),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.quran_verse_custom_hours,
                        customSlider.roundToInt(),
                        customSlider.roundToInt()
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = customSlider,
                onValueChange = { customSlider = it },
                // Apply only when the drag ends: changeInterval persists the
                // interval AND reschedules the periodic WorkManager work, so
                // firing it on every drag tick would spam the scheduler.
                onValueChangeFinished = {
                    state.changeInterval(context, customSlider.roundToInt().coerceIn(1, 72))
                },
                valueRange = 1f..72f,
                steps = 70
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Media notifications toggle ──
            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = if (state.mediaNotificationsEnabled) Icons.Filled.Notifications
                        else Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = if (state.mediaNotificationsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                },
                title = stringResource(R.string.media_notifications),
                note = stringResource(R.string.media_notifications_note),
                checked = state.mediaNotificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingPermissionApply = { granted ->
                            state.setMediaNotifications(granted, context)
                        }
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        state.setMediaNotifications(enabled, context)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Quran notifications toggle ──
            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = if (state.quranNotificationsEnabled) Icons.Filled.Notifications
                        else Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = if (state.quranNotificationsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                },
                title = stringResource(R.string.quran_notifications),
                note = stringResource(R.string.quran_notifications_note),
                checked = state.quranNotificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingPermissionApply = { granted ->
                            state.setQuranNotifications(granted)
                        }
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        state.setQuranNotifications(enabled)
                    }
                }
            )
        }
    }
}

/**
 * Notifications bottom sheet (opened from the Quran tab's bell icon): every
 * channel update ("… has an update"), with tap-to-play and per-item dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(state: ContentHubState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.quran_notifications_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.mediaUpdatesLoading && state.mediaUpdates.isEmpty() -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.media_updates_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.mediaUpdates.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.media_no_updates_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        state.mediaUpdates.forEach { update ->
                            NotificationRow(
                                update = update,
                                onClick = {
                                    onDismiss()
                                    state.playMediaUpdate(update)
                                },
                                onDismissUpdate = { state.dismissUpdate(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A single update row inside the notifications sheet. */
@Composable
private fun NotificationRow(
    update: MediaChannelUpdate,
    onClick: () -> Unit,
    onDismissUpdate: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = stringResource(R.string.media_has_update, update.channelName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (update.latestVideoTitle.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = update.latestVideoTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = { onDismissUpdate(update.latestVideoId) }) {
            Text(stringResource(R.string.media_update_dismiss))
        }
    }
}

/** One labeled toggle row used by the settings sheet. */
@Composable
private fun SettingsToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Whether the app still needs the runtime notification permission (Android 13+). */
private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}
