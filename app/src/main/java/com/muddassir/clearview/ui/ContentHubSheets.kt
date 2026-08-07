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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.muddassir.clearview.R
import com.muddassir.clearview.media.model.MediaChannelUpdate
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.quran.data.QuranJsonParser
import com.muddassir.clearview.quran.model.QuranVerse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings bottom sheet (opened from the Quran tab's gear icon): the verse
 * refresh interval (presets + a custom slider), the Media / Quran
 * notification toggles, and a card opening the bookmarks manager. Permission
 * is requested when a toggle is turned ON without the OS permission, and once
 * on first open if a toggle already defaults ON.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuranSettingsSheet(state: ContentHubState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val presets = VERSE_INTERVAL_OPTIONS
    val interval = state.refreshIntervalHours

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
    // missing — ask once while the settings sheet is open. Granting from this
    // auto-prompt must ALSO kick an immediate media check, otherwise channels
    // that already uploaded wait up to an hour for the periodic worker.
    LaunchedEffect(Unit) {
        if ((state.mediaNotificationsEnabled || state.quranNotificationsEnabled) &&
            needsNotificationPermission(context)
        ) {
            pendingPermissionApply = { granted ->
                if (granted && state.mediaNotificationsEnabled) {
                    // Same as flipping the media toggle ON: kick an immediate
                    // check AND refresh the in-app "Latest Updates" feed.
                    MediaWorkScheduler.checkNow(context)
                    state.refreshMediaUpdates()
                }
            }
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
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(R.plurals.quran_verse_refresh_note_hours, interval, interval),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Bookmarks manager ──
            // Closes settings first so the two sheets never stack on screen.
            Card(
                onClick = {
                    onDismiss()
                    state.showBookmarksSheet = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.quran_bookmarks_view),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.quran_bookmarks_note,
                                state.bookmarkCount,
                                state.bookmarkCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Search modes for the full-screen Quran search. */
private enum class QuranSearchMode { SEARCH, SURAH }

/**
 * Full-screen Quran search (opened from the Quran tab's search icon): a search
 * field pinned in the top bar with real-time debounced results (matches
 * highlighted), plus a "Surah" browse mode listing all 114 surahs for quick
 * jumps. Tapping a result (or surah) opens it as the current verse.
 *
 * Implemented as a full-screen dialog so results get the whole screen instead
 * of fighting a bottom sheet for space with the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranSearchScreen(state: ContentHubState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<QuranVerse>?>(null) }
    var mode by remember { mutableStateOf(QuranSearchMode.SEARCH) }
    var searching by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // Debounced search; Surah mode (and blank query) show their own content.
    LaunchedEffect(query, mode) {
        if (mode == QuranSearchMode.SURAH || query.isBlank()) {
            searching = false
            results = null
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        results = withContext(Dispatchers.IO) { state.searchQuran(query) }
        searching = false
    }

    // Autofocus + open the keyboard the moment the screen opens. The short
    // delay lets the dialog window attach before the IME is asked to show —
    // calling show() in the same frame is silently dropped on some devices.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        delay(150)
        keyboard?.show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Full width + edge-to-edge so imePadding() below actually receives the
        // IME insets and the results list shrinks above the keyboard.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ── Top bar: back + search field ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.quran_search_back)
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                stringResource(
                                    if (mode == QuranSearchMode.SURAH) R.string.quran_search_hint_surah
                                    else R.string.quran_search_hint
                                )
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.quran_search_clear)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                    )
                }

                // ── Mode toggle: Search | Surah ──
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == QuranSearchMode.SEARCH,
                        onClick = { mode = QuranSearchMode.SEARCH },
                        label = { Text(stringResource(R.string.quran_search_mode_search)) }
                    )
                    FilterChip(
                        selected = mode == QuranSearchMode.SURAH,
                        onClick = { mode = QuranSearchMode.SURAH },
                        label = { Text(stringResource(R.string.quran_search_mode_surah)) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                when (mode) {
                    QuranSearchMode.SURAH -> SurahBrowseList(
                        state = state,
                        query = query,
                        onOpenVerse = {
                            state.goToVerse(it)
                            onDismiss()
                        }
                    )

                    QuranSearchMode.SEARCH -> {
                        // Capture the state once into an immutable local: `results`
                        // is a mutable state that becomes null when the query is
                        // cleared, and the lazy list content below is evaluated
                        // AFTER this `when` is chosen — reading `results!!` there
                        // would NPE on that transition.
                        val list = results
                        when {
                            searching && list == null -> Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.quran_search_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            list == null -> Text(
                                text = stringResource(R.string.quran_search_idle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                            list.isEmpty() -> Text(
                                text = stringResource(R.string.quran_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                            else -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.quran_search_results_count,
                                            list.size,
                                            list.size
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Refining the query keeps previous results
                                    // visible; a small spinner shows the refresh.
                                    if (searching) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(list, key = { "${it.surahNumber}:${it.ayahNumber}" }) { verse ->
                                        VerseSearchRow(
                                            verse = verse,
                                            highlight = query,
                                            onClick = {
                                                keyboard?.hide()
                                                state.goToVerse(verse)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * All 114 surahs with number + transliterated name + translation, filtered
 * live by the shared search field ([query]); tapping one opens its first
 * verse (via the reference search, so it also enriches the Arabic text). The
 * names come from [QuranJsonParser] — pure, no network — so the list renders
 * and filters immediately; only the jump needs the translation cache.
 */
@Composable
private fun SurahBrowseList(
    state: ContentHubState,
    query: String,
    onOpenVerse: (QuranVerse) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read via stringResource (not context.getString) so the value tracks
    // configuration changes — lint treats context reads inside composables as
    // an error since they aren't recomposed when the configuration changes.
    val verseUnavailable = stringResource(R.string.quran_verse_unavailable)

    // Filter the 114 surahs by the shared search field: a blank query shows
    // all of them; a pure number matches the surah number exactly ("2" →
    // Surah 2); anything else is a case-insensitive substring match on the
    // transliterated name or the English translation (e.g. "baqara", "cow",
    // "ya"). All from [QuranJsonParser] — pure local data, so filtering is
    // instant with no I/O.
    val q = query.trim()
    val surahs = remember(q) {
        if (q.isEmpty()) {
            (1..114).toList()
        } else {
            val lower = q.lowercase()
            val numeric = q.all { it.isDigit() }
            (1..114).filter { number ->
                (numeric && number.toString() == q) ||
                    QuranJsonParser.surahName(number).lowercase().contains(lower) ||
                    QuranJsonParser.surahTranslation(number).lowercase().contains(lower)
            }
        }
    }

    if (surahs.isEmpty()) {
        Text(
            text = stringResource(R.string.quran_surah_no_matches),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        items(surahs, key = { it }) { number ->
            val name = QuranJsonParser.surahName(number)
            val translation = QuranJsonParser.surahTranslation(number)
            Card(
                onClick = {
                    scope.launch {
                        val first = withContext(Dispatchers.IO) {
                            state.searchQuran("$number:1").firstOrNull()
                        }
                        if (first != null) {
                            onOpenVerse(first)
                        } else {
                            // Not cached yet — the jump can't resolve.
                            Toast.makeText(context, verseUnavailable, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Two-digit number badge.
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = number.toString().padStart(2, '0'),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (translation.isNotBlank()) {
                            Spacer(Modifier.height(1.dp))
                            Text(
                                text = translation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A single verse row (search result or bookmark): reference, Arabic + English
 * text, tap to open. Pass [onRemove] to show a trailing remove button (used by
 * the bookmarks manager); nested clicks are consumed, so tapping remove never
 * opens the verse. Pass [highlight] to bold/color matching substrings in the
 * English text (the search query / bookmark filter).
 */
@Composable
private fun VerseSearchRow(
    verse: QuranVerse,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    highlight: String? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${verse.surahNumber}:${verse.ayahNumber} · ${verse.surahName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (onRemove != null) {
                    // Default IconButton size keeps the 48dp touch target.
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.quran_bookmarks_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (verse.arabicText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = verse.arabicText,
                    fontSize = 18.sp,
                    lineHeight = 30.sp,
                    fontFamily = FontFamily.Serif,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = highlightMatches(verse.text, highlight, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Returns [text] with every case-insensitive occurrence of [query] styled with
 * [color] + bold (for search/bookmark result highlighting). Plain when [query]
 * is blank or purely numeric/reference-shaped ("2:255" / "255" lookups match
 * verse numbers, so highlighting text would be noise).
 */
private fun highlightMatches(text: String, query: String?, color: Color): AnnotatedString {
    val q = query?.trim().orEmpty()
    if (q.isEmpty() || q.all { it.isDigit() || it == ':' || it == '.' || it.isWhitespace() }) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var index = 0
        val lowerText = text.lowercase()
        val lowerQuery = q.lowercase()
        while (index < text.length) {
            val match = lowerText.indexOf(lowerQuery, index)
            if (match < 0) {
                append(text, index, text.length)
                break
            }
            append(text, index, match)
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append(text, match, match + q.length)
            }
            index = match + q.length
        }
    }
}

/**
 * Bookmarks manager bottom sheet (opened from the settings sheet's "View
 * bookmarks" card): every saved verse with a local filter, tap-to-open and
 * per-item remove. Removing a bookmark that is currently displayed also
 * updates the top-bar bookmark icon (via [ContentHubState.removeBookmark]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(state: ContentHubState, onDismiss: () -> Unit) {
    var bookmarks by remember { mutableStateOf<List<QuranVerse>?>(null) }
    var query by remember { mutableStateOf("") }
    // Verse awaiting removal confirmation (null = no dialog). Removing a
    // bookmark is destructive, so the sheet always asks Remove / Cancel first.
    var pendingRemove by remember { mutableStateOf<QuranVerse?>(null) }

    // Load once when the sheet opens (null = still loading).
    LaunchedEffect(Unit) {
        bookmarks = withContext(Dispatchers.IO) { state.bookmarkedVerses() }
    }

    // Local filter over the already-loaded list (bookmarks are few, so
    // re-filtering in memory on every keystroke is instant).
    val filtered = remember(bookmarks, query) {
        val list = bookmarks ?: return@remember emptyList()
        if (query.isBlank()) list
        else {
            val lower = query.trim().lowercase()
            list.filter {
                it.text.lowercase().contains(lower) ||
                    it.surahName.lowercase().contains(lower) ||
                    "${it.surahNumber}:${it.ayahNumber}".contains(lower)
            }
        }
    }

    // Same stable-local pattern as QuranSearchScreen: `bookmarks` is a nullable
    // mutable state and the lazy list / click lambdas must not read `!!` on it
    // after the `when` is chosen. Declared at function level so the remove
    // confirmation dialog below can also use it.
    val list = bookmarks

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.quran_bookmarks_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.quran_bookmarks_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.quran_search_clear)
                            )
                        }
                    }
                },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            when {
                list == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.quran_bookmarks_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                list.isEmpty() -> Text(
                    text = stringResource(R.string.quran_bookmarks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                filtered.isEmpty() -> Text(
                    text = stringResource(R.string.quran_bookmarks_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                else -> {
                    Text(
                        text = pluralStringResource(
                            R.plurals.quran_bookmarks_count,
                            list.size,
                            list.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { "${it.surahNumber}:${it.ayahNumber}" }) { verse ->
                            VerseSearchRow(
                                verse = verse,
                                highlight = query,
                                onClick = {
                                    state.goToVerse(verse)
                                    onDismiss()
                                },
                                onRemove = { pendingRemove = verse }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Remove confirmation (Remove / Cancel) ──────────────────────
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.quran_bookmarks_remove_confirm_title)) },
            text = { Text(stringResource(R.string.quran_bookmarks_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    state.removeBookmark(target.surahNumber, target.ayahNumber)
                    bookmarks = bookmarks?.filterNot {
                        it.surahNumber == target.surahNumber &&
                            it.ayahNumber == target.ayahNumber
                    } ?: emptyList()
                    pendingRemove = null
                }) {
                    Text(
                        text = stringResource(R.string.quran_bookmarks_remove_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.quran_cancel))
                }
            }
        )
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
