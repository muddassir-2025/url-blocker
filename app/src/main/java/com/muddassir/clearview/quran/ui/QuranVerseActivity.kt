package com.muddassir.clearview.quran.ui

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
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.worker.AudioWorkScheduler
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.ui.ContentHubTabContent
import com.muddassir.clearview.ui.ContentHubTopBar
import com.muddassir.clearview.ui.applyImmersiveIfNeeded
import com.muddassir.clearview.ui.isLandscape
import com.muddassir.clearview.ui.rememberContentHubState
import com.muddassir.clearview.ui.theme.UrlblockerTheme

/**
 * Quran-only screen — opened by tapping the Quran Reminder widget.
 *
 * Deliberately NO bottom navigation: the widget experience is exclusively the
 * Quran tab (the complete current verse with copy / share / bookmark and the
 * refresh-frequency picker). It renders through the same shared
 * [ContentHubState] / content composables as the main app, so the verse shown
 * here is ALWAYS the exact verse the widget displays and vice-versa — the
 * widget and the app can never drift apart.
 *
 * The activity declares android:configChanges (see AndroidManifest) so
 * rotation never recreates it.
 */
class QuranVerseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Media updates: the widget is the app's primary entry point, so the
        // hourly channel-update check must be scheduled here too — otherwise a
        // user who only ever opens the app via the widget would never get media
        // notifications (MainActivity schedules it, but may never be opened).
        // Idempotent (WorkManager KEEP policy), safe to call every time.
        MediaWorkScheduler.ensureScheduled(this)
        // Offline audio downloads: app-wide init + daily cleanup — the widget
        // activity must schedule these too (it hosts the same ContentHub).
        AudioDownloads.initialize(this)
        AudioWorkScheduler.ensureScheduled(this)
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

    // System back while the offline audio player is open closes it.
    BackHandler(enabled = hub.playingAudio != null) {
        hub.exitAudio()
    }

    // Immersive fullscreen: landscape + a playing video (or the vertical
    // Shorts-style fullscreen) hides the system bars; portrait restores them.
    // Playback is never restarted because the activity doesn't recreate on
    // rotation (configChanges in the manifest).
    val isFullscreen =
        (landscape && hub.playingVideo != null) ||
            (hub.playerFullscreen && hub.playingVideo != null)
    applyImmersiveIfNeeded(isFullscreen)

    // Widget surface = Quran only: no bottom navigation bar. ContentHubTabContent
    // renders the persisted verse (the same one the widget shows); the selected
    // tab can never leave QURAN because there is no way to switch it here.
    Scaffold(
        topBar = {
            if (!isFullscreen) {
                ContentHubTopBar(
                    state = hub,
                    onBack = { (context as? Activity)?.finish() }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ContentHubTabContent(state = hub, isLandscape = landscape)
        }
    }
}
