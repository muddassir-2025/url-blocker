package com.muddassir.clearview

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.worker.AudioWorkScheduler
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.phonelimit.PhoneLimitCoordinator
import com.muddassir.clearview.quran.worker.QuranWorkScheduler
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.ui.BlockTab
import com.muddassir.clearview.ui.ContentHubTabContent
import com.muddassir.clearview.ui.ContentHubTopBar
import com.muddassir.clearview.ui.ContentTab
import com.muddassir.clearview.ui.ApplyImmersiveIfNeeded
import com.muddassir.clearview.ui.contentHubNavItems
import com.muddassir.clearview.ui.isLandscape
import com.muddassir.clearview.ui.rememberContentHubState
import com.muddassir.clearview.ui.theme.UrlblockerTheme
import com.muddassir.clearview.viewmodel.MainViewModel

open class MainActivity : ComponentActivity() {

    /**
     * Warm-start request for the Todo screen: a Todo-reminder notification tap
     * while the app is already running delivers a new intent via [onNewIntent];
     * this snapshot state lets MainScreen react. The cold-start path reads the
     * same flag straight from the launcher intent instead.
     */
    private val todoRequestState = mutableStateOf(false)
    val todoScreenRequested: Boolean get() = todoRequestState.value

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(TodoNotifier.EXTRA_OPEN_TODO, false)) {
            intent.removeExtra(TodoNotifier.EXTRA_OPEN_TODO)
            todoRequestState.value = true
        }
        // Phone Limit deep link (widget START fallback / expiry notification).
        if (intent.getBooleanExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT, false)) {
            intent.removeExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT)
            phoneLimitRequestState.value = true
        }
    }

    /** Clears the warm-start request after MainScreen has handled it. */
    fun consumeTodoScreenRequest() {
        todoRequestState.value = false
    }

    // ── Phone Limit deep link (widget fallback / expiry notification) ──

    /** Warm-start request for the Phone Limit sheet (same pattern as Todo). */
    private val phoneLimitRequestState = mutableStateOf(false)
    val phoneLimitScreenRequested: Boolean get() = phoneLimitRequestState.value

    /** Clears the warm-start request after MainScreen has handled it. */
    fun consumePhoneLimitScreenRequest() {
        phoneLimitRequestState.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android notification channels are permanent once created — they
        // persist across app updates and never clean up on their own. Older
        // builds registered channels that are no longer created (or duplicated
        // the "Channel updates" name), so remove them on every startup:
        //   - "media_badge": a second channel that shared the "Channel
        //     updates" name (the launcher-badge notification now reuses
        //     media_updates); deleting it collapses the duplicate entries in
        //     system notification settings.
        //   - "protection_monitor": retired from the code long ago.
        // Both calls are idempotent no-ops on devices that never had them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("media_badge")
            nm.deleteNotificationChannel("protection_monitor")
            // Todo reminders: keep both channels (reminders + the silent alarm
            // channel) in their correct state from the very first launch — an
            // older build's alarm channel without the silent tone must be
            // rebuilt here, not only when the first alarm happens to fire.
            TodoNotifier.ensureChannel(this)
        }

        // Quran Reminder: ensure the initial download + 6-hour refresh work is
        // scheduled the first time the app opens (idempotent). The widget also
        // schedules this on add, so the first verse is ready either way.
        QuranWorkScheduler.ensureScheduled(this)

        // Media updates: periodic hourly check for new channel uploads
        // (the worker no-ops while the toggle is off).
        MediaWorkScheduler.ensureScheduled(this)

        // Offline audio downloads: app-wide init (idempotent) + the daily
        // cleanup worker (expired / orphans / stale .part files).
        AudioDownloads.initialize(this)
        AudioWorkScheduler.ensureScheduled(this)

        // Use ViewModelProvider (not @Composable viewModel()) since we're in onCreate
        val viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Re-lock the Block tab when the app leaves the foreground: the
                // password is needed again the next time the Block tab is opened.
                // Quran / Media / Live stay freely accessible (like the widget).
                viewModel.lockApp()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkHasPassword()
                // Immediately re-read protection status when the user returns to
                // the app (e.g., after toggling the Accessibility Service in
                // Settings).
                viewModel.checkAccessibilityStatus(this)
                viewModel.checkDeviceAdminStatus(this)
                
                // The user may have just returned from granting the exact-alarm
                // permission (SCHEDULE_EXACT_ALARM). Re-sync every todo alarm:
                // the ones scheduled BEFORE the grant were inexact, and a
                // re-arm now upgrades them to exact so reminders ring on time.
                TodoScheduler.rescheduleAll(this)
            }
        })

        setContent {
            UrlblockerTheme {
                MainScreen()
            }
        }
    }
}

private enum class MainTab { QURAN, MEDIA, LIVE, BLOCK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val landscape = isLandscape()

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.QURAN) }
    val hub = rememberContentHubState()

    // A Todo-reminder notification tap opens straight into the Todo screen —
    // either from a cold start (the launcher intent carries EXTRA_OPEN_TODO) or
    // a warm start (onNewIntent set todoScreenRequested). Both switch to the
    // Quran tab first so the top bar that hosts the Todo screen is composed.
    val activity = LocalActivity.current as? MainActivity
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra(TodoNotifier.EXTRA_OPEN_TODO, false) == true) {
            activity.intent.removeExtra(TodoNotifier.EXTRA_OPEN_TODO)
            selectedTab = MainTab.QURAN
            hub.selectedTab = ContentTab.QURAN
            hub.showTodoScreen = true
        }
    }
    val todoRequested = activity?.todoScreenRequested == true
    LaunchedEffect(todoRequested) {
        if (todoRequested) {
            activity?.consumeTodoScreenRequest()
            selectedTab = MainTab.QURAN
            hub.selectedTab = ContentTab.QURAN
            hub.showTodoScreen = true
        }
    }

    // A Phone Limit deep link (widget START fallback, expiry notification tap)
    // opens straight into the Phone Limit sheet — the "timer window", not just
    // the Quran tab. Cold start: the launcher intent carries the flag; warm
    // start: onNewIntent set phoneLimitRequested.
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra(
                PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT, false
            ) == true
        ) {
            activity.intent.removeExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT)
            selectedTab = MainTab.QURAN
            hub.selectedTab = ContentTab.QURAN
            hub.showPhoneLimitSheet = true
        }
    }
    val phoneLimitRequested = activity?.phoneLimitScreenRequested == true
    LaunchedEffect(phoneLimitRequested) {
        if (phoneLimitRequested) {
            activity?.consumePhoneLimitScreenRequest()
            selectedTab = MainTab.QURAN
            hub.selectedTab = ContentTab.QURAN
            hub.showPhoneLimitSheet = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        viewModel.checkAccessibilityStatus(context)
    }

    // Periodically check accessibility and device admin status while the app
    // is in the FOREGROUND (the user may leave to Settings, toggle the service,
    // and return). The loop is paused while backgrounded so it never burns
    // battery polling an invisible UI. Wrapped in try/catch so a single failure
    // can never silently kill the refresh loop.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            resumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        try {
            while (true) {
                if (resumed) {
                    try {
                        viewModel.checkAccessibilityStatus(context)
                        viewModel.checkDeviceAdminStatus(context)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Status refresh failed: ${e.message}")
                    }
                }
                kotlinx.coroutines.delay(3000)
            }
        } finally {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Device Admin launcher — hoisted to MainScreen level for stability
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            android.util.Log.i("MainActivity", "Device Admin activated via result")
        }
    }

    // System back while a video is playing exits vertical fullscreen first
    // (Shorts style), then returns to the Media tab — in landscape fullscreen
    // there is no visible top-bar back button.
    BackHandler(enabled = hub.playingVideo != null) {
        if (hub.playerFullscreen) hub.playerFullscreen = false
        else hub.playingVideo = null
    }

    // System back while the offline audio player is open closes it (stops
    // playback), like the top-bar back arrow.
    BackHandler(enabled = hub.playingAudio != null) {
        hub.exitAudio()
    }

    // Immersive fullscreen: landscape + (video player OR live tab), or the
    // vertical Shorts-style fullscreen, hides the system bars; portrait
    // restores them. Playback is never restarted because the activity doesn't
    // recreate on rotation (configChanges in the manifest).
    val isFullscreen =
        (landscape && (hub.playingVideo != null || hub.selectedTab == ContentTab.LIVE)) ||
            (hub.playerFullscreen && hub.playingVideo != null)
    ApplyImmersiveIfNeeded(isFullscreen)

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                if (selectedTab == MainTab.BLOCK) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    "ClearView",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Security & controls",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            // Lock indicator — tap to re-lock the Block tab.
                            IconButton(onClick = {
                                if (viewModel.hasPassword) {
                                    viewModel.lockApp()
                                }
                            }) {
                                Icon(
                                    imageVector = if (viewModel.isAppLocked && viewModel.hasPassword)
                                        Icons.Filled.Lock
                                    else
                                        Icons.Outlined.LockOpen,
                                    contentDescription = if (viewModel.hasPassword) "Lock Block tab" else "No password set",
                                    tint = if (viewModel.hasPassword && viewModel.isAppLocked)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    // The main app has nothing to navigate back to on the content
                    // tabs; the shared top bar's player branch handles its own
                    // back (returns to the Media tab).
                    ContentHubTopBar(state = hub, onBack = null)
                }
            }
        },
        bottomBar = {
            if (hub.playingVideo == null && hub.playingAudio == null && !isFullscreen) {
                NavigationBar {
                    contentHubNavItems().forEach { item ->
                        NavigationBarItem(
                            selected = selectedTab == tabFor(item.tab),
                            onClick = {
                                hub.selectedTab = item.tab
                                selectedTab = tabFor(item.tab)
                            },
                            icon = {
                                // Channel-update badge: shows the number of
                                // unseen updates; clears once Media is opened.
                                if (item.tab == ContentTab.MEDIA && hub.unreadMediaUpdates > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge {
                                                Text(hub.unreadMediaUpdates.coerceAtMost(99).toString())
                                            }
                                        }
                                    ) {
                                        item.icon()
                                    }
                                } else {
                                    item.icon()
                                }
                            },
                            label = { Text(item.label) }
                        )
                    }
                    NavigationBarItem(
                        selected = selectedTab == MainTab.BLOCK,
                        onClick = { selectedTab = MainTab.BLOCK },
                        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                        label = { Text("Block") }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                MainTab.BLOCK -> {
                    // The Block tab is the ONLY password-protected surface. It
                    // is gated on first open too: no password set → the setup
                    // screen forces one before the dashboard is revealed;
                    // password set but locked → unlock screen; otherwise the
                    // security dashboard.
                    if (!viewModel.hasPassword || viewModel.shouldShowLockScreen()) {
                        LockScreen(
                            onUnlock = { password -> viewModel.verifyAppPassword(password) },
                            onSetupPassword = { password -> viewModel.setAppPassword(password) },
                            hasPassword = viewModel.hasPassword
                        )
                    } else {
                        BlockTab(viewModel, deviceAdminLauncher)
                    }
                }
                else -> ContentHubTabContent(state = hub, isLandscape = landscape)
            }
        }
    }
}

private fun tabFor(tab: ContentTab): MainTab = when (tab) {
    ContentTab.QURAN -> MainTab.QURAN
    ContentTab.MEDIA -> MainTab.MEDIA
    ContentTab.LIVE -> MainTab.LIVE
}

@Composable
private fun LockScreen(
    onUnlock: (String) -> Boolean,
    onSetupPassword: (String) -> Unit,
    hasPassword: Boolean
) {
    var passwordInput by remember { mutableStateOf("") }
    var setupPasswordInput by remember { mutableStateOf("") }
    var setupConfirmInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSetupMode by remember { mutableStateOf(!hasPassword) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isSetupMode) "Set Block Password" else "Block Tab Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSetupMode)
                    "Create a password to protect the Block tab (keywords, websites and protection controls)"
                else
                    "Enter password to open Block settings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isSetupMode) {
                OutlinedTextField(
                    value = setupPasswordInput,
                    onValueChange = { setupPasswordInput = it; errorMessage = null },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = setupConfirmInput,
                    onValueChange = { setupConfirmInput = it; errorMessage = null },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (setupPasswordInput.trim().length < 4) {
                            errorMessage = "Password must be at least 4 characters"
                        } else if (setupPasswordInput != setupConfirmInput) {
                            errorMessage = "Passwords do not match"
                        } else {
                            onSetupPassword(setupPasswordInput)
                            setupPasswordInput = ""
                            setupConfirmInput = ""
                            isSetupMode = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set Password")
                }
            } else {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it; errorMessage = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (onUnlock(passwordInput)) {
                            passwordInput = ""
                            errorMessage = null
                        } else {
                            errorMessage = "Incorrect password"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock")
                }
            }
        }
    }
}
