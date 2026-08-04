package com.muddassir.clearview.media.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.media.data.MediaLibraryStore
import com.muddassir.clearview.media.data.MediaRepository
import com.muddassir.clearview.media.data.WatchProgressStore
import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedLibraryFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.SavedChannel
import com.muddassir.clearview.media.model.datePickerMillisToLocalStart
import com.muddassir.clearview.media.util.MediaVideos
import com.muddassir.clearview.media.util.applyFeedFilter
import com.muddassir.clearview.media.util.feedFilterSummary
import com.muddassir.clearview.media.util.formatViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media tab — a Subscriptions-style feed:
 *
 *  1. Channel avatar strip on top (tap an avatar to filter to that channel,
 *     tap again or tap "All" to show everything; "+" adds a channel).
 *  2. Shorts section (vertical cards, newest first).
 *  3. Long-video list (YouTube-style rows) — each thumbnail carries a red
 *     watch-progress bar and a "Watched" badge once ≥90% was viewed (progress
 *     comes from the in-app player's real getCurrentTime/getDuration).
 *
 * The feed aggregates EVERY saved channel (cached data first for an instant
 * first paint, then a background refresh of all feeds merged newest-first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTab(
    onPlayVideo: (MediaVideo, List<MediaVideo>, Int) -> Unit,
    /** Fired when the Media tab is shown (marks channel updates as seen). */
    onMediaOpened: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context.applicationContext) }
    val progressStore = remember { WatchProgressStore(context.applicationContext) }
    val libraryStore = remember { MediaLibraryStore(context.applicationContext) }
    // Bumped whenever the library changes (bookmark / hide / manual add) so
    // the merged feed and the Continue Watching row recompute.
    var libraryRevision by remember { mutableIntStateOf(0) }

    var channels by remember { mutableStateOf(repository.getSavedChannels()) }
    var filterChannelId by remember { mutableStateOf<String?>(null) }
    var videos by remember { mutableStateOf<List<MediaVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showingCached by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Pull-to-refresh: bumping the token re-runs the feed load. The load effect
    // is keyed on channelIdsKey (channel add/remove) + this token, so a manual
    // refresh never needs the channels to change.
    var refreshToken by remember { mutableIntStateOf(0) }
    // When the last successful refresh completed (epoch ms) — drives the
    // "Updated Xm ago" hint under the feed header.
    var lastRefreshedAt by remember { mutableStateOf(0L) }
    // Bumped every minute while a refresh time exists so the "Updated Xm ago"
    // hint re-renders over time instead of freezing at "0 minutes ago".
    var clockTick by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddVideoDialog by remember { mutableStateOf(false) }
    var showHiddenDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<SavedChannel?>(null) }
    // The feed filter is persisted per context (survives restarts): the All
    // Feed filter when no channel is selected, and each channel's own filter
    // when one is. Switching channels swaps to that channel's saved filter.
    var feedFilter by remember(filterChannelId) {
        mutableStateOf(repository.getFeedFilter(filterChannelId))
    }
    var showFilterSheet by remember { mutableStateOf(false) }
    // Feed search: a live title/channel filter applied on top of the current
    // feed (All Feed or a selected channel) — never refetches.
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val saveFilter: (FeedFilter) -> Unit = { f ->
        feedFilter = f
        repository.setFeedFilter(f, filterChannelId)
    }

    // The user opened the Media tab — its channel updates count as seen.
    LaunchedEffect(Unit) { onMediaOpened() }

    // Keep the "Updated Xm ago" hint fresh: every minute (while a refresh
    // time exists) bump clockTick, which recomposes the header text.
    LaunchedEffect(lastRefreshedAt) {
        while (lastRefreshedAt > 0L) {
            delay(60_000L)
            clockTick++
        }
    }

    // Stable key: the channel IDS only. The avatar backfill below replaces the
    // list object with the same ids (avoids keying the feed load on the list
    // identity — which would re-fetch every feed whenever an avatar landed).
    val channelIdsKey = channels.map { it.channelId }.sorted().joinToString(",")

    // Aggregate load: every channel's cached videos first (instant), then a
    // background refresh of all feeds merged into one list. Re-runs only when
    // a channel is added or removed.
    LaunchedEffect(channelIdsKey, refreshToken) {
        if (channels.isEmpty()) {
            videos = emptyList()
            isLoading = false
            errorMessage = null
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        val cached = withContext(Dispatchers.IO) { repository.getAllCachedVideos(channels) }
        if (cached.isNotEmpty()) {
            videos = cached
            showingCached = true
        }
        val fresh = repository.refreshAllVideos(channels)
        if (fresh != null) {
            videos = fresh
            showingCached = false
            lastRefreshedAt = System.currentTimeMillis()
        } else if (cached.isEmpty()) {
            errorMessage = "Couldn't load videos. Check your internet connection."
        }
        isLoading = false
    }

    // Best-effort avatar backfill (separate effect so an avatar landing never
    // re-triggers the feed load). Runs on open and after add/remove; the
    // updated list keeps the same channel ids so the feed effect stays put.
    LaunchedEffect(channelIdsKey) {
        if (channels.isEmpty()) return@LaunchedEffect
        val withAvatars = withContext(Dispatchers.IO) { repository.fillMissingAvatars(channels) }
        if (withAvatars != null) channels = withAvatars
    }

    // The feed plus the user's library (bookmarked / manually added videos —
    // which can be older than the RSS window), merged by id (RSS wins).
    // Hidden videos are filtered out everywhere EXCEPT the Hidden manager.
    val bookmarkedVideos = remember(libraryRevision) { libraryStore.getBookmarkedVideos() }
    val manualVideos = remember(libraryRevision) { libraryStore.getManuallyAddedVideos() }
    val hiddenVideos = remember(libraryRevision) { libraryStore.getHiddenVideos() }
    // Hidden videos are scoped to the current feed context: in a channel feed
    // only THAT channel's hidden videos are shown in the manager; in the All
    // Feed every channel's hidden videos appear.
    val contextHiddenVideos = remember(hiddenVideos, filterChannelId) {
        if (filterChannelId == null) hiddenVideos
        else hiddenVideos.filter { it.channelId == filterChannelId }
    }
    val hiddenIds = remember(hiddenVideos) { hiddenVideos.map { it.videoId }.toSet() }
    val mergedVideos = remember(videos, bookmarkedVideos, manualVideos) {
        MediaVideos.merge(videos, bookmarkedVideos + manualVideos)
    }
    val visibleVideos = remember(mergedVideos, hiddenIds) {
        mergedVideos.filterNot { it.videoId in hiddenIds }
    }

    // Channel strip filter + the All Feed filters are applied locally to the
    // already-loaded videos — no refetching.
    val channelVideos = remember(visibleVideos, filterChannelId) {
        if (filterChannelId == null) visibleVideos
        else visibleVideos.filter { it.channelId == filterChannelId }
    }
    val displayed = remember(channelVideos, feedFilter, libraryRevision) {
        applyFeedFilter(
            channelVideos,
            feedFilter,
            progressOf = { progressStore.get(it) },
            bookmarkedOf = { libraryStore.isBookmarked(it) }
        )
    }
    // Feed search: matches titles and channel names (case-insensitive) over
    // the channel + feed-filtered list; a blank query leaves the feed alone.
    // The shorts queue built from `shorts` below inherits the search scope, so
    // swiping through results stays within the matches.
    val searchResults = remember(displayed, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) displayed
        else displayed.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.channelName.contains(q, ignoreCase = true)
        }
    }
    val shorts = searchResults.filter { it.isShort }
    val longs = searchResults.filterNot { it.isShort }
    val isSearching = searchActive && searchQuery.isNotBlank()

    // ── Continue Watching: partially watched LONG videos in the current
    // channel context, capped so the section stays compact (Shorts never
    // appear here — they're meant to be watched in one sitting).
    val continueWatching = remember(channelVideos, hiddenIds, libraryRevision) {
        channelVideos
            .filter { v ->
                !v.isShort && !v.isLive &&
                    (progressStore.get(v.videoId)?.let {
                        it >= 0.02f && it < 0.9f
                    } ?: false)
            }
            .sortedByDescending { it.publishedAtEpochMillis }
            .take(6)
    }
    val playShort: (MediaVideo) -> Unit = { video ->
        val idx = shorts.indexOfFirst { it.videoId == video.videoId }
        onPlayVideo(video, shorts, idx)
    }
    val playLong: (MediaVideo) -> Unit = { video ->
        onPlayVideo(video, emptyList(), -1)
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Channel avatar strip (Subscriptions-style) ─────────────
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item(key = "all") {
                AllAvatar(
                    selected = filterChannelId == null,
                    onClick = { filterChannelId = null }
                )
            }
            items(channels, key = { it.channelId }) { channel ->
                ChannelAvatar(
                    channel = channel,
                    selected = filterChannelId == channel.channelId,
                    onClick = {
                        filterChannelId =
                            if (filterChannelId == channel.channelId) null else channel.channelId
                    },
                    onRemove = { pendingRemove = channel }
                )
            }
            item(key = "add") {
                AddAvatar(onClick = { showAddDialog = true })
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── All Feed header: filter button (highlighted when active) + summary ──
        // Shown whenever there is a feed at all — including when a filter hides
        // every video (so it can always be reset). Hidden during the empty
        // no-channels / loading states.
        if (videos.isNotEmpty()) {
            FeedHeader(
                filter = feedFilter,
                resultCount = searchResults.size,
                hiddenCount = contextHiddenVideos.size,
                canAddVideo = filterChannelId != null,
                searchActive = searchActive,
                searchQuery = searchQuery,
                // "Updated Xm ago" — recomputed whenever a refresh lands OR
                // the minute ticker bumps (clockTick read forces recompose).
                updatedAgo = if (lastRefreshedAt > 0L && !isLoading && clockTick >= 0) {
                    val ago = DateUtils.getRelativeTimeSpanString(
                        lastRefreshedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                    if (ago.startsWith("0 min")) "Updated just now" else "Updated $ago"
                } else null,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = {
                    searchActive = !searchActive
                    searchQuery = ""
                },
                onOpenFilter = { showFilterSheet = true },
                onReset = { saveFilter(FeedFilter()) },
                onOpenHidden = { showHiddenDialog = true },
                onAddVideo = { showAddVideoDialog = true }
            )
        }

        // ── Feed ───────────────────────────────────────────────────
        // Pull-to-refresh wraps the whole feed (including skeleton + empty
        // states — each is scrollable so the gesture always engages).
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { refreshToken++ },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                // First load: no channels → nothing to load (and nothing to
                // refresh). Shown before the skeleton so a channel-less feed
                // doesn't shimmer forever.
                channels.isEmpty() && videos.isEmpty() && !isLoading ->
                    ErrorCard("No channels saved. Tap + Add to save one.")
                // First load with channels: shimmer placeholders while the
                // feeds fetch (never a blank screen).
                isLoading && videos.isEmpty() -> SkeletonFeed()
                errorMessage != null && videos.isEmpty() -> ErrorCard(errorMessage!!)
                videos.isEmpty() && !isLoading -> ErrorCard("No videos yet for your channels.")
                videos.isNotEmpty() && displayed.isNotEmpty() &&
                    searchResults.isEmpty() && isSearching && !isLoading ->
                    ErrorCard("No videos match your search.")
                videos.isNotEmpty() && displayed.isEmpty() && !isLoading ->
                    ErrorCard(
                        if (feedFilter.isActive) "No videos match your filters."
                        else "No videos for this channel yet."
                    )
                else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // ── Continue Watching: partially watched videos, in the
                    // current channel context (All Feed OR a selected channel).
                    // Auto-hides when none remain — and while a search is
                    // active, so results stay focused on the matches.
                    if (continueWatching.isNotEmpty() && !isSearching) {
                        item(key = "continue-header") {
                            SectionHeader(
                                title = "Continue Watching",
                                isLoading = false,
                                showingCached = false
                            )
                        }
                        item(key = "continue-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(continueWatching, key = { it.videoId }) { video ->
                                    ContinueWatchingCard(
                                        video = video,
                                        progressStore = progressStore,
                                        onClick = { playLong(video) }
                                    )
                                }
                            }
                        }
                    }
                    if (shorts.isNotEmpty()) {
                        item(key = "shorts-header") {
                            SectionHeader(
                                title = "Shorts",
                                isLoading = isLoading,
                                showingCached = showingCached
                            )
                        }
                        item(key = "shorts-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shorts, key = { it.videoId }) { video ->
                                    ShortCard(
                                        video = video,
                                        progressStore = progressStore,
                                        isBookmarked = libraryStore.isBookmarked(video.videoId),
                                        isManual = libraryStore.isManuallyAdded(video.videoId),
                                        onClick = { playShort(video) },
                                        onToggleBookmark = {
                                            libraryStore.toggleBookmark(video)
                                            libraryRevision++
                                        },
                                        onHide = {
                                            libraryStore.hideVideo(video)
                                            libraryRevision++
                                        },
                                        onRemoveManual = {
                                            libraryStore.removeManuallyAdded(video.videoId)
                                            libraryRevision++
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (longs.isNotEmpty()) {
                        item(key = "videos-header") {
                            SectionHeader(
                                title = "Videos",
                                isLoading = isLoading,
                                showingCached = showingCached
                            )
                        }
                        items(longs, key = { it.videoId }) { video ->
                            LongVideoCard(
                                video = video,
                                progressStore = progressStore,
                                isBookmarked = libraryStore.isBookmarked(video.videoId),
                                isManual = libraryStore.isManuallyAdded(video.videoId),
                                onClick = { playLong(video) },
                                onToggleBookmark = {
                                    libraryStore.toggleBookmark(video)
                                    libraryRevision++
                                },
                                onHide = {
                                    libraryStore.hideVideo(video)
                                    libraryRevision++
                                },
                                onRemoveManual = {
                                    libraryStore.removeManuallyAdded(video.videoId)
                                    libraryRevision++
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // ── Add channel dialog ─────────────────────────────────────────
    if (showAddDialog) {
        AddChannelDialog(
            repository = repository,
            onAdded = { channel ->
                showAddDialog = false
                channels = repository.getSavedChannels()
                filterChannelId = channel.channelId
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ── Filter bottom sheet ────────────────────────────────────────
    if (showFilterSheet) {
        FilterSheet(
            filter = feedFilter,
            onApply = { applied ->
                saveFilter(applied)
                showFilterSheet = false
            },
            onReset = {
                saveFilter(FeedFilter())
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }

    // ── Hidden videos manager ──────────────────────────────────────
    // Lists only the current context's hidden videos (channel feed → that
    // channel's; All Feed → everything). "Unhide all" follows the same scope:
    // inside a channel it only unhides that channel's hidden videos.
    if (showHiddenDialog) {
        HiddenVideosDialog(
            libraryStore = libraryStore,
            hiddenVideos = contextHiddenVideos,
            onUnhideAll = {
                if (filterChannelId == null) {
                    libraryStore.unhideAll()
                } else {
                    contextHiddenVideos.forEach { libraryStore.unhideVideo(it.videoId) }
                }
                libraryRevision++
            },
            onChanged = { libraryRevision++ },
            onDismiss = { showHiddenDialog = false }
        )
    }

    // ── Add video by URL ───────────────────────────────────────────
    if (showAddVideoDialog) {
        AddVideoDialog(
            repository = repository,
            libraryStore = libraryStore,
            channel = channels.firstOrNull { it.channelId == filterChannelId },
            onAdded = { libraryRevision++ },
            onDismiss = { showAddVideoDialog = false }
        )
    }

    // ── Remove channel confirmation ────────────────────────────────
    pendingRemove?.let { channel ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove channel?") },
            text = { Text("Remove \"${channel.displayName}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.removeChannel(channel.channelId)
                    channels = repository.getSavedChannels()
                    if (filterChannelId == channel.channelId) filterChannelId = null
                    pendingRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    isLoading: Boolean,
    showingCached: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Accent bar — the app's brand color, echoing the player's progress accent.
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        if (showingCached && !isLoading) {
            Text(
                text = "cached",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "All" avatar at the head of the strip — shows every channel's feed. */
@Composable
private fun AllAvatar(selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "All",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "All",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A saved channel: circular avatar (or initials) with its name below. The
 * small ✕ badge in the avatar's corner removes the channel (the inner
 * clickable consumes the tap, so it never also triggers the filter).
 */
@Composable
private fun ChannelAvatar(
    channel: SavedChannel,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            ) {
                if (channel.avatarUrl != null) {
                    RemoteImage(
                        url = channel.avatarUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials(channel.displayName),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // Remove badge (✕) in the corner.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${channel.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "+" entry at the end of the strip — opens the add-channel dialog. */
@Composable
private fun AddAvatar(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add channel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Vertical Shorts card for the horizontal Shorts row — the title and view
 * count are overlaid on the thumbnail over a bottom scrim (YouTube Shorts
 * style), with a small play badge in the corner.
 */
@Composable
private fun ShortCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    isBookmarked: Boolean,
    isManual: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit
) {
    val fraction = remember(video.videoId) { progressStore.get(video.videoId) }
    // A live broadcast has no finite duration to complete — never show a
    // "Watched" badge or progress bar on it, even if stale progress was saved
    // by an older build that misclassified it as a bounded video.
    val watched = !video.isLive && (fraction ?: 0f) >= 0.9f
    val live = video.isLive
    Card(
        onClick = onClick,
        modifier = Modifier.width(150.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
        ) {
            RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
            // Bottom scrim keeps the overlay text legible on any thumbnail.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )
            // Watch status: watched dims the card, partial shows a thin bar.
            if (watched) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "Watched",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            } else if (fraction != null && fraction > 0.02f && !live) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            // Overflow menu (top-right, where the play badge used to be):
            // bookmark / hide / remove manual.
            VideoCardMenu(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                isBookmarked = isBookmarked,
                isManual = isManual,
                onToggleBookmark = onToggleBookmark,
                onHide = onHide,
                onRemoveManual = onRemoveManual
            )
            // Bottom-end stack: duration pill (+ "Manually added" above it).
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isManual) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Manually added",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                DurationBadge(seconds = video.durationSeconds)
            }
            // Title + views overlaid at the bottom.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (video.viewCount > 0L) {
                        formatViews(video.viewCount) + " views"
                    } else {
                        video.channelName.ifBlank { "YouTube" }
                    },
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * YouTube-style long-video row: thumbnail with a red watch-progress bar and a
 * "Watched" badge, title + channel/date to the right.
 */
@Composable
private fun LongVideoCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    isBookmarked: Boolean,
    isManual: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit
) {
    val fraction = remember(video.videoId) { progressStore.get(video.videoId) }
    // A live broadcast has no finite duration to complete — never show a
    // "Watched" badge or progress bar on it, even if stale progress was saved
    // by an older build that misclassified it as a bounded video.
    val watched = !video.isLive && (fraction ?: 0f) >= 0.9f
    val live = video.isLive

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row {
            // ── Thumbnail + progress overlay ──
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f)
            ) {
                RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                // Watched treatment: dim the whole thumbnail FIRST, so every
                // badge (LIVE, menu, duration) stays bright on top — matching
                // the ShortCard ordering.
                if (watched) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                }
                // Live indicator on the thumbnail.
                if (live) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFFD93025)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color.White, CircleShape)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                // Overflow menu (bookmark / hide / remove manual).
                VideoCardMenu(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    isBookmarked = isBookmarked,
                    isManual = isManual,
                    onToggleBookmark = onToggleBookmark,
                    onHide = onHide,
                    onRemoveManual = onRemoveManual
                )
                // Bottom-end stack: duration pill (+ "Manually added" above
                // it when present). Both sit above the watch-progress bar.
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isManual) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        ) {
                            Text(
                                text = "Manually added",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    DurationBadge(seconds = video.durationSeconds)
                }
                if (watched) {
                    // Watched badge (the dim is already applied above).
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Watched",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                } else if (fraction != null && fraction > 0.02f && !live) {
                    // In-progress: YouTube-style thin progress bar + a small
                    // "NN%" pill (top-right) so the watched amount is visible
                    // at a glance.
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // ── Title / channel · views · date ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(10.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(video.channelName.ifBlank { "YouTube" })
                        if (video.viewCount > 0L) {
                            append(" · ").append(formatViews(video.viewCount)).append(" views")
                        }
                        if (video.publishedAtEpochMillis > 0L) {
                            append(" · ").append(
                                DateUtils.getRelativeTimeSpanString(
                                    video.publishedAtEpochMillis,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                ).toString()
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The All Feed heading row: title + filter button (highlighted when active) +
 * an overflow menu (Hidden videos manager, Add video by URL) + active summary
 * and Reset.
 */
@Composable
private fun FeedHeader(
    filter: FeedFilter,
    resultCount: Int,
    hiddenCount: Int,
    canAddVideo: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    /** "Updated Xm ago" text shown under the title row (null = hide). */
    updatedAgo: String? = null,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenFilter: () -> Unit,
    onReset: () -> Unit,
    onOpenHidden: () -> Unit,
    onAddVideo: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Autofocus the search field the moment search mode opens.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocus.requestFocus()
        } else {
            // The field leaves composition when search closes — dismiss the
            // keyboard too, otherwise it lingers over the feed.
            keyboardController?.hide()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar — echoes the SectionHeader brand mark.
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (searchActive) "Search" else "All Feed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Search toggle: a magnifier normally, an ✕ to close in search mode
            // (closing also clears the query via onToggleSearch).
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchActive) "Close search" else "Search feed",
                    tint = if (searchActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More feed options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (hiddenCount > 0) "Hidden videos ($hiddenCount)"
                                else "Hidden videos"
                            )
                        },
                        onClick = {
                            showMenu = false
                            onOpenHidden()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add video by URL") },
                        enabled = canAddVideo,
                        onClick = {
                            showMenu = false
                            onAddVideo()
                        }
                    )
                }
            }
            IconButton(
                onClick = onOpenFilter,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = "Filter feed",
                    tint = if (filter.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // Search field — shown only while search mode is active.
        if (searchActive) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocus),
                placeholder = { Text("Search videos & channels") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
            )
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "$resultCount result${if (resultCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
        }
        if (filter.isActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = feedFilterSummary(filter, resultCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Reset", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else if (updatedAgo != null && !searchActive) {
            // "Updated Xm ago" — shown in place of the filter summary when no
            // filter is active, so the feed's freshness is always visible.
            Text(
                text = updatedAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Bottom sheet with the All Feed filter controls: Date presets (+ custom range
 * via date pickers), Content type, Sort, and Reset / Apply. Draft state is
 * only committed on Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    filter: FeedFilter,
    onApply: (FeedFilter) -> Unit,
    onReset: (FeedFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(filter) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Filter",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Date",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            FeedDateFilter.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { draft = draft.copy(date = option) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = draft.date == option,
                        onClick = { draft = draft.copy(date = option) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (draft.date == FeedDateFilter.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text(formatShortDate(draft.customStartEpochMillis, "Start")) }
                    OutlinedButton(
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text(formatShortDate(draft.customEndEpochMillis, "End")) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Content",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedContentFilter.entries.forEach { option ->
                    FilterChip(
                        selected = draft.content == option,
                        onClick = { draft = draft.copy(content = option) },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Sort",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            FeedSortOrder.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { draft = draft.copy(sort = option) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = draft.sort == option,
                        onClick = { draft = draft.copy(sort = option) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Watch Status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            FeedWatchStatus.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { draft = draft.copy(watchStatus = option) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = draft.watchStatus == option,
                        onClick = { draft = draft.copy(watchStatus = option) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Library",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedLibraryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = draft.library == option,
                        onClick = { draft = draft.copy(library = option) },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { draft = FeedFilter() },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
                Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.weight(1f)
                ) { Text("Apply") }
            }
        }
    }

    // Custom range: start date picker.
    if (showStartPicker) {
        val startState = rememberDatePickerState(
            initialSelectedDateMillis = draft.customStartEpochMillis
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startState.selectedDateMillis?.let { picked ->
                        draft = draft.copy(
                            customStartEpochMillis = datePickerMillisToLocalStart(picked)
                        )
                    }
                    showStartPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = startState)
        }
    }

    // Custom range: end date picker.
    if (showEndPicker) {
        val endState = rememberDatePickerState(
            initialSelectedDateMillis = draft.customEndEpochMillis
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endState.selectedDateMillis?.let { picked ->
                        draft = draft.copy(
                            customEndEpochMillis = datePickerMillisToLocalStart(picked)
                        )
                    }
                    showEndPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = endState)
        }
    }
}

/** "12 Jan 2026", or the [fallback] placeholder when no date is picked yet. */
private fun formatShortDate(millis: Long?, fallback: String): String {
    if (millis == null) return fallback
    return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}

/**
 * Continue Watching card for the horizontal row: thumbnail with a progress
 * bar + the resume position ("12:34 / 42:10") so the user knows exactly where
 * they left off.
 */
@Composable
private fun ContinueWatchingCard(
    video: MediaVideo,
    progressStore: WatchProgressStore,
    onClick: () -> Unit
) {
    val progress = remember(video.videoId) { progressStore.getProgress(video.videoId) }
    Card(
        onClick = onClick,
        modifier = Modifier.width(200.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                val fraction = (progress?.fraction ?: 0f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = Color.Black.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = formatResumeLabel(progress?.positionSeconds, progress?.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = video.channelName.ifBlank { "YouTube" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** "12:34 / 42:10", or "Resume" when the duration isn't known yet. */
private fun formatResumeLabel(positionSeconds: Long?, durationSeconds: Long?): String {
    val pos = positionSeconds ?: return "Resume"
    val dur = durationSeconds ?: return "Resume"
    if (dur <= 0L) return "Resume"
    return "${formatClock(pos)} / ${formatClock(dur)}"
}

/**
 * YouTube-style duration pill for card thumbnails — "12:34" (or
 * "1:02:34" past an hour). Hidden entirely when the duration isn't known
 * (live broadcasts, or a video that hasn't been enriched yet).
 */
@Composable
private fun DurationBadge(
    seconds: Long,
    modifier: Modifier = Modifier
) {
    if (seconds <= 0L) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Text(
            text = formatClock(seconds),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** "12:34", or "1:02:34" past an hour. */
private fun formatClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * Overflow menu on feed cards: Bookmark toggle, Hide video, and (for
 * manually added videos) Remove. Tapping any entry does NOT trigger the
 * card's own onClick (the inner clickable consumes the tap).
 */
@Composable
private fun VideoCardMenu(
    modifier: Modifier = Modifier,
    isBookmarked: Boolean,
    isManual: Boolean,
    onToggleBookmark: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable { showMenu = true },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f)
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Video options",
                tint = Color.White,
                modifier = Modifier.padding(4.dp)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isBookmarked) "Remove bookmark" else "Bookmark") },
                onClick = {
                    showMenu = false
                    onToggleBookmark()
                }
            )
            if (isManual) {
                DropdownMenuItem(
                    text = { Text("Remove (manually added)") },
                    onClick = {
                        showMenu = false
                        onRemoveManual()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Hide video") },
                onClick = {
                    showMenu = false
                    onHide()
                }
            )
        }
    }
}

@Composable
private fun AddChannelDialog(
    repository: MediaRepository,
    onAdded: (SavedChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add channel") },
        text = {
            Column {
                Text(
                    text = "Paste a channel URL or @handle, e.g. @SafinaSociety",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = error != null,
                    label = { Text("Channel handle or URL") }
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    adding = true
                    scope.launch {
                        when (val result = repository.addChannel(input)) {
                            is MediaRepository.AddChannelResult.Success -> onAdded(result.channel)
                            is MediaRepository.AddChannelResult.Error -> {
                                error = result.message
                                adding = false
                            }
                        }
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        }
    )
}

/**
 * Hidden-videos manager: lists the hidden videos of the current context (a
 * single channel, or every channel in the All Feed) with an Unhide action,
 * plus an "Unhide all" escape hatch (scoped to the shown list). Hidden
 * videos stay out of every feed until unhidden here.
 */
@Composable
private fun HiddenVideosDialog(
    libraryStore: MediaLibraryStore,
    hiddenVideos: List<MediaVideo>,
    onUnhideAll: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden videos") },
        text = {
            if (hiddenVideos.isEmpty()) {
                Text(
                    text = "No hidden videos. When you hide a video it will be listed here so you can bring it back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(hiddenVideos, key = { it.videoId }) { video ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 64.dp, height = 36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = video.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    libraryStore.unhideVideo(video.videoId)
                                    onChanged()
                                }) { Text("Unhide") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (hiddenVideos.isNotEmpty()) {
                TextButton(onClick = {
                    onUnhideAll()
                }) { Text("Unhide all") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ── Skeleton loaders ──────────────────────────────────────────────────
// Shimmer placeholders shown during the first feed load (never a blank
// screen). The layout mirrors the real feed: a section header, a row of
// vertical Shorts cards, another header, then long-video rows.

@Composable
private fun SkeletonFeed() {
    val brush = rememberShimmerBrush()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        // The skeleton is a scrollable surface so pull-to-refresh can engage
        // even while the first load is still in flight.
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "sk-header-shorts") {
            SkeletonSectionHeader(brush = brush)
        }
        item(key = "sk-row-shorts") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(3) {
                    SkeletonBlock(
                        brush = brush,
                        modifier = Modifier
                            .width(150.dp)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
            }
        }
        item(key = "sk-header-videos") {
            SkeletonSectionHeader(brush = brush)
        }
        items(4) {
            SkeletonVideoRow(brush = brush)
        }
    }
}

/** Shimmer section-title placeholder (accent bar + text bar). */
@Composable
private fun SkeletonSectionHeader(brush: Brush) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkeletonBlock(
            brush = brush,
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        SkeletonBlock(
            brush = brush,
            modifier = Modifier
                .width(120.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

/** Shimmer long-video row: thumbnail block + two text lines. */
@Composable
private fun SkeletonVideoRow(brush: Brush) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SkeletonBlock(
            brush = brush,
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            SkeletonBlock(
                brush = brush,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(
                brush = brush,
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

/** One shimmer placeholder block (animated sweeping gradient). */
@Composable
private fun SkeletonBlock(
    brush: Brush,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(brush))
}

/**
 * A single shared animated shimmer brush (one infinite transition for the
 * whole skeleton, passed down to every block so they sweep in unison).
 */
@Composable
private fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val highlight = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val transition = rememberInfiniteTransition(label = "feedShimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "feedShimmerX"
    )
    // Brush offsets are in PIXELS of the drawn block (not normalized), so the
    // sweep band must be sized in real pixels. A ~300px highlight band moving
    // across a ~1000px sweep travels over every block width used in the
    // skeleton (150dp shorts cards ≈ 450px @3x density, 160dp thumbnails ≈
    // 480px). Blocks narrower than the band still get a visible shimmer.
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x * 500f - 200f, 0f),
        end = Offset(x * 500f + 100f, 0f)
    )
}

@Composable
private fun ErrorCard(message: String) {
    // Scrollable (even though the card fits) so the pull-to-refresh gesture
    // can engage from an empty / error state — a stuck feed is exactly when
    // the user reaches for a manual refresh.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📡", fontSize = 36.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * "Add video by URL": pastes a YouTube URL, resolves it via the channel's
 * feed or oEmbed, and saves it as a manually added video in the library
 * (marked "Manually added", never treated as new, never notified).
 */
@Composable
private fun AddVideoDialog(
    repository: MediaRepository,
    libraryStore: MediaLibraryStore,
    channel: SavedChannel?,
    onAdded: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add video by URL") },
        text = {
            Column {
                Text(
                    text = "Paste a YouTube link. It will be added to " +
                        (channel?.displayName ?: "your feed") +
                        " as a manually added video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = status?.startsWith("Couldn't") == true,
                    label = { Text("YouTube URL") }
                )
                if (status != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status!!,
                        color = if (status!!.startsWith("Couldn't")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    adding = true
                    status = null
                    scope.launch {
                        status = when (val result = repository.resolveVideoByUrl(input, channel)) {
                            is MediaRepository.ResolveVideoResult.Success -> {
                                if (libraryStore.addManuallyAdded(result.video)) {
                                    onAdded()
                                    onDismiss()
                                    null
                                } else {
                                    "That video is already in your library."
                                }
                            }
                            is MediaRepository.ResolveVideoResult.AlreadyExists ->
                                "That video is already in this channel's feed."
                            is MediaRepository.ResolveVideoResult.Error -> result.message
                        }
                        adding = false
                    }
                }
            ) { Text(if (adding) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        }
    )
}

/** Two-letter initials for the avatar fallback ("Safina Society" → "SS"). */
private fun initials(name: String): String {
    val parts = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    return parts
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifBlank { name.take(1).uppercase() }
}
