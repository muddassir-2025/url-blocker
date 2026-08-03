package com.example.url_blocker.quran.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.url_blocker.R
import com.example.url_blocker.media.model.MediaChannelUpdate
import com.example.url_blocker.quran.model.QuranVerse
import com.example.url_blocker.ui.VERSE_INTERVAL_OPTIONS

/**
 * Quran tab (the app's home page): the complete current verse — surah name,
 * reference, the Arabic verse (when the Arabic edition is cached) and the
 * English translation — plus Previous/Next verse navigation (wrapping across
 * surah boundaries), subtle actions (New Verse / Copy), the refresh-frequency
 * picker, the Media Notifications toggle and the Latest Updates feed
 * ("… has an update") from the user's saved channels.
 *
 * LAYOUT NOTE: every child is a plain element of the vertically scrollable
 * Column — no Box overlays, no stacked content. The Previous/Next row is part
 * of that normal flow (below the reference chip, above the translation), so it
 * pushes the content below it down naturally and nothing ever overlaps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuranTab(
    verse: QuranVerse?,
    isLoading: Boolean,
    refreshIntervalHours: Int,
    onRefreshIntervalChange: (Int) -> Unit,
    onNewVerse: () -> Unit,
    onCopyVerse: () -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    mediaNotificationsEnabled: Boolean,
    onMediaNotificationsToggle: (Boolean) -> Unit,
    mediaUpdates: List<MediaChannelUpdate>,
    mediaUpdatesLoading: Boolean,
    onPlayUpdate: (MediaChannelUpdate) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val intervalOptions = VERSE_INTERVAL_OPTIONS

    // Notification permission (Android 13+): only request it when the user
    // actually turns the toggle ON; if they deny it, keep the toggle off.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onMediaNotificationsToggle(true)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.media_notification_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
            // Permission denied = notifications can never arrive, so reflect
            // that honestly in the toggle. (Also prevents re-prompting the
            // dialog on every visit to the home tab.)
            onMediaNotificationsToggle(false)
        }
    }

    // Fresh installs: the toggle defaults ON but the OS permission isn't — ask
    // once when the home tab first shows. After that the state settles (granted
    // → nothing to ask; denied → toggle flips off), so this never re-prompts.
    LaunchedEffect(Unit) {
        if (mediaNotificationsEnabled && needsNotificationPermission(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            isLoading -> {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Loading verse…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            verse == null -> {
                Spacer(Modifier.height(48.dp))
                Text("🕋", fontSize = 56.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Verses are being downloaded.\nOpen the app once to download the full translation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            else -> {
                val v = verse!!
                // Previous/Next navigation is part of the normal vertical
                // layout (no overlay/stack): the row sits under the reference
                // chip and above the translation, pushing content down.
                VerseDisplay(
                    v = v,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onNewVerse = onNewVerse,
                    onCopyVerse = onCopyVerse
                )

                Spacer(Modifier.height(32.dp))

                HorizontalDivider()

                Spacer(Modifier.height(24.dp))

                // Refresh frequency picker.
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
                    intervalOptions.forEach { hours ->
                        FilterChip(
                            selected = hours == refreshIntervalHours,
                            onClick = {
                                if (hours != refreshIntervalHours) onRefreshIntervalChange(hours)
                            },
                            label = { Text(context.getString(R.string.quran_verse_hour_short, hours)) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = context.resources.getQuantityString(
                        R.plurals.quran_verse_refresh_note_hours,
                        refreshIntervalHours,
                        refreshIntervalHours
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(28.dp))

                HorizontalDivider()

                Spacer(Modifier.height(24.dp))
            }
        }

        // ── Media Notifications toggle ─────────────────────────────
        // Shown on every state (loading / empty / verse) — the home page.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (mediaNotificationsEnabled) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (mediaNotificationsEnabled) Icons.Filled.Notifications
                    else Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = if (mediaNotificationsEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.media_notifications),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.media_notifications_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = mediaNotificationsEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && needsNotificationPermission(context)) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onMediaNotificationsToggle(enabled)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Latest Updates feed (channel updates) ──────────────────
        Text(
            text = stringResource(R.string.media_latest_updates),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        when {
            mediaUpdatesLoading && mediaUpdates.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Checking for updates…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            mediaUpdates.isEmpty() -> {
                Text(
                    text = stringResource(R.string.media_no_updates_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> {
                mediaUpdates.forEach { update ->
                    UpdateRow(update = update, onClick = { onPlayUpdate(update) })
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The full verse reading view: surah header, verse reference, Arabic verse +
 * reference chip, Previous/Next navigation, English translation and the
 * action buttons — all plain Column children in order, so they stack
 * vertically with normal spacing and never overlap.
 */
@Composable
private fun VerseDisplay(
    v: QuranVerse,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNewVerse: () -> Unit,
    onCopyVerse: () -> Unit
) {
    Spacer(Modifier.height(8.dp))

    // Surah name + translation.
    Text(
        text = v.surahName,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    if (v.surahTranslation.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = v.surahTranslation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Surah ${v.surahNumber} · Ayah ${v.ayahNumber}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )

    Spacer(Modifier.height(24.dp))

    // Arabic verse (renders RTL automatically from the script).
    if (v.arabicText.isNotBlank()) {
        Text(
            text = v.arabicText,
            fontSize = 30.sp,
            lineHeight = 50.sp,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "${v.surahNumber}:${v.ayahNumber}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    // Previous / Next navigation — small, subtle, centered, directly under the
    // reference chip and above the English translation. A plain centered Row in
    // the normal vertical flow (pushes the translation down; never overlays it).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.quran_verse_previous),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = onNext,
            enabled = canGoNext,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.quran_verse_next),
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }

    Spacer(Modifier.height(28.dp))

    // English translation — never truncated.
    Text(
        text = "“${v.text}”",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Serif,
        lineHeight = 32.sp,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(32.dp))

    // Subtle actions.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(
            onClick = onNewVerse,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.quran_verse_new))
        }
        OutlinedButton(
            onClick = onCopyVerse,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.quran_verse_copy))
        }
    }
}

/** Whether the app still needs the runtime notification permission (Android 13+). */
private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}

/** One "… has an update" row: channel name, latest video title + when. */
@Composable
private fun UpdateRow(update: MediaChannelUpdate, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
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
            if (update.publishedAtEpochMillis > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        update.publishedAtEpochMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
