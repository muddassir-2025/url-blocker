package com.example.url_blocker.quran.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.url_blocker.ui.ContentHubTabContent
import com.example.url_blocker.ui.ContentHubTopBar
import com.example.url_blocker.ui.ContentTab
import com.example.url_blocker.ui.applyImmersiveIfNeeded
import com.example.url_blocker.ui.contentHubNavItems
import com.example.url_blocker.ui.isLandscape
import com.example.url_blocker.ui.rememberContentHubState
import com.example.url_blocker.ui.theme.UrlblockerTheme

/**
 * Islamic content hub — opened by tapping the Quran Reminder widget.
 *
 * Three tabs behind a bottom navigation bar:
 *  - Quran: the complete current verse (Arabic + English) with copy / share /
 *    bookmark and the refresh-frequency picker.
 *  - Media: latest videos from saved YouTube channels, played INSIDE the app.
 *  - Live: Makkah / Madinah live broadcasts, played INSIDE the app.
 *
 * All three render through the shared [ContentHubState] / content composables,
 * the same ones the main app's Quran / Media / Live tabs use — the widget and
 * the app can never drift apart.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubScreen() {
    val context = LocalContext.current
    val landscape = isLandscape()
    val hub = rememberContentHubState()

    // System back while a video is playing exits vertical fullscreen first
    // (Shorts style), then returns to the Media tab — in landscape fullscreen
    // there is no visible top-bar back button.
    BackHandler(enabled = hub.playingVideo != null) {
        if (hub.playerFullscreen) hub.playerFullscreen = false
        else hub.playingVideo = null
    }

    // Immersive fullscreen: landscape + (video player OR live tab), or the
    // vertical Shorts-style fullscreen, hides the system bars; portrait
    // restores them. Playback is never restarted because the activity doesn't
    // recreate on rotation (configChanges in the manifest).
    val isFullscreen =
        (landscape && (hub.playingVideo != null || hub.selectedTab == ContentTab.LIVE)) ||
            (hub.playerFullscreen && hub.playingVideo != null)
    applyImmersiveIfNeeded(isFullscreen)

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                ContentHubTopBar(
                    state = hub,
                    onBack = { (context as? Activity)?.finish() }
                )
            }
        },
        bottomBar = {
            if (hub.playingVideo == null && !isFullscreen) {
                NavigationBar {
                    contentHubNavItems().forEach { item ->
                        NavigationBarItem(
                            selected = hub.selectedTab == item.tab,
                            onClick = { hub.selectedTab = item.tab },
                            icon = item.icon,
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ContentHubTabContent(state = hub, isLandscape = landscape)
        }
    }
}
