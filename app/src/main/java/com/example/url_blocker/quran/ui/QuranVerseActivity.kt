package com.example.url_blocker.quran.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.url_blocker.R
import com.example.url_blocker.media.model.MediaVideo
import com.example.url_blocker.media.ui.LiveTab
import com.example.url_blocker.media.ui.MediaTab
import com.example.url_blocker.media.ui.VideoPlayerScreen
import com.example.url_blocker.quran.data.QuranRepository
import com.example.url_blocker.quran.util.copyVerseToClipboard
import com.example.url_blocker.quran.util.formatVerseForSharing
import com.example.url_blocker.quran.worker.QuranWorkScheduler
import com.example.url_blocker.ui.theme.UrlblockerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Islamic content hub — opened by tapping the Quran Reminder widget.
 *
 * Three tabs behind a bottom navigation bar:
 *  - Quran: the complete current verse (Arabic + English) with copy / share /
 *    bookmark and the refresh-frequency picker.
 *  - Media: latest videos from saved YouTube channels, played INSIDE the app.
 *  - Live: Makkah / Madinah live broadcasts, played INSIDE the app.
 *
 * The activity declares android:configChanges (see AndroidManifest) so
 * rotation never recreates it — the embedded player keeps playing while the
 * UI switches to immersive landscape fullscreen and back.
 */
class QuranVerseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UrlblockerTheme {
                HubScreen()
            }
        }
    }
}

private enum class HubTab { QURAN, MEDIA, LIVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectedTab by rememberSaveable { mutableStateOf(HubTab.QURAN) }
    var playingVideo by remember { mutableStateOf<MediaVideo?>(null) }

    // System back while a video is playing returns to the Media tab instead of
    // finishing the whole activity (in landscape fullscreen there is no visible
    // top-bar back button).
    BackHandler(enabled = playingVideo != null) {
        playingVideo = null
    }

    // Quran verse state (hoisted so the top bar can act on it).
    val quranRepository = remember { QuranRepository(context.applicationContext) }
    var verse by remember { mutableStateOf(quranRepository.getCurrentVerse()) }
    var verseLoading by remember { mutableStateOf(verse == null) }
    var refreshIntervalHours by remember { mutableStateOf(quranRepository.getRefreshIntervalHours()) }
    var isBookmarked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(verse) {
        isBookmarked = verse?.let { quranRepository.isBookmarked(it.surahNumber, it.ayahNumber) } == true
    }

    // Initial load: show whatever is cached; pick a first verse if cached.
    LaunchedEffect(Unit) {
        if (verse == null) {
            verseLoading = true
            val loaded = withContext(Dispatchers.IO) {
                if (quranRepository.isCached()) quranRepository.pickRandomVerse() else null
            }
            verse = loaded
            verseLoading = false
        }
    }

    fun pickNewVerse() {
        verseLoading = true
        scope.launch {
            val next = withContext(Dispatchers.IO) { quranRepository.pickRandomVerse() }
            if (next != null) verse = next
            verseLoading = false
        }
    }

    fun copyVerse() {
        verse?.let { v ->
            copyVerseToClipboard(context, v)
            Toast.makeText(context, context.getString(R.string.quran_verse_copied), Toast.LENGTH_SHORT).show()
        }
    }

    fun shareVerse() {
        verse?.let { v ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, formatVerseForSharing(context, v))
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.quran_share_via)))
        }
    }

    fun toggleBookmark() {
        verse?.let { v ->
            val added = quranRepository.toggleBookmark(v.surahNumber, v.ayahNumber)
            isBookmarked = added
            Toast.makeText(
                context,
                context.getString(if (added) R.string.quran_bookmarked else R.string.quran_bookmark_removed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun changeInterval(hours: Int) {
        refreshIntervalHours = hours
        quranRepository.setRefreshIntervalHours(hours)
        QuranWorkScheduler.reschedule(context)
        Toast.makeText(
            context,
            context.resources.getQuantityString(R.plurals.quran_verse_interval_set, hours, hours),
            Toast.LENGTH_SHORT
        ).show()
    }

    // Immersive fullscreen: landscape + (video player OR live tab) hides the
    // system bars; portrait restores them. Playback is never restarted because
    // the activity doesn't recreate on rotation (configChanges in the manifest).
    val isFullscreen = isLandscape && (playingVideo != null || selectedTab == HubTab.LIVE)
    val activity = context as? Activity
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

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                when {
                    playingVideo != null -> TopAppBar(
                        title = {
                            Text(
                                text = playingVideo!!.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { playingVideo = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )

                    selectedTab == HubTab.QURAN -> TopAppBar(
                        title = { Text(stringResource(R.string.quran_verse_header)) },
                        navigationIcon = {
                            IconButton(onClick = { (context as? Activity)?.finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { shareVerse() },
                                enabled = verse != null && !verseLoading
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.quran_share))
                            }
                            IconButton(
                                onClick = { toggleBookmark() },
                                enabled = verse != null && !verseLoading
                            ) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                                    contentDescription = stringResource(R.string.quran_bookmark)
                                )
                            }
                            IconButton(
                                onClick = { copyVerse() },
                                enabled = verse != null && !verseLoading
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.quran_verse_copy))
                            }
                        }
                    )

                    selectedTab == HubTab.MEDIA -> TopAppBar(title = { Text(stringResource(R.string.media_tab)) })

                    else -> TopAppBar(title = { Text(stringResource(R.string.live_tab)) })
                }
            }
        },
        bottomBar = {
            if (playingVideo == null && !isFullscreen) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == HubTab.QURAN,
                        onClick = { selectedTab = HubTab.QURAN },
                        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                        label = { Text(stringResource(R.string.quran_tab)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == HubTab.MEDIA,
                        onClick = { selectedTab = HubTab.MEDIA },
                        icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                        label = { Text(stringResource(R.string.media_tab)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == HubTab.LIVE,
                        onClick = { selectedTab = HubTab.LIVE },
                        icon = { Icon(Icons.Filled.LiveTv, contentDescription = null) },
                        label = { Text(stringResource(R.string.live_tab)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                playingVideo != null -> VideoPlayerScreen(
                    video = playingVideo!!,
                    isLandscape = isLandscape
                )

                selectedTab == HubTab.QURAN -> QuranTab(
                    verse = verse,
                    isLoading = verseLoading,
                    refreshIntervalHours = refreshIntervalHours,
                    onRefreshIntervalChange = { changeInterval(it) },
                    onNewVerse = { pickNewVerse() },
                    onCopyVerse = { copyVerse() }
                )

                selectedTab == HubTab.MEDIA -> MediaTab(onPlayVideo = { playingVideo = it })

                else -> LiveTab(isLandscape = isLandscape)
            }
        }
    }
}
