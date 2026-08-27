package com.muddassir.clearview.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.MediaBadge
import com.muddassir.clearview.media.data.MediaRepository
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.OfflineAudioPlayer
import com.muddassir.clearview.media.model.MediaChannelUpdate
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.ui.AudioPlayerScreen
import com.muddassir.clearview.media.ui.LiveTab
import com.muddassir.clearview.media.ui.MediaTab
import com.muddassir.clearview.media.ui.VideoPlayerScreen
import com.muddassir.clearview.phonelimit.ui.PhoneLimitSheet
import com.muddassir.clearview.media.worker.MediaNotifier
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.quran.data.IslamicDateStore
import com.muddassir.clearview.quran.data.QuranRepository
import com.muddassir.clearview.quran.model.QuranVerse
import com.muddassir.clearview.quran.ui.DhikrCounterScreen
import com.muddassir.clearview.quran.ui.QuranTab
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.ui.TodoScreen
import com.muddassir.clearview.quran.util.copyVerseToClipboard
import com.muddassir.clearview.quran.util.formatVerseForSharing
import com.muddassir.clearview.quran.widget.QuranReminderWidgetProvider
import com.muddassir.clearview.quran.worker.QuranWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Islamic-content hub — the exact experience the Quran Reminder widget
 * opens. Both the widget activity ([QuranVerseActivity]) and the main app's
 * Quran / Media / Live tabs render this same state + content, so the two can
 * never drift apart.
 *
 * [ContentHubState] owns the tab selection, the playing video, and the Quran
 * verse state (verse, bookmark, refresh interval) plus the actions the top
 * bar can trigger (share / bookmark / copy / new verse / interval).
 */
enum class ContentTab { QURAN, MEDIA, LIVE }

class ContentHubState(appContext: Context) {

    var selectedTab by mutableStateOf(ContentTab.QURAN)
    var playingVideo by mutableStateOf<MediaVideo?>(null)
    // Offline audio playback (downloaded files). Only one of playingVideo /
    // playingAudio is non-null at a time — playing audio closes the video player.
    var playingAudio by mutableStateOf<DownloadItem?>(null)
    // Vertical fullscreen (YouTube Shorts style): the video fills the whole
    // portrait screen and the bars hide. Reset when the video changes/exits.
    var playerFullscreen by mutableStateOf(false)
    // Ordered Shorts list for the vertical viewer (empty for long videos) +
    // the index of the currently playing video within it.
    var shortsQueue by mutableStateOf<List<MediaVideo>>(emptyList())
    var shortsIndex by mutableStateOf(-1)
    // ── Media tab navigation state (survives player open/close) ────
    var mediaFilterChannelId by mutableStateOf<String?>(null)
    var mediaSelectedPlaylistId by mutableStateOf<String?>(null)
    var mediaSelectedUserPlaylistId by mutableStateOf<String?>(null)
    // Previous/Next verse navigation availability (false at the very first /
    // last verse of the Quran, or before the cache is loaded).
    var canGoPrevious by mutableStateOf(false)
    var canGoNext by mutableStateOf(false)
    var verse by mutableStateOf<QuranVerse?>(null)
    var verseLoading by mutableStateOf(false)
    var refreshIntervalHours by mutableStateOf(DEFAULT_REFRESH_INTERVAL_HOURS)
    var isBookmarked by mutableStateOf(false)

    // ── Media notifications (channel updates) ──────────────────────
    var mediaNotificationsEnabled by mutableStateOf(true)
    var mediaUpdates by mutableStateOf<List<MediaChannelUpdate>>(emptyList())
    var mediaUpdatesLoading by mutableStateOf(false)
    // Number of updates the user hasn't seen yet (drives the Media-tab badge).
    var unreadMediaUpdates by mutableStateOf(0)

    // ── Quran notifications (new verse) ────────────────────────────
    var quranNotificationsEnabled by mutableStateOf(true)

    // ── Todo reminders (alarm notifications) ───────────────────────
    var todoNotificationsEnabled by mutableStateOf(true)

    // ── Top-bar sheets on the Quran tab (search / settings / notifications) ──
    var showSearchSheet by mutableStateOf(false)
    var showSettingsSheet by mutableStateOf(false)
    var showNotificationsSheet by mutableStateOf(false)
    // Bookmarks manager opened from the settings sheet.
    var showBookmarksSheet by mutableStateOf(false)
    // Dhikr Counter screen opened from the settings sheet's Dhikr card.
    var showDhikrCounter by mutableStateOf(false)
    // Todo screen opened from the settings sheet's Todo card.
    var showTodoScreen by mutableStateOf(false)
    // Phone Limit sheet opened from the settings sheet's "Set Phone Limit" card.
    var showPhoneLimitSheet by mutableStateOf(false)

    // ── Islamic date (Umm al-Qura) on the Quran tab ────────────────
    // The user's ±1 day adjustment (0 = the default calculated date),
    // persisted via IslamicDateStore so it survives restarts.
    var islamicDateAdjustment by mutableStateOf(0)
    var showIslamicDateSheet by mutableStateOf(false)

    private val appContext: Context = appContext
    private val quranRepository = QuranRepository(appContext)
    private val mediaRepository = MediaRepository(appContext)
    private val islamicDateStore = IslamicDateStore(appContext)
    private val todoStore = TodoStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Only the current interval options (1/3/6/12/24 hr) are offered; a
        // stored value from an older build (2/4/8 hr) is coerced to 6 hr and
        // persisted so the scheduler and the UI never disagree.
        val stored = quranRepository.getRefreshIntervalHours()
        refreshIntervalHours = if (stored in VERSE_INTERVAL_OPTIONS) stored else {
            quranRepository.setRefreshIntervalHours(DEFAULT_REFRESH_INTERVAL_HOURS)
            // The periodic verse work was scheduled at the old interval (KEEP
            // policy never re-reads it) — reschedule so the UI and the actual
            // refresh cadence agree again.
            QuranWorkScheduler.reschedule(appContext)
            DEFAULT_REFRESH_INTERVAL_HOURS
        }
        mediaNotificationsEnabled = mediaRepository.isMediaNotificationsEnabled()
        quranNotificationsEnabled = quranRepository.getQuranNotificationsEnabled()
        todoNotificationsEnabled = todoStore.getTodoNotificationsEnabled()
        islamicDateAdjustment = islamicDateStore.adjustmentDays()
        verseLoading = true
    }

    /**
     * Loads the verse off the main thread and shows it. On every open the
     * PERSISTED current verse is shown (the one the widget displays) so the
     * app and the home-screen widget can never drift apart; a random verse is
     * only picked on the very first run, when nothing is persisted yet. The
     * widget is re-rendered afterwards so both surfaces stay in lock-step even
     * across a first-run race with the background worker.
     */
    fun start() {
        scope.launch {
            if (verse == null) {
                verseLoading = true
                var loaded = withContext(Dispatchers.IO) {
                    quranRepository.getCurrentVerse() ?: quranRepository.pickRandomVerse()
                }
                if (loaded == null) {
                    // Very first launch: nothing is cached yet, so the persisted
                    // verse doesn't exist and pickRandomVerse() has no data. The
                    // background worker downloads the translation separately, but
                    // this in-process download makes the result observable by
                    // the UI — when it completes, the verse is set and the tab
                    // recomposes immediately, with NO app restart required.
                    val downloaded = withContext(Dispatchers.IO) {
                        quranRepository.ensureEnglishAndArabic()
                    }
                    if (downloaded) {
                        loaded = withContext(Dispatchers.IO) {
                            quranRepository.pickRandomVerse()
                        }
                    }
                }
                verse = loaded
                verseLoading = false
                // Refresh on every open is cheap (one RemoteViews update) and
                // guarantees the widget reflects any in-app change immediately.
                if (loaded != null) QuranReminderWidgetProvider.refreshAllWidgets(appContext)
            }
            refreshNavAvailability()
        }
        refreshMediaUpdates()
    }

    // ── Media notifications (channel updates) ──────────────────────

    /**
     * Rebuilds the home-page "Latest Updates" feed from the persisted update
     * history (the entries the background worker recorded when it detected
     * uploads — each one matches a notification). Never network: reads the
     * newest [MediaUpdates.MAX] entries. Runs whenever the Quran (home) tab is
     * shown.
     */
    fun refreshMediaUpdates() {
        scope.launch {
            mediaUpdatesLoading = true
            val (updates, unread) = withContext(Dispatchers.IO) {
                // Fresh installs start empty until the first background check;
                // the one-time seed pre-fills from the cached feeds (never
                // re-runs after the history has been written, so dismissals
                // are never resurrected).
                mediaRepository.ensureUpdatesHistorySeeded(mediaRepository.getSavedChannels())
                val list = mediaRepository.getUpdatesHistory()
                list to mediaRepository.countUnreadUpdates(list)
            }
            mediaUpdates = updates
            unreadMediaUpdates = unread
            mediaUpdatesLoading = false
            // Keep the launcher app-icon badge in sync with the unread count.
            MediaBadge.setBadge(appContext, unread)
        }
    }

    /** Marks every currently listed update as seen (the Media tab was opened). */
    fun markMediaUpdatesSeen() {
        scope.launch {
            withContext(Dispatchers.IO) {
                mediaRepository.markUpdatesSeen(mediaUpdates.map { it.latestVideoId })
            }
            unreadMediaUpdates = 0
            MediaBadge.setBadge(appContext, 0)
        }
    }

    /**
     * Persists the media-notifications toggle and keeps the background check
     * running. Turning it ON also triggers an immediate check so channels that
     * already uploaded get notified right away. (The OS notification
     * permission is handled by the UI before this is called.)
     */
    fun setMediaNotifications(enabled: Boolean, context: Context) {
        mediaNotificationsEnabled = enabled
        mediaRepository.setMediaNotificationsEnabled(enabled)
        MediaWorkScheduler.ensureScheduled(context)
        if (enabled) {
            MediaWorkScheduler.checkNow(context)
            refreshMediaUpdates()
        }
    }

    /** Persists the Quran-verse notification toggle (OS notification on new verse). */
    fun setQuranNotifications(enabled: Boolean) {
        quranNotificationsEnabled = enabled
        quranRepository.setQuranNotificationsEnabled(enabled)
    }

    /**
     * Persists the global Todo-reminders toggle. Turning it ON re-registers
     * every pending todo alarm so existing todos get their reminders again
     * (turning it OFF leaves alarms unset — [TodoScheduler] skips them too).
     */
    fun setTodoNotifications(enabled: Boolean) {
        todoNotificationsEnabled = enabled
        todoStore.setTodoNotificationsEnabled(enabled)
        TodoScheduler.rescheduleAll(appContext)
    }

    /** Persists the ±1 day Islamic-date adjustment (0 = the default Umm al-Qura date). */
    fun changeIslamicDateAdjustment(days: Int) {
        islamicDateAdjustment = days.coerceIn(-1, 1)
        islamicDateStore.setAdjustmentDays(islamicDateAdjustment)
    }

    /**
     * Opens the notifications panel; viewing it marks every listed update as
     * seen (clears the bell / tab / launcher badges), like opening the Media
     * tab does.
     */
    fun openNotificationsPanel() {
        markMediaUpdatesSeen()
        showNotificationsSheet = true
    }

    /**
     * Removes one update from the "Latest Updates" feed (persisted — it won't
     * come back on the next refresh).
     */
    fun dismissUpdate(latestVideoId: String) {
        scope.launch {
            // Remove its OS notification too (deterministic per-channel id), so
            // the shade and the launcher bubble follow the in-app feed.
            mediaUpdates.firstOrNull { it.latestVideoId == latestVideoId }
                ?.let { MediaNotifier.cancelChannelNotification(appContext, it.channelId) }
            withContext(Dispatchers.IO) { mediaRepository.dismissUpdate(latestVideoId) }
            val updated = mediaUpdates.filterNot { it.latestVideoId == latestVideoId }
            mediaUpdates = updated
            // All updates gone → the group summary in the shade must go too,
            // otherwise the launcher bubble lingers after clearing the feed.
            if (updated.isEmpty()) MediaNotifier.cancelSummary(appContext)
            val unread = withContext(Dispatchers.IO) {
                mediaRepository.countUnreadUpdates(updated)
            }
            unreadMediaUpdates = unread
            MediaBadge.setBadge(appContext, unread)
        }
    }

    /** Starts a video from the Media tab; [queue]/[index] enable Shorts paging. */
    fun playVideo(video: MediaVideo, queue: List<MediaVideo> = emptyList(), index: Int = -1) {
        shortsQueue = queue
        shortsIndex = index
        playingAudio = null
        playingVideo = video
    }

    /**
     * Plays the downloaded audio for [video] immediately (podcast-style),
     * closing the WebView video player if it was open. No-op when the video
     * isn't downloaded yet.
     */
    fun playAudio(video: MediaVideo) {
        AudioDownloads.itemFor(video.videoId)?.let { playAudioItem(it) }
    }

    /** Plays [item] (its local audio file) immediately. */
    fun playAudioItem(item: DownloadItem) {
        playingVideo = null
        shortsQueue = emptyList()
        shortsIndex = -1
        playingAudio = item
    }

    /** Closes the audio player screen. Playback deliberately CONTINUES in the
     *  background (foreground service + media notification) — like any music
     *  app, leaving the player must not stop the audio.
     */
    fun exitAudio() {
        playingAudio = null
    }

    /** Vertical Shorts paging: +1 next, -1 previous (no-op at the ends). */
    fun navigateShorts(delta: Int) {
        val next = shortsIndex + delta
        if (next in shortsQueue.indices) {
            shortsIndex = next
            playingVideo = shortsQueue[next]
        }
    }

    /**
     * Plays the latest video of an update from the home feed. Prefers the
     * cached copy (instant); if it isn't cached yet, fetches that channel's
     * feed once so the player always has a valid id.
     */
    fun playMediaUpdate(update: MediaChannelUpdate) {
        scope.launch {
            val video = withContext(Dispatchers.IO) {
                mediaRepository.getCachedVideos(update.channelId)
                    ?.first?.firstOrNull { it.videoId == update.latestVideoId }
                    ?: mediaRepository.refreshVideos(update.channelId)
                        ?.firstOrNull { it.videoId == update.latestVideoId }
            }
            if (video != null) playingVideo = video
        }
    }

    /** Re-read the bookmark state for the current verse. */
    fun refreshBookmarkState() {
        isBookmarked = verse?.let { quranRepository.isBookmarked(it.surahNumber, it.ayahNumber) } == true
    }

    fun pickNewVerse() {
        verseLoading = true
        scope.launch {
            val next = withContext(Dispatchers.IO) { quranRepository.pickRandomVerse() }
            if (next != null) {
                verse = next
                // Keep the home-screen widget in sync with the in-app verse.
                QuranReminderWidgetProvider.refreshAllWidgets(appContext)
                // The top-bar bookmark icon must reflect the NEW verse instantly.
                refreshBookmarkState()
            }
            verseLoading = false
            refreshNavAvailability()
        }
    }

    // ── Verse navigation (previous / next) ─────────────────────────

    /**
     * Loads the adjacent verse (step = -1 previous / +1 next). Wraps across
     * surah boundaries automatically; no-op at the very first/last verse of
     * the Quran (the buttons are disabled there). Loads without the full-screen
     * spinner so the Crossfade in the UI can animate the swap smoothly.
     */
    fun goToAdjacentVerse(step: Int) {
        val v = verse ?: return
        scope.launch {
            val (next, prevAvailable, nextAvailable) = withContext(Dispatchers.IO) {
                val n = quranRepository.getAdjacentVerse(v.surahNumber, v.ayahNumber, step)
                if (n == null) {
                    Triple<QuranVerse?, Boolean, Boolean>(null, false, false)
                } else {
                    Triple(
                        n,
                        quranRepository.hasAdjacentVerse(n.surahNumber, n.ayahNumber, -1),
                        quranRepository.hasAdjacentVerse(n.surahNumber, n.ayahNumber, +1)
                    )
                }
            }
            if (next != null) {
                verse = next
                canGoPrevious = prevAvailable
                canGoNext = nextAvailable
                // Keep the home-screen widget in sync with the in-app verse, and
                // the top-bar bookmark icon with the NEW verse.
                QuranReminderWidgetProvider.refreshAllWidgets(appContext)
                refreshBookmarkState()
            }
        }
    }

    /** Re-evaluates whether Previous/Next have a verse to go to. */
    private fun refreshNavAvailability() {
        val v = verse ?: return
        scope.launch {
            val (prev, next) = withContext(Dispatchers.IO) {
                Pair(
                    quranRepository.hasAdjacentVerse(v.surahNumber, v.ayahNumber, -1),
                    quranRepository.hasAdjacentVerse(v.surahNumber, v.ayahNumber, +1)
                )
            }
            canGoPrevious = prev
            canGoNext = next
        }
    }

    fun copyVerse(context: Context) {
        val v = verse ?: return
        copyVerseToClipboard(context, v)
        Toast.makeText(context, context.getString(R.string.quran_verse_copied), Toast.LENGTH_SHORT).show()
    }

    fun shareVerse(context: Context) {
        val v = verse ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, formatVerseForSharing(context, v))
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.quran_share_via)))
    }

    fun toggleBookmark(context: Context) {
        val v = verse ?: return
        val added = quranRepository.toggleBookmark(v.surahNumber, v.ayahNumber)
        isBookmarked = added
        Toast.makeText(
            context,
            context.getString(if (added) R.string.quran_bookmarked else R.string.quran_bookmark_removed),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun changeInterval(context: Context, hours: Int) {
        refreshIntervalHours = hours
        quranRepository.setRefreshIntervalHours(hours)
        QuranWorkScheduler.reschedule(context)
        Toast.makeText(
            context,
            context.resources.getQuantityString(R.plurals.quran_verse_interval_set, hours, hours),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── Quran search / bookmarks manager ─────────────────────────────

    /**
     * Searches the cached translation; see [QuranRepository.searchVerses].
     * Empty when nothing is cached yet (or no matches).
     */
    suspend fun searchQuran(query: String): List<QuranVerse> =
        quranRepository.searchVerses(query)

    /**
     * Opens [v] as the current verse (persisted, widget synced, nav flags +
     * bookmark state refreshed) without the full-screen loading spinner — used
     * by search results and the bookmarks manager.
     */
    fun goToVerse(v: QuranVerse) {
        verse = v
        scope.launch {
            withContext(Dispatchers.IO) {
                // Persist as the current verse so the widget follows instantly.
                quranRepository.saveCurrentVerse(v)
            }
            QuranReminderWidgetProvider.refreshAllWidgets(appContext)
            refreshNavAvailability()
            refreshBookmarkState()
        }
    }

    /** Resolves every saved bookmark into its full verse (enriched with Arabic). */
    suspend fun bookmarkedVerses(): List<QuranVerse> = quranRepository.getBookmarkedVerses()

    /** Number of saved bookmarks (instant prefs read, for the settings card). */
    val bookmarkCount: Int
        get() = quranRepository.getBookmarks().size

    /** Removes a bookmark (no-op when not bookmarked); updates the top-bar icon. */
    fun removeBookmark(surahNumber: Int, ayahNumber: Int) {
        quranRepository.removeBookmark(surahNumber, ayahNumber)
        refreshBookmarkState()
    }

    fun cancel() {
        scope.cancel()
    }
}

@Composable
fun rememberContentHubState(): ContentHubState {
    val context = LocalContext.current
    val state = remember { ContentHubState(context.applicationContext) }
    LaunchedEffect(state.verse) {
        state.refreshBookmarkState()
    }
    DisposableEffect(Unit) {
        state.start()
        onDispose { state.cancel() }
    }
    return state
}

/** True when the current orientation is landscape. */
@Composable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * The hub's tab content: the playing video (full screen), or the Quran /
 * Media / Live tab. The video player replaces the tab so playback keeps its
 * full composition slot.
 */
@Composable
fun ContentHubTabContent(
    state: ContentHubState,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        // Refresh the home-page updates feed whenever the Quran (home) tab is
        // shown — the worker and Media tab keep the caches fresh, so this is a
        // cheap local read that picks up any new uploads.
        LaunchedEffect(state.selectedTab) {
            if (state.selectedTab == ContentTab.QURAN) state.refreshMediaUpdates()
        }
        // Leaving the player (or switching to a different video) always exits
        // vertical fullscreen — EXCEPT when navigating within the Shorts queue
        // (swipe up/down), where fullscreen must stay put.
        LaunchedEffect(state.playingVideo?.videoId) {
            val current = state.playingVideo
            val inShortsQueue = current != null &&
                state.shortsIndex in state.shortsQueue.indices &&
                state.shortsQueue[state.shortsIndex].videoId == current.videoId
            if (!inShortsQueue) state.playerFullscreen = false
        }
        when {
            state.playingVideo != null -> VideoPlayerScreen(
                video = state.playingVideo!!,
                isLandscape = isLandscape,
                fullscreenVertical = state.playerFullscreen,
                onToggleFullscreen = { state.playerFullscreen = !state.playerFullscreen },
                onExit = { state.playingVideo = null },
                onPlayOffline = { state.playAudio(state.playingVideo!!) },
                shortsQueue = state.shortsQueue,
                shortsIndex = state.shortsIndex,
                onNavigateShorts = { state.navigateShorts(it) }
            )

            state.playingAudio != null -> AudioPlayerScreen(
                item = state.playingAudio!!,
                onExit = { state.exitAudio() }
            )

            state.selectedTab == ContentTab.QURAN -> QuranTab(
                verse = state.verse,
                isLoading = state.verseLoading,
                onNewVerse = { state.pickNewVerse() },
                onCopyVerse = { state.copyVerse(context) },
                canGoPrevious = state.canGoPrevious,
                canGoNext = state.canGoNext,
                onPrevious = { state.goToAdjacentVerse(-1) },
                onNext = { state.goToAdjacentVerse(+1) },
                islamicDateAdjustment = state.islamicDateAdjustment,
                onAdjustDate = { state.showIslamicDateSheet = true }
            )

            state.selectedTab == ContentTab.MEDIA -> MediaTab(
                hubState = state,
                onPlayVideo = { video, queue, index ->
                    state.playVideo(video, queue, index)
                    if (index < 0) state.markMediaUpdatesSeen()
                },
                onPlayOffline = { video -> state.playAudio(video) },
                onPlayAudio = { item -> state.playAudioItem(item) },
                onMediaOpened = { state.markMediaUpdatesSeen() }
            )

            else -> LiveTab(isLandscape = isLandscape)
        }
    }
}

/**
 * The hub's top bar: while a video plays it shows the video title (plus the
 * optional back action); on the Quran tab it shows the verse header with
 * share / bookmark / copy actions; Media and Live get their titles. Pass
 * [onBack] to show a back arrow (the widget passes an activity-finish; the
 * main app passes player-exit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentHubTopBar(
    state: ContentHubState,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    when {
        // While a video plays, the back arrow ALWAYS returns to the Media tab
        // (clears the player) — regardless of [onBack]. Both the widget and the
        // main app want this; [onBack] only applies to the tab top bars.
        state.playingVideo != null -> TopAppBar(
            title = {
                Text(
                    text = state.playingVideo!!.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { state.playingVideo = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Offline audio: back arrow closes the podcast-style player; the bar
        // shows "Now Playing" (the player body itself shows the track title).
        state.playingAudio != null -> TopAppBar(
            title = {
                Text(
                    text = "Now Playing",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { state.exitAudio() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        state.selectedTab == ContentTab.QURAN -> TopAppBar(
            title = { Text(stringResource(R.string.quran_verse_header)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                // Search: by verse number or English translation text.
                IconButton(
                    onClick = { state.showSearchSheet = true },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.quran_search)
                    )
                }
                // More options: verse refresh interval + notification toggles and
                // the feature hub (Dhikr counter, Todo, bookmarks) — no longer
                // just settings, so it uses the overflow icon.
                IconButton(
                    onClick = { state.showSettingsSheet = true },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.quran_more_options)
                    )
                }
                IconButton(
                    onClick = { state.toggleBookmark(context) },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    // Filled + primary-tinted when bookmarked, outlined + muted
                    // otherwise — the state change is unmistakable at a glance.
                    Icon(
                        imageVector = if (state.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                        contentDescription = stringResource(R.string.quran_bookmark),
                        tint = if (state.isBookmarked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Notifications: opens the updates panel; badge = unread count.
                IconButton(
                    onClick = { state.openNotificationsPanel() },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    if (state.unreadMediaUpdates > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(state.unreadMediaUpdates.coerceAtMost(99).toString())
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Notifications,
                                contentDescription = stringResource(R.string.quran_notifications_title),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = stringResource(R.string.quran_notifications_title)
                        )
                    }
                }
            }
        )

        state.selectedTab == ContentTab.MEDIA -> TopAppBar(
            title = { Text(stringResource(R.string.media_tab)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
        )

        else -> TopAppBar(
            title = { Text(stringResource(R.string.live_tab)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
        )
    }

    // Sheets opened from the Quran tab top bar: search, settings, notifications
    // and the bookmarks manager (the latter is opened from the settings sheet).
    if (state.showSearchSheet) {
        QuranSearchScreen(state = state, onDismiss = { state.showSearchSheet = false })
    }
    if (state.showSettingsSheet) {
        QuranSettingsSheet(state = state, onDismiss = { state.showSettingsSheet = false })
    }
    if (state.showNotificationsSheet) {
        NotificationsSheet(state = state, onDismiss = { state.showNotificationsSheet = false })
    }
    if (state.showBookmarksSheet) {
        BookmarksSheet(state = state, onDismiss = { state.showBookmarksSheet = false })
    }
    if (state.showDhikrCounter) {
        DhikrCounterScreen(onDismiss = { state.showDhikrCounter = false })
    }
    if (state.showTodoScreen) {
        TodoScreen(onDismiss = { state.showTodoScreen = false })
    }
    if (state.showIslamicDateSheet) {
        IslamicDateAdjustmentSheet(
            adjustment = state.islamicDateAdjustment,
            onSelect = { state.changeIslamicDateAdjustment(it) },
            onDismiss = { state.showIslamicDateSheet = false }
        )
    }
    // Phone Limit: countdown that locks the phone when it expires. Opened from
    // the settings sheet's "Set Phone Limit" card (Quran tab ⋮ menu).
    if (state.showPhoneLimitSheet) {
        PhoneLimitSheet(onDismiss = { state.showPhoneLimitSheet = false })
    }
}

// ── Constants shared with QuranTab's interval picker ──────────────

/** The only verse-refresh intervals offered (1/3/6/12/24 hr). */
val VERSE_INTERVAL_OPTIONS = listOf(1, 3, 6, 12, 24)
private const val DEFAULT_REFRESH_INTERVAL_HOURS = 6

/** Icon + label pair used by the shared bottom navigation. */
data class ContentHubNavItem(
    val tab: ContentTab,
    val icon: @Composable () -> Unit,
    val label: String
)

/** Standard hub navigation items (Quran, Media, Live) with resource labels. */
@Composable
fun contentHubNavItems(): List<ContentHubNavItem> = listOf(
    ContentHubNavItem(
        ContentTab.QURAN,
        { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
        stringResource(R.string.quran_tab)
    ),
    ContentHubNavItem(
        ContentTab.MEDIA,
        { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
        stringResource(R.string.media_tab)
    ),
    ContentHubNavItem(
        ContentTab.LIVE,
        { Icon(Icons.Filled.LiveTv, contentDescription = null) },
        stringResource(R.string.live_tab)
    )
)

/**
 * Hides the system bars for immersive playback when [isFullscreen] (landscape
 * with a playing video or the live tab); restores them otherwise. No-op when
 * the current context isn't an [Activity].
 */
@Composable
fun ApplyImmersiveIfNeeded(isFullscreen: Boolean) {
    val activity = LocalActivity.current
    LaunchedEffect(isFullscreen) {
        if (activity != null) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
