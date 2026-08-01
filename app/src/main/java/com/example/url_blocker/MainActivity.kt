package com.example.url_blocker

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.url_blocker.quran.worker.QuranWorkScheduler
import com.example.url_blocker.ui.theme.UrlblockerTheme
import com.example.url_blocker.viewmodel.MainViewModel

open class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Quran Reminder: ensure the initial download + 6-hour refresh work is
        // scheduled the first time the app opens (idempotent). The widget also
        // schedules this on add, so the first verse is ready either way.
        QuranWorkScheduler.ensureScheduled(this)

        // Lock the app when it goes to background
        // Use ViewModelProvider (not @Composable viewModel()) since we're in onCreate
        val viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.lockApp()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkHasPassword()
                // Immediately re-read protection status when the user returns to
                // the app (e.g., after toggling the Accessibility Service in
                // Settings). Without this, the dashboard can keep showing a
                // stale "Protection Inactive" until the next poll tick.
                viewModel.checkAccessibilityStatus(this)
                viewModel.checkDeviceAdminStatus(this)
            }
        })

        setContent {
            UrlblockerTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        viewModel.checkAccessibilityStatus(context)
    }

    // Periodically check accessibility and device admin status.
    // Wrapped in try/catch so a single failure can never silently kill the
    // refresh loop and leave the dashboard stuck on a stale status.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                viewModel.checkAccessibilityStatus(context)
                viewModel.checkDeviceAdminStatus(context)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Status refresh failed: ${e.message}")
            }
            kotlinx.coroutines.delay(3000)
        }
    }

    // Auto-start the protection monitor service when Accessibility Service is active
    LaunchedEffect(viewModel.isAccessibilityEnabled) {
        if (viewModel.isAccessibilityEnabled && !viewModel.isMonitorServiceRunning) {
            viewModel.startMonitorService(context)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "URL Blocker",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Safe browsing protection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Lock indicator
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
                            contentDescription = if (viewModel.hasPassword) "Lock app" else "No password set",
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
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("Keywords") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.Language, contentDescription = null) },
                    label = { Text("Websites") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text("Log") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Show lock screen overlay when app is locked
            // (either with existing password or for initial password setup)
            if (viewModel.shouldShowLockScreen()) {
                LockScreen(
                    onUnlock = { password -> viewModel.verifyAppPassword(password) },
                    onSetupPassword = { password -> viewModel.setAppPassword(password) },
                    hasPassword = viewModel.hasPassword
                )
            } else {
                when (selectedTab) {
                    0 -> DashboardTab(viewModel, deviceAdminLauncher)
                    1 -> KeywordsTab(viewModel)
                    2 -> WebsitesTab(viewModel)
                    3 -> LogTab(viewModel)
                }
            }
        }
    }
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
                text = if (isSetupMode) "Set App Password" else "App Locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isSetupMode)
                    "Create a password to protect your blocked keywords and websites"
                else
                    "Enter password to view protected content",
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

// ── Dashboard Tab ─────────────────────────────────────────────────

@Composable
private fun DashboardTab(
    viewModel: MainViewModel,
    deviceAdminLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Accessibility Status Card
        item {
            val isEnabled = viewModel.isAccessibilityEnabled
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isEnabled)
                            Icons.Filled.Shield
                        else
                            Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (isEnabled)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEnabled) "Protection Active" else "Protection Inactive",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = if (isEnabled)
                                "Monitoring Chrome and Google app"
                            else
                                "Enable Accessibility Service to start blocking",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            else
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                    if (!isEnabled) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.openAccessibilitySettings(context) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Restore Protection", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Quick Status Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${viewModel.userKeywords.size}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Keywords",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${viewModel.blockedDomains.size}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Websites",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // How it works
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How it works",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Monitors Chrome and Google app 24/7",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Blocks adult content and explicit keywords",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Customizable keyword and domain lists",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Settings shortcut
        item {
            OutlinedButton(
                onClick = { viewModel.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Accessibility Settings")
            }
        }

        // ── Device Admin Status ───────────────────────────────────────
        item {
            val isAdmin = viewModel.isDeviceAdminEnabled
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAdmin)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.AdminPanelSettings,
                        contentDescription = null,
                        tint = if (isAdmin) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Device Admin",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isAdmin)
                                "Active — adds uninstall protection step"
                            else
                                "Inactive — app can be uninstalled freely",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isAdmin) {
                        TextButton(
                            onClick = {
                                val intent = android.content.Intent(
                                    android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                                ).apply {
                                    putExtra(
                                        android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                        android.content.ComponentName(
                                            context,
                                            com.example.url_blocker.receiver.DeviceAdminReceiver::class.java
                                        )
                                    )
                                    putExtra(
                                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Activating device admin adds a deactivation step before uninstall, " +
                                        "making it harder to accidentally remove the protection app. " +
                                        "The launcher icon will also be hidden for additional protection."
                                    )
                                }
                                try {
                                    deviceAdminLauncher.launch(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Failed to launch Device Admin: ${e.message}")
                                    // Fallback: just open Device Admin settings manually
                                    val fallbackIntent = android.content.Intent(
                                        android.provider.Settings.ACTION_SECURITY_SETTINGS
                                    ).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallbackIntent)
                                }
                            }
                        ) {
                            Text("Activate", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ── App Password / Lock ───────────────────────────────────────
        item {
            val hasPwd = viewModel.hasPassword
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasPwd)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (hasPwd) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                        contentDescription = null,
                        tint = if (hasPwd) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Lock",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (hasPwd)
                                "Password set — app locks on background"
                            else
                                "No password — all content visible freely",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hasPwd) {
                        TextButton(onClick = { viewModel.clearAppPassword() }) {
                            Text("Remove", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = { viewModel.appLockTriggered = true }) {
                            Text("Set", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ── Private DNS (Network-level filtering) ────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Private DNS (Network Filter)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Set a filtered DNS provider to block adult content at the network level — works on ALL apps including Chrome, incognito mode, and Google Images. Cannot be bypassed by installing a new browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.openPrivateDnsSettings(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open DNS Settings", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.openDnsSetupGuide(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("DNS Providers", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recommended: CleanBrowsing Family Filter or Cloudflare 1.1.1.3 (Family)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ── Device Owner (ADB) — truly blocks uninstall ─────────────────
        item {
            val isOwner = viewModel.isDeviceOwner
            val isAdmin = viewModel.isDeviceAdminEnabled
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOwner && isAdmin)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isOwner && isAdmin) Icons.Filled.VerifiedUser else Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            tint = if (isOwner && isAdmin)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Uninstall Protection",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isOwner && isAdmin)
                                    "✅ Uninstall completely blocked — factory reset required"
                                else
                                if (isAdmin)
                                    "Device Admin active — adds uninstall friction (1 extra step)"
                                else
                                    "No protection — app can be uninstalled freely",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (!isOwner && isAdmin) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Upgrade to Device Owner for full protection:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Connect your phone to a computer with ADB\n" +
                                  "2. Temporarily remove your Google account (Settings > Accounts)\n" +
                                  "3. Run this command in terminal:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = viewModel.getDeviceOwnerAdbCommand(),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "4. Re-add your Google account\n" +
                                  "5. Re-open the app — uninstall will be blocked permanently",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Protection Monitor Status ─────────────────────────────────
        item {
            val isMonitoring = viewModel.isMonitorServiceRunning
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isMonitoring)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = if (isMonitoring) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Protection Monitor",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isMonitoring)
                                "Running — will prompt to re-enable after 5 min"
                            else
                                "Not running — automatic re-enable prompting disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isMonitoring) {
                        TextButton(
                            onClick = { viewModel.startMonitorService(context) }
                        ) {
                            Text("Start", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ── Keywords Tab ──────────────────────────────────────────────────

@Composable
private fun KeywordsTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    var filterText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    val visibleKeywords = viewModel.userKeywords.filter {
        filterText.isBlank() || it.contains(filterText.trim(), ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Add keyword input
        OutlinedTextField(
            value = viewModel.newKeywordText,
            onValueChange = { viewModel.updateNewKeyword(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add a keyword to block") },
            placeholder = { Text("e.g., instagram, tiktok") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { viewModel.addKeyword() }
            ),
            trailingIcon = {
                IconButton(onClick = { viewModel.addKeyword() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Built-in protection includes expanded adult-content keywords",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Strict Mode toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (viewModel.isStrictMode)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = if (viewModel.isStrictMode)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Strict Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Blocks broader terms (hot, babe, bikini, etc.). " +
                                "May cause false positives on legitimate sites like weather, fashion, or news.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = viewModel.isStrictMode,
                    onCheckedChange = { viewModel.toggleStrictMode(context) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Google App gender terms toggle: the Google app never exposes which
        // search tab is active (chips have no selected state, chip taps carry
        // no label), so Images/Videos-only blocking of gender terms is not
        // possible there. This toggle blocks them on ALL Google app tabs.
        // Chrome keeps its tab-aware behavior (All tab stays free).
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (viewModel.blockGenderTermsInGoogleApp)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Man,
                    contentDescription = null,
                    tint = if (viewModel.blockGenderTermsInGoogleApp)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Google App Gender Terms",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Blocks woman, man, girl, etc. on ALL Google app search tabs. " +
                                "The Google app can't reveal the active tab, so Images/Videos-only " +
                                "blocking isn't possible there. Chrome keeps tab-aware blocking. " +
                                "Requires Strict Mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = viewModel.blockGenderTermsInGoogleApp,
                    onCheckedChange = { viewModel.toggleBlockGenderTermsInGoogleApp() }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your custom keywords (${viewModel.userKeywords.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (viewModel.userKeywords.size > 5) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Filter custom keywords") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Keywords list
        if (visibleKeywords.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (viewModel.userKeywords.isEmpty()) {
                            "No custom keywords added yet.\nAdd keywords above to block specific terms."
                        } else {
                            "No keywords match the current filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleKeywords, key = { it }) { keyword ->
                    KeywordItem(
                        keyword = keyword,
                        onEdit = {
                            editTarget = keyword
                            editText = keyword
                        },
                        onDelete = { deleteTarget = keyword }
                    )
                }
            }
        }

        deleteTarget?.let { keyword ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Remove keyword?") },
                text = { Text("Remove \"$keyword\"? It will no longer be blocked.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeKeyword(keyword)
                        deleteTarget = null
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            )
        }

        editTarget?.let { keyword ->
            AlertDialog(
                onDismissRequest = { editTarget = null },
                title = { Text("Edit keyword") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        label = { Text("Keyword") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (editText.trim().isNotEmpty()) {
                            viewModel.editKeyword(keyword, editText)
                            editTarget = null
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editTarget = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun KeywordItem(keyword: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = keyword,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit $keyword")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete $keyword",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Websites Tab ──────────────────────────────────────────────────

@Composable
private fun WebsitesTab(viewModel: MainViewModel) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Add domain input
        OutlinedTextField(
            value = viewModel.newDomainText,
            onValueChange = { viewModel.updateNewDomain(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add a website or domain to block") },
            placeholder = { Text("e.g., youtube.com, reddit.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { viewModel.addDomain() }
            ),
            trailingIcon = {
                IconButton(onClick = { viewModel.addDomain() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add domain")
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Blocked websites (${viewModel.blockedDomains.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Domains list
        if (viewModel.blockedDomains.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No websites blocked yet.\nAdd domain names above to block entire sites.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(viewModel.blockedDomains.toList(), key = { it }) { domain ->
                    DomainItem(
                        domain = domain,
                        onDelete = { deleteTarget = domain }
                    )
                }
            }
        }

        deleteTarget?.let { domain ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Remove website?") },
                text = { Text("Remove \"$domain\"? Its subdomains will no longer be blocked.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeDomain(domain)
                        deleteTarget = null
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun DomainItem(domain: String, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = domain,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete $domain",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Log Tab ───────────────────────────────────────────────────────

@Composable
private fun LogTab(viewModel: MainViewModel) {
    LaunchedEffect(Unit) {
        viewModel.refreshLog()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Event Log",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (viewModel.logEntries.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearLog() }) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (viewModel.logEntries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No events logged yet.\nEvents will appear here as Chrome and Google app are monitored.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(viewModel.logEntries.toList().reversed(), key = { it }) { entry ->
                    LogItem(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun LogItem(entry: String) {
    val isBlocked = entry.startsWith("BLOCKED")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isBlocked)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = entry,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isBlocked)
                MaterialTheme.colorScheme.onErrorContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
