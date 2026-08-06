package com.muddassir.clearview.media.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
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
import com.muddassir.clearview.media.data.UserPlaylistStore
import com.muddassir.clearview.media.data.WatchProgressStore
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.DownloadStatus
import com.muddassir.clearview.media.model.FeedContentFilter
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.SavedChannel
import com.muddassir.clearview.media.model.SavedPlaylist
import com.muddassir.clearview.media.model.UserPlaylist
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
    /** Plays the downloaded audio for [video] instead of the video (offline). */
    onPlayOffline: (MediaVideo) -> Unit = {},
    /** Opens the podcast-style audio player for a downloaded item. */
    onPlayAudio: (DownloadItem) -> Unit = {},
    /** Fired when the Media tab is shown (marks channel updates as seen). */
    onMediaOpened: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { MediaRepository(context.applicationContext) }
    val progressStore = remember { WatchProgressStore(context.applicationContext) }
    val libraryStore = remember { MediaLibraryStore(context.applicationContext) }
    // Offline audio downloads: idempotent init (cheap after the first call);
    // the cards and the Downloads section read the state directly below.
    AudioDownloads.initialize(context.applicationContext)
    // Bumped whenever the library changes (hide / manual add / playlist edit)
    // so the merged feed and the Continue Watching row recompute.
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

    // ── Imported YouTube playlists (added by URL) ─────────────────
    var playlists by remember { mutableStateOf(repository.getSavedPlaylists()) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var playlistVideos by remember { mutableStateOf<List<MediaVideo>>(emptyList()) }
    var playlistLoading by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    var playlistRefreshedAt by remember { mutableStateOf(0L) }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var pendingPlaylistRemove by remember { mutableStateOf<SavedPlaylist?>(null) }

    // ── User-created playlists (local library) ────────────────────
    val userPlaylistStore = remember { UserPlaylistStore(context.applicationContext) }
    // Bumped whenever a user playlist is created / renamed / deleted / edited.
    var playlistRevision by remember { mutableIntStateOf(0) }
    var showPlaylistsSheet by remember { mutableStateOf(false) }
    var selectedUserPlaylistId by remember { mutableStateOf<String?>(null) }
    var showPlaylistEditor by remember { mutableStateOf(false) }
    var showAddVideosPicker by remember { mutableStateOf(false) }
    // Name dialog: null target = create a new playlist, non-null = rename it.
    var showPlaylistNameDialog by remember { mutableStateOf(false) }
    var playlistNameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    // A video the user tapped "Add to playlist…" on in a card's ⋮ menu.
    var pendingAddToPlaylist by remember { mutableStateOf<MediaVideo?>(null) }
    // A video the user chose to seed a NEW playlist with (via the ⋮ menu's
    // "New playlist with this video" — routed through the name dialog).
    var pendingCreateWithVideo by remember { mutableStateOf<MediaVideo?>(null) }
    val userPlaylists = remember(playlistRevision) { userPlaylistStore.getPlaylists() }
    val selectedUserPlaylist = remember(userPlaylists, selectedUserPlaylistId) {
        userPlaylists.firstOrNull { it.id == selectedUserPlaylistId }
    }
    val saveUserPlaylists: () -> Unit = { playlistRevision++ }

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

    // Playlist load: the selected imported playlist's cached videos first
    // (instant), then a background refresh of the playlist page. Runs when the
    // selection changes or the pull-to-refresh token bumps.
    LaunchedEffect(selectedPlaylistId, refreshToken) {
        val id = selectedPlaylistId ?: return@LaunchedEffect
        playlistLoading = true
        playlistError = null
        val cached = withContext(Dispatchers.IO) { repository.getCachedPlaylistVideos(id) }
        if (cached?.first?.isNotEmpty() == true) playlistVideos = cached.first
        val fresh = repository.refreshPlaylistVideos(id)
        when {
            fresh == null && cached?.first?.isNotEmpty() != true ->
                playlistError = "Couldn't load this playlist. Check your connection."
            fresh != null && fresh.isEmpty() && cached?.first?.isNotEmpty() != true ->
                playlistError = "This playlist is private or unavailable."
            fresh != null && fresh.isNotEmpty() -> {
                playlistVideos = fresh
                playlistRefreshedAt = System.currentTimeMillis()
            }
        }
        playlistLoading = false
    }

    // The feed plus the user's library (manually added videos — which can be
    // older than the RSS window), merged by id (RSS wins). Hidden videos are
    // filtered out everywhere EXCEPT the Hidden manager.
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
    val mergedVideos = remember(videos, manualVideos) {
        MediaVideos.merge(videos, manualVideos)
    }
    val visibleVideos = remember(mergedVideos, hiddenIds) {
        mergedVideos.filterNot { it.videoId in hiddenIds }
    }

    // Feed source: a selected imported YouTube playlist, a selected user
    // playlist, or the normal channel feed (All Feed / a single channel).
    // Only one context can be active at a time.
    val feedIsPlaylist = selectedPlaylistId != null
    val feedIsUserPlaylist = selectedUserPlaylistId != null
    val playlistContextVideos = remember(playlistVideos, hiddenIds) {
        playlistVideos.filterNot { it.videoId in hiddenIds }
    }
    val userPlaylistVideos = selectedUserPlaylist?.videos.orEmpty()
    val baseVideos = when {
        feedIsUserPlaylist -> userPlaylistVideos
        feedIsPlaylist -> playlistContextVideos
        else -> visibleVideos
    }
    // Channel strip filter + the All Feed filters are applied locally to the
    // already-loaded videos — no refetching. Playlist contexts bypass the
    // channel strip entirely.
    val channelVideos = remember(baseVideos, filterChannelId, feedIsPlaylist, feedIsUserPlaylist) {
        when {
            feedIsPlaylist || feedIsUserPlaylist -> baseVideos
            filterChannelId == null -> baseVideos
            else -> baseVideos.filter { it.channelId == filterChannelId }
        }
    }
    // User playlists keep their hand-picked ORDER — the feed filters (date /
    // content / watch status) don't apply; search still filters on top.
    val displayed = remember(channelVideos, feedFilter, libraryRevision, feedIsUserPlaylist) {
        if (feedIsUserPlaylist) channelVideos
        else applyFeedFilter(
            channelVideos,
            feedFilter,
            progressOf = { progressStore.get(it) }
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
    // appear here — they're meant to be watched in one sitting). Hidden in
    // playlist contexts — those are curated, ordered lists.
    val continueWatching = remember(
        channelVideos, hiddenIds, libraryRevision, feedIsPlaylist, feedIsUserPlaylist
    ) {
        if (feedIsPlaylist || feedIsUserPlaylist) emptyList()
        else channelVideos
            .filter { v ->
                !v.isShort && !v.isLive &&
                    (progressStore.get(v.videoId)?.let {
                        it >= 0.02f && it < 0.9f
                    } ?: false)
            }
            .sortedByDescending { it.publishedAtEpochMillis }
            .take(6)
    }
    // Already downloaded → play the local offline audio immediately (podcast
    // style); otherwise the normal (WebView) video playback.
    val playShort: (MediaVideo) -> Unit = { video ->
        if (AudioDownloads.isDownloaded(video.videoId)) {
            onPlayOffline(video)
        } else {
            val idx = shorts.indexOfFirst { it.videoId == video.videoId }
            onPlayVideo(video, shorts, idx)
        }
    }
    val playLong: (MediaVideo) -> Unit = { video ->
        if (AudioDownloads.isDownloaded(video.videoId)) onPlayOffline(video)
        else onPlayVideo(video, emptyList(), -1)
    }
    // The Downloads content filter swaps the feed for the Downloads section.
    val downloadsFilter = feedFilter.content == FeedContentFilter.DOWNLOADS

    Column(modifier = modifier.fillMaxSize()) {

        // ── Channel avatar strip (Subscriptions-style) ─────────────
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            item(key = "all") {
                AllAvatar(
                    selected = filterChannelId == null,
                    onClick = {
                        filterChannelId = null
                        // Leaving a playlist context: the channel strip always wins.
                        selectedPlaylistId = null
                        selectedUserPlaylistId = null
                    }
                )
            }
            items(channels, key = { it.channelId }) { channel ->
                ChannelAvatar(
                    channel = channel,
                    selected = filterChannelId == channel.channelId,
                    onClick = {
                        filterChannelId =
                            if (filterChannelId == channel.channelId) null else channel.channelId
                        // Leaving a playlist context: the channel strip always wins.
                        selectedPlaylistId = null
                        selectedUserPlaylistId = null
                    },
                    onRemove = { pendingRemove = channel }
                )
            }
            item(key = "add") {
                AddAvatar(onClick = { showAddDialog = true })
            }
        }

        // ── Imported YouTube playlist strip (below the channels) ──
        // A horizontal row of the user's imported playlists (tap to view its
        // videos as a feed, tap again / ✕ to deselect) ending in an add chip.
        if (playlists.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(playlists, key = { it.playlistId }) { playlist ->
                    val count = remember(playlist.playlistId, selectedPlaylistId, playlistVideos.size) {
                        if (selectedPlaylistId == playlist.playlistId) playlistVideos.size
                        else repository.getCachedPlaylistVideos(playlist.playlistId)
                            ?.first?.size ?: 0
                    }
                    PlaylistChip(
                        playlist = playlist,
                        selected = selectedPlaylistId == playlist.playlistId,
                        videoCount = count,
                        onClick = {
                            if (selectedPlaylistId == playlist.playlistId) {
                                selectedPlaylistId = null
                            } else {
                                // Only one feed context at a time. Clear any
                                // previously loaded playlist videos so the new
                                // playlist's feed never flashes the old one's
                                // content under the new title.
                                selectedPlaylistId = playlist.playlistId
                                playlistVideos = emptyList()
                                playlistError = null
                                selectedUserPlaylistId = null
                                filterChannelId = null
                            }
                        },
                        onRemove = { pendingPlaylistRemove = playlist }
                    )
                }
                item(key = "add-playlist") {
                    AddPlaylistChip(onClick = { showAddPlaylistDialog = true })
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── All Feed header: filter button (highlighted when active) + summary ──
        // Shown whenever there is a feed at all — including when a filter hides
        // every video (so it can always be reset), and in playlist contexts.
        // Hidden during the empty no-channels / loading states.
        if (videos.isNotEmpty() || feedIsPlaylist || feedIsUserPlaylist) {
            // "Updated Xm ago" comes from whichever source feeds the screen:
            // the channel feeds in normal mode, the playlist page otherwise.
            val sourceRefreshedAt = if (feedIsPlaylist) playlistRefreshedAt else lastRefreshedAt
            val sourceLoading = if (feedIsPlaylist) playlistLoading else isLoading
            val headerTitle = when {
                feedIsUserPlaylist -> selectedUserPlaylist?.name ?: "My Playlist"
                feedIsPlaylist -> playlists.firstOrNull { it.playlistId == selectedPlaylistId }
                    ?.title ?: "Playlist"
                else -> "All Feed"
            }
            FeedHeader(
                title = headerTitle,
                filter = feedFilter,
                resultCount = searchResults.size,
                hiddenCount = contextHiddenVideos.size,
                // "Add video by URL" is available when a channel is selected
                // OR when viewing one of the user's own playlists (the added
                // video then lands in that playlist too).
                canAddVideo = filterChannelId != null || feedIsUserPlaylist,
                canEditPlaylist = feedIsUserPlaylist,
                searchActive = searchActive,
                searchQuery = searchQuery,
                // "Updated Xm ago" — recomputed whenever a refresh lands OR
                // the minute ticker bumps (clockTick read forces recompose).
                updatedAgo = if (sourceRefreshedAt > 0L && !sourceLoading && clockTick >= 0) {
                    val ago = DateUtils.getRelativeTimeSpanString(
                        sourceRefreshedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                    if (ago.startsWith("0 min")) "Updated just now" else "Updated $ago"
                } else null,
                onClose = if (feedIsPlaylist || feedIsUserPlaylist) {
                    {
                        selectedPlaylistId = null
                        selectedUserPlaylistId = null
                    }
                } else null,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = {
                    searchActive = !searchActive
                    searchQuery = ""
                },
                onOpenFilter = { showFilterSheet = true },
                onReset = { saveFilter(FeedFilter()) },
                onOpenHidden = { showHiddenDialog = true },
                onAddVideo = { showAddVideoDialog = true },
                onAddPlaylist = { showAddPlaylistDialog = true },
                onOpenMyPlaylists = { showPlaylistsSheet = true },
                onEditPlaylist = { showPlaylistEditor = true }
            )
        }

        // ── Feed ───────────────────────────────────────────────────
        // Pull-to-refresh wraps the whole feed (including skeleton + empty
        // states — each is scrollable so the gesture always engages).
        PullToRefreshBox(
            isRefreshing = isLoading || playlistLoading,
            onRefresh = { refreshToken++ },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                // Downloads filter → the Downloads section (storage card +
                // offline audio list). Replaces the feed entirely.
                downloadsFilter && !isSearching -> DownloadsSection(
                    onPlayAudio = onPlayAudio
                )
                // Imported playlist: loading / error / empty states.
                feedIsPlaylist && playlistLoading && playlistVideos.isEmpty() -> SkeletonFeed()
                feedIsPlaylist && playlistError != null && playlistVideos.isEmpty() ->
                    ErrorCard(playlistError!!)
                feedIsPlaylist && playlistVideos.isEmpty() && !playlistLoading -> ErrorCard(
                    playlistError ?: "No videos in this playlist yet."
                )
                // User playlist: empty state (videos get added via Edit).
                feedIsUserPlaylist && userPlaylistVideos.isEmpty() ->
                    ErrorCard("This playlist is empty. Tap Edit to add videos.")
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
                    // Section headers reflect whichever source loads the feed.
                    val feedLoading = if (feedIsPlaylist) playlistLoading else isLoading
                    val feedCached = !feedIsPlaylist && !feedIsUserPlaylist && showingCached
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
                    if (shorts.isNotEmpty() && !feedIsUserPlaylist) {
                        item(key = "shorts-header") {
                            SectionHeader(
                                title = "Shorts",
                                isLoading = feedLoading,
                                showingCached = feedCached
                            )
                        }
                        item(key = "shorts-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shorts, key = { it.videoId }) { video ->
                                    ShortCard(
                                        video = video,
                                        progressStore = progressStore,
                                        isManual = libraryStore.isManuallyAdded(video.videoId),
                                        downloadStatus = AudioDownloads.statusFor(video.videoId),
                                        isOffline = AudioDownloads.isDownloaded(video.videoId),
                                        onClick = { playShort(video) },
                                        onDownload = {
                                            AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                                        },
                                        onCancelDownload = { AudioDownloads.cancel(video.videoId) },
                                        onDeleteDownload = { AudioDownloads.delete(video.videoId) },
                                        onHide = {
                                            libraryStore.hideVideo(video)
                                            libraryRevision++
                                        },
                                        onRemoveManual = {
                                            libraryStore.removeManuallyAdded(video.videoId)
                                            libraryRevision++
                                        },
                                        onAddToPlaylist = { pendingAddToPlaylist = video }
                                    )
                                }
                            }
                        }
                    }
                    if (longs.isNotEmpty() && !feedIsUserPlaylist) {
                        item(key = "videos-header") {
                            SectionHeader(
                                title = "Videos",
                                isLoading = feedLoading,
                                showingCached = feedCached
                            )
                        }
                        items(longs, key = { it.videoId }) { video ->
                            LongVideoCard(
                                video = video,
                                progressStore = progressStore,
                                isManual = libraryStore.isManuallyAdded(video.videoId),
                                downloadStatus = AudioDownloads.statusFor(video.videoId),
                                isOffline = AudioDownloads.isDownloaded(video.videoId),
                                onClick = { playLong(video) },
                                onDownload = {
                                    AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                                },
                                onCancelDownload = { AudioDownloads.cancel(video.videoId) },
                                onDeleteDownload = { AudioDownloads.delete(video.videoId) },
                                onHide = {
                                    libraryStore.hideVideo(video)
                                    libraryRevision++
                                },
                                onRemoveManual = {
                                    libraryStore.removeManuallyAdded(video.videoId)
                                    libraryRevision++
                                },
                                onAddToPlaylist = { pendingAddToPlaylist = video }
                            )
                        }
                    }
                    // ── User playlist: the hand-picked ORDER matters, so every
                    // video renders as one ordered row list (no shorts/longs
                    // split, no feed filters).
                    if (feedIsUserPlaylist && searchResults.isNotEmpty()) {
                        item(key = "playlist-videos-header") {
                            SectionHeader(
                                title = "Videos",
                                isLoading = false,
                                showingCached = false
                            )
                        }
                        items(searchResults, key = { it.videoId }) { video ->
                            LongVideoCard(
                                video = video,
                                progressStore = progressStore,
                                isManual = libraryStore.isManuallyAdded(video.videoId),
                                downloadStatus = AudioDownloads.statusFor(video.videoId),
                                isOffline = AudioDownloads.isDownloaded(video.videoId),
                                onClick = { playLong(video) },
                                onDownload = {
                                    AudioDownloads.download(video, AudioDownloads.sourceFor(video))
                                },
                                onCancelDownload = { AudioDownloads.cancel(video.videoId) },
                                onDeleteDownload = { AudioDownloads.delete(video.videoId) },
                                onHide = {
                                    libraryStore.hideVideo(video)
                                    libraryRevision++
                                },
                                onRemoveManual = {
                                    libraryStore.removeManuallyAdded(video.videoId)
                                    libraryRevision++
                                },
                                onAddToPlaylist = { pendingAddToPlaylist = video },
                                // A video shown in this playlist's feed is by
                                // definition in it — offer removing it straight
                                // from the card (the feed updates instantly).
                                onRemoveFromPlaylist = {
                                    selectedUserPlaylist?.let { p ->
                                        userPlaylistStore.removeVideo(p.id, video.videoId)
                                        saveUserPlaylists()
                                        Toast.makeText(
                                            context,
                                            "Removed from ${p.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
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
            // While viewing one of your playlists, a by-URL video also lands
            // in that playlist — that's the context the menu item appears in.
            targetLabel = selectedUserPlaylist?.let { "your playlist \"${it.name}\"" },
            onResolved = { video ->
                selectedUserPlaylist?.let { p ->
                    userPlaylistStore.addVideos(p.id, listOf(video))
                    saveUserPlaylists()
                    Toast.makeText(context, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                }
            },
            onAdded = { libraryRevision++ },
            onDismiss = { showAddVideoDialog = false }
        )
    }

    // ── Add YouTube playlist by URL ────────────────────────────────
    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            repository = repository,
            onAdded = { playlist ->
                showAddPlaylistDialog = false
                playlists = repository.getSavedPlaylists()
                // Jump straight into the new playlist's feed.
                selectedPlaylistId = playlist.playlistId
                selectedUserPlaylistId = null
                filterChannelId = null
                playlistVideos = emptyList()
                playlistError = null
            },
            onDismiss = { showAddPlaylistDialog = false }
        )
    }

    // ── Remove playlist confirmation ───────────────────────────────
    pendingPlaylistRemove?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingPlaylistRemove = null },
            title = { Text("Remove playlist?") },
            text = { Text("Remove \"${playlist.title}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.removePlaylist(playlist.playlistId)
                    playlists = repository.getSavedPlaylists()
                    if (selectedPlaylistId == playlist.playlistId) {
                        selectedPlaylistId = null
                        playlistVideos = emptyList()
                        playlistError = null
                    }
                    pendingPlaylistRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPlaylistRemove = null }) { Text("Cancel") }
            }
        )
    }

    // ── My playlists manager (user-created) ────────────────────────
    if (showPlaylistsSheet) {
        PlaylistsSheet(
            playlists = userPlaylists,
            onOpen = { p ->
                showPlaylistsSheet = false
                selectedUserPlaylistId = p.id
                selectedPlaylistId = null
                // Keep the tab context (All Feed vs the selected channel): the
                // playlist's Edit → Add videos picker then suggests the videos
                // of whichever tab you opened the playlist from. Closing the
                // playlist (✕) also lands you right back on that tab.
            },
            onCreate = {
                showPlaylistsSheet = false
                playlistNameTarget = null
                showPlaylistNameDialog = true
            },
            onRename = { p ->
                playlistNameTarget = p
                showPlaylistNameDialog = true
            },
            onDelete = { p ->
                userPlaylistStore.deletePlaylist(p.id)
                if (selectedUserPlaylistId == p.id) selectedUserPlaylistId = null
                saveUserPlaylists()
            },
            onDismiss = { showPlaylistsSheet = false }
        )
    }

    // ── Playlist name dialog (create / rename) ─────────────────────
    if (showPlaylistNameDialog) {
        PlaylistNameDialog(
            initial = playlistNameTarget?.name ?: "",
            title = if (playlistNameTarget == null) "New playlist" else "Rename playlist",
            confirmLabel = if (playlistNameTarget == null) "Create" else "Rename",
            onSubmit = { name ->
                if (playlistNameTarget == null) {
                    // Creating fresh — optionally seeded with the video the
                    // user picked from a card's ⋮ menu.
                    val seed = pendingCreateWithVideo?.let { listOf(it) } ?: emptyList()
                    userPlaylistStore.createPlaylist(name, seed)
                    if (pendingCreateWithVideo != null) {
                        Toast.makeText(
                            context,
                            "Created \"$name\" with this video",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    userPlaylistStore.renamePlaylist(playlistNameTarget!!.id, name)
                }
                pendingCreateWithVideo = null
                showPlaylistNameDialog = false
                saveUserPlaylists()
            },
            onDismiss = {
                pendingCreateWithVideo = null
                showPlaylistNameDialog = false
            }
        )
    }

    // ── Playlist editor (reorder / remove / add videos) ────────────
    if (showPlaylistEditor && selectedUserPlaylist != null) {
        PlaylistEditorSheet(
            playlist = selectedUserPlaylist!!,
            onMove = { from, to ->
                userPlaylistStore.moveVideo(selectedUserPlaylist!!.id, from, to)
                saveUserPlaylists()
            },
            onRemove = { videoId ->
                userPlaylistStore.removeVideo(selectedUserPlaylist!!.id, videoId)
                saveUserPlaylists()
            },
            onAddVideos = { showAddVideosPicker = true },
            onDismiss = { showPlaylistEditor = false }
        )
    }

    // ── Add-videos picker for the open user playlist ───────────────
    // The suggested candidates follow the tab the playlist was opened from:
    // All Feed → every video; a channel tab → just that channel's videos.
    val addVideosCandidates = remember(visibleVideos, filterChannelId) {
        if (filterChannelId == null) visibleVideos
        else visibleVideos.filter { it.channelId == filterChannelId }
    }
    if (showAddVideosPicker && selectedUserPlaylist != null) {
        PlaylistAddVideosSheet(
            candidates = addVideosCandidates,
            alreadyIn = selectedUserPlaylist!!.videos.map { it.videoId }.toSet(),
            onAdd = { videos ->
                userPlaylistStore.addVideos(selectedUserPlaylist!!.id, videos)
                saveUserPlaylists()
                showAddVideosPicker = false
            },
            onDismiss = { showAddVideosPicker = false }
        )
    }

    // ── Add-to-playlist picker (from a card's ⋮ menu) ──────────────
    pendingAddToPlaylist?.let { video ->
        AddToPlaylistSheet(
            video = video,
            playlists = userPlaylists,
            onAdd = { playlist ->
                userPlaylistStore.addVideos(playlist.id, listOf(video))
                saveUserPlaylists()
                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                pendingAddToPlaylist = null
            },
            onCreateNew = {
                // Route through the name dialog so the new playlist gets a
                // real name (seeded with this video on submit).
                pendingCreateWithVideo = video
                pendingAddToPlaylist = null
                playlistNameTarget = null
                showPlaylistNameDialog = true
            },
            onDismiss = { pendingAddToPlaylist = null }
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
    isManual: Boolean,
    downloadStatus: DownloadStatus?,
    isOffline: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit,
    onAddToPlaylist: () -> Unit = {}
) {
    val fraction = remember(video.videoId) { progressStore.get(video.videoId) }
    // A live broadcast has no finite duration to complete — never show a
    // "Watched" badge or progress bar on it, even if stale progress was saved
    // by an older build that misclassified it as a bounded video.
    val watched = !video.isLive && (fraction ?: 0f) >= 0.9f
    val live = video.isLive
    Card(
        onClick = onClick,
        modifier = Modifier.width(158.dp),
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
            } else if (downloadStatus == null && fraction != null && fraction > 0.02f && !live) {
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
            // add to playlist / download / hide / remove manual.
            VideoCardMenu(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                isManual = isManual,
                downloadLabel = downloadMenuLabel(downloadStatus, isOffline),
                onDownloadAction = downloadMenuAction(
                    downloadStatus, isOffline, onDownload, onCancelDownload, onDeleteDownload
                ),
                onHide = onHide,
                onRemoveManual = onRemoveManual,
                onAddToPlaylist = onAddToPlaylist
            )
            // Download progress: an animated bar pinned to the bottom of the
            // thumbnail while the audio downloads (pulsing while the server
            // prepares, a real % once bytes flow, red if it failed — the ⋮
            // menu then offers Retry). The download icon itself lives in the
            // ⋮ menu and the video player's control panel.
            downloadStatus?.let { status ->
                DownloadProgressOverlay(status, Modifier.align(Alignment.BottomStart))
            }
            // Bottom-end stack: status pills + duration pill.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isOffline) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
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
                    .padding(12.dp)
                    .padding(end = 10.dp)
            ) {
                Text(
                    text = video.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
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
    isManual: Boolean,
    downloadStatus: DownloadStatus?,
    isOffline: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    /** When set (user-playlist feed), the ⋮ menu offers removing from it. */
    onRemoveFromPlaylist: (() -> Unit)? = null
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
                    .width(176.dp)
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
                // Overflow menu (add to playlist / download / hide / remove
                // manual / remove from playlist).
                VideoCardMenu(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    isManual = isManual,
                    downloadLabel = downloadMenuLabel(downloadStatus, isOffline),
                    onDownloadAction = downloadMenuAction(
                        downloadStatus, isOffline, onDownload, onCancelDownload, onDeleteDownload
                    ),
                    onHide = onHide,
                    onRemoveManual = onRemoveManual,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemoveFromPlaylist = onRemoveFromPlaylist
                )
            // Download progress: an animated bar pinned to the bottom of the
            // thumbnail while the audio downloads (see ShortCard for details).
            downloadStatus?.let { status ->
                DownloadProgressOverlay(status, Modifier.align(Alignment.BottomStart))
            }
            // Bottom-end stack: status pills + duration pill. Everything sits
            // above the watch-progress bar.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isOffline) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
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
                } else if (downloadStatus == null && fraction != null && fraction > 0.02f && !live) {
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
                    .padding(12.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
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
    /** The feed title: "All Feed", a playlist's title, or the search label. */
    title: String = "All Feed",
    filter: FeedFilter,
    resultCount: Int,
    hiddenCount: Int,
    canAddVideo: Boolean,
    /** Shows a pencil icon to open the user-playlist editor. */
    canEditPlaylist: Boolean = false,
    searchActive: Boolean,
    searchQuery: String,
    /** "Updated Xm ago" text shown under the title row (null = hide). */
    updatedAgo: String? = null,
    /** When set, an ✕ appears that exits the current playlist context. */
    onClose: (() -> Unit)? = null,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenFilter: () -> Unit,
    onReset: () -> Unit,
    onOpenHidden: () -> Unit,
    onAddVideo: () -> Unit,
    onAddPlaylist: (() -> Unit)? = null,
    onOpenMyPlaylists: (() -> Unit)? = null,
    onEditPlaylist: (() -> Unit)? = null
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
                text = if (searchActive) "Search" else title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Edit playlist (user playlist context only).
            if (canEditPlaylist && onEditPlaylist != null) {
                IconButton(
                    onClick = onEditPlaylist,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit playlist",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            // Search toggle: a magnifier normally, an ✕ to close in search mode
            // (closing also clears the query via onToggleSearch).
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchActive) "Close search" else "Search feed",
                    tint = if (searchActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More feed options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
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
                        text = { Text("My playlists") },
                        enabled = onOpenMyPlaylists != null,
                        onClick = {
                            showMenu = false
                            onOpenMyPlaylists?.invoke()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add playlist by URL") },
                        enabled = onAddPlaylist != null,
                        onClick = {
                            showMenu = false
                            onAddPlaylist?.invoke()
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
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = "Filter feed",
                    tint = if (filter.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            // Exit the playlist context (imported or user playlist).
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close playlist",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
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
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
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
            // FlowRow of chips (All / Videos / Shorts / Downloads) wraps instead
            // of overflowing on narrow screens. LIVE is deliberately excluded:
            // the dedicated Live tab is the only live viewer, so a Live feed
            // filter is no longer offered here.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedContentFilter.entries
                    .filterNot { it == FeedContentFilter.LIVE }
                    .forEach { option ->
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
        modifier = Modifier.width(210.dp),
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
            Column(modifier = Modifier.padding(12.dp)) {
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
 * The animated download bar pinned to the bottom of a feed-card thumbnail.
 * Replaces the old corner download icon: while the audio is being fetched the
 * bar sweeps (Preparing — the server may be cold-starting), then fills with
 * real progress once bytes flow (Downloading), and turns red if it failed
 * (the ⋮ menu then offers "Retry download").
 */
@Composable
private fun DownloadProgressOverlay(
    status: DownloadStatus,
    modifier: Modifier = Modifier
) {
    when (status) {
        is DownloadStatus.Preparing -> DownloadSweepBar(modifier)
        is DownloadStatus.Downloading -> {
            if (status.progress >= 0f) {
                // Smoothly filled as bytes land.
                val animated by animateFloatAsState(
                    targetValue = status.progress.coerceIn(0f, 1f),
                    animationSpec = tween(400),
                    label = "download-progress"
                )
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animated)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            } else {
                // Total size unknown yet — sweep instead of looking stuck.
                DownloadSweepBar(modifier)
            }
        }
        is DownloadStatus.Error -> {
            // Failed: a static red bar signals the download didn't complete.
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}

/** A pulsing bar that sweeps across the thumbnail (Preparing / unknown size). */
@Composable
private fun DownloadSweepBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "download-sweep")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "download-sweep-offset"
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        val bar = maxWidth * 0.35f
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(bar)
                .offset(x = (maxWidth + bar) * sweep - bar)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * Overflow menu on feed cards: Add to playlist, Download audio, Hide video,
 * and (for manually added videos) Remove. In a user-playlist feed a
 * "Remove from playlist" entry is offered too. Tapping any entry does NOT
 * trigger the card's own onClick (the inner clickable consumes the tap).
 */
@Composable
private fun VideoCardMenu(
    modifier: Modifier = Modifier,
    isManual: Boolean,
    downloadLabel: String? = null,
    onDownloadAction: (() -> Unit)? = null,
    onHide: () -> Unit,
    onRemoveManual: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    /** When set (a user-playlist feed), the menu also offers removing from it. */
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { showMenu = true },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f)
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Video options",
                tint = Color.White,
                modifier = Modifier.padding(5.dp)
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            if (onAddToPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    }
                )
            }
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        showMenu = false
                        onRemoveFromPlaylist()
                    }
                )
            }
            if (downloadLabel != null && onDownloadAction != null) {
                DropdownMenuItem(
                    text = { Text(downloadLabel) },
                    onClick = {
                        showMenu = false
                        onDownloadAction()
                    }
                )
            }
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

/** The ⋮ menu label for a card's download entry, by state. */
internal fun downloadMenuLabel(status: DownloadStatus?, isOffline: Boolean): String = when {
    isOffline -> "Delete download"
    status == null -> "Download audio"
    status is DownloadStatus.Preparing || status is DownloadStatus.Downloading -> "Cancel download"
    else -> "Retry download"
}

/** The ⋮ menu action for a card's download entry, by state. */
internal fun downloadMenuAction(
    status: DownloadStatus?,
    isOffline: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
): (() -> Unit)? = when {
    isOffline -> onDelete
    status == null -> onDownload
    status is DownloadStatus.Preparing || status is DownloadStatus.Downloading -> onCancel
    else -> onDownload
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
    /** Where the video will go, e.g. "your playlist \"X\"" — drives the copy. */
    targetLabel: String? = null,
    /** Fired with the resolved video once it's been added to the library. */
    onResolved: (MediaVideo) -> Unit = {},
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
                        (targetLabel ?: channel?.displayName ?: "your feed") +
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
                                    onResolved(result.video)
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

// ══════════════════════════════════════════════════════════════════════
//  Imported YouTube playlists (added by URL)
// ══════════════════════════════════════════════════════════════════════

/**
 * A pill-shaped chip for one imported YouTube playlist in the Media tab's
 * playlist strip: icon + title + video count, with a small ✕ to remove (the
 * ✕ consumes its own tap, so it never also opens the playlist).
 */
@Composable
private fun PlaylistChip(
    playlist: SavedPlaylist,
    selected: Boolean,
    videoCount: Int,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlaylistPlay,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (videoCount > 0) "$videoCount videos" else "Loading…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${playlist.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** The "+" entry at the end of the playlist strip — opens the add-by-URL dialog. */
@Composable
private fun AddPlaylistChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add playlist by URL",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Playlist",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * "Add playlist by URL": pastes a YouTube playlist link, resolves + fetches
 * all its videos, and saves it. Invalid URLs, private playlists and network
 * failures each show a friendly inline error instead of failing silently.
 */
@Composable
private fun AddPlaylistDialog(
    repository: MediaRepository,
    onAdded: (SavedPlaylist) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val isError = status != null &&
        (status!!.startsWith("Couldn't") ||
            status!!.startsWith("This playlist") ||
            status!!.startsWith("That") ||
            status!!.startsWith("Enter"))

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add playlist by URL") },
        text = {
            Column {
                Text(
                    text = "Paste a YouTube playlist link. All its videos will be fetched and shown like a normal feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    singleLine = true,
                    enabled = !adding,
                    isError = isError,
                    label = { Text("Playlist URL or id") }
                )
                if (status != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status!!,
                        color = if (isError) MaterialTheme.colorScheme.error
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
                        status = when (val result = repository.addPlaylist(input)) {
                            is MediaRepository.AddPlaylistResult.Success -> {
                                onAdded(result.playlist)
                                null
                            }
                            is MediaRepository.AddPlaylistResult.Error -> result.message
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

// ══════════════════════════════════════════════════════════════════════
//  User-created playlists (local library)
// ══════════════════════════════════════════════════════════════════════

/**
 * The "My Playlists" manager: every user-created playlist with open, rename
 * and delete actions, plus a New playlist button. Tapping a playlist opens it
 * as the current feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsSheet(
    playlists: List<UserPlaylist>,
    onOpen: (UserPlaylist) -> Unit,
    onCreate: () -> Unit,
    onRename: (UserPlaylist) -> Unit,
    onDelete: (UserPlaylist) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "My Playlists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New playlist")
            }
            Spacer(Modifier.height(12.dp))
            if (playlists.isEmpty()) {
                Text(
                    text = "No playlists yet. Create one to curate your own video list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists, key = { it.id }) { p ->
                        UserPlaylistRow(
                            playlist = p,
                            onOpen = { onOpen(p) },
                            onRename = { onRename(p) },
                            onDelete = { onDelete(p) }
                        )
                    }
                }
            }
        }
    }
}

/** One playlist row in the manager: leading thumbnail, name + count, rename / delete. */
@Composable
private fun UserPlaylistRow(
    playlist: UserPlaylist,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                val first = playlist.videos.firstOrNull()
                if (first != null) {
                    RemoteImage(url = first.thumbnailUrl, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.videos.size} video${if (playlist.videos.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Rename + delete consume their own taps (never open the playlist).
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Rename playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Create / rename dialog for a user playlist (blank names are rejected). */
@Composable
internal fun PlaylistNameDialog(
    initial: String,
    title: String,
    confirmLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Playlist name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) {
                        onSubmit(name)
                        keyboard?.hide()
                    } }
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSubmit(name) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * The playlist editor: every video in order with move-up / move-down / remove
 * controls, plus an "Add videos" button that opens the picker. All edits save
 * locally through the store and re-render instantly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEditorSheet(
    playlist: UserPlaylist,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (videoId: String) -> Unit,
    onAddVideos: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${playlist.videos.size} video${if (playlist.videos.size == 1) "" else "s"} · use ↑↓ to reorder",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (playlist.videos.isEmpty()) {
                Text(
                    text = "No videos yet. Tap Add videos to fill this playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(playlist.videos, key = { _, v -> v.videoId }) { index, video ->
                        PlaylistEditorRow(
                            video = video,
                            index = index,
                            count = playlist.videos.size,
                            onMoveUp = { onMove(index, index - 1) },
                            onMoveDown = { onMove(index, index + 1) },
                            onRemove = { onRemove(video.videoId) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAddVideos,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add videos")
            }
        }
    }
}

/** One ordered row in the playlist editor: thumbnail, title, reorder + remove. */
@Composable
private fun PlaylistEditorRow(
    video: MediaVideo,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${index + 1} · ${video.channelName.ifBlank { "YouTube" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = "Move up",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = index < count - 1, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "Move down",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove from playlist",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Multi-select picker of candidate videos to add into the open playlist — the
 * candidates follow the tab context (All Feed → every video, a channel tab →
 * that channel's). Only videos not already in the playlist are listed; already
 * present ones can never be duplicated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistAddVideosSheet(
    candidates: List<MediaVideo>,
    alreadyIn: Set<String>,
    onAdd: (List<MediaVideo>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val available = remember(candidates, alreadyIn) {
        candidates.filterNot { it.videoId in alreadyIn }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Add videos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${available.size} video${if (available.size == 1) "" else "s"} available · ${selectedIds.size} selected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (available.isEmpty()) {
                Text(
                    text = "Nothing left to add — every known video is already in this playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(available, key = { it.videoId }) { video ->
                        val isSelected = video.videoId in selectedIds
                        Surface(
                            onClick = {
                                selectedIds = if (isSelected) selectedIds - video.videoId
                                else selectedIds + video.videoId
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    RemoteImage(url = video.thumbnailUrl, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = video.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = video.channelName.ifBlank { "YouTube" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.Transparent
                                        )
                                        .border(
                                            1.5.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAdd(available.filter { it.videoId in selectedIds }) },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Add ${selectedIds.size} video${if (selectedIds.size == 1) "" else "s"}"
                )
            }
        }
    }
}

/**
 * "Add to playlist…" picker opened from a video card's ⋮ menu (or the video
 * player's ⋮ / Playlist button): tap a playlist to append the video (deduped —
 * it can't be added twice), or create a new playlist seeded with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    video: MediaVideo,
    playlists: List<UserPlaylist>,
    onAdd: (UserPlaylist) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            if (playlists.isEmpty()) {
                Text(
                    text = "No playlists yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // heightIn keeps the list bounded inside the sheet (a plain
                // LazyColumn must never measure against infinite height).
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 380.dp)
                ) {
                    items(playlists, key = { it.id }) { p ->
                        Surface(
                            onClick = { onAdd(p) },
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.PlaylistPlay,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = p.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${p.videos.size} video${if (p.videos.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCreateNew,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New playlist with this video")
            }
        }
    }
}
