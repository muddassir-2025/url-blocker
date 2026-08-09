package com.muddassir.clearview.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import com.muddassir.clearview.viewmodel.MainViewModel

/**
 * Block tab — the password-protected security dashboard.
 *
 * Cards (top to bottom):
 *  1. Protection toggle — reflects the Accessibility Service state, turns
 *     green when active, and opens the system settings to enable/disable it.
 *  2. Strict Mode — adds the curated risky-but-innocent discovery terms
 *     (bikini, lingerie, cleavage, ...) on top of the always-on adult terms.
 *  3. Block Shorts — blocks YouTube Shorts in Chrome and the YouTube app
 *     (no need to add "shorts" as a keyword).
 *  4. Blocked Items — dotted editable list card: add / view keywords and
 *     websites.
 *  5. DNS protection — network-level filtering.
 *  6. Advanced — device admin, uninstall protection, app lock.
 */
@Composable
fun BlockTab(
    viewModel: MainViewModel,
    deviceAdminLauncher: ActivityResultLauncher<Intent>
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ProtectionCard(viewModel, context) }
        item { StrictModeCard(viewModel, context) }
        item { BlockShortsCard(viewModel) }
        item { BlockedItemsCard(viewModel) }
        item { DnsCard(viewModel, context) }

        item {
            Text(
                text = "ADVANCED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
        }
        item { DeviceAdminCard(viewModel, context, deviceAdminLauncher) }
        item { UninstallProtectionCard(viewModel, context) }
        item { AppLockCard(viewModel) }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

// ── 1. Protection toggle ──────────────────────────────────────────

@Composable
private fun ProtectionCard(viewModel: MainViewModel, context: Context) {
    val isEnabled = viewModel.isAccessibilityEnabled
    val activeGreen = Color(0xFF2E7D32)
    // Prominent disclosure + consent for the Accessibility Service (Google Play
    // policy for non-accessibility-tool apps): shown whenever the user attempts
    // to ENABLE protection. The service is described, its on-device nature is
    // stated, and consent is explicit — system Settings is only opened after
    // the user taps Continue. Disabling stays one tap away as before.
    var showDisclosure by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) activeGreen else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isEnabled) "Active — monitoring Chrome & Google" else "Off — tap to enable",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { _ ->
                        if (!isEnabled) {
                            // Enabling: require explicit consent via the
                            // prominent-disclosure dialog before the user is
                            // sent to system Settings to turn the service on.
                            showDisclosure = true
                        } else {
                            // Disabling: no disclosure needed — straight to
                            // settings. The service can't be toggled
                            // programmatically.
                            viewModel.openAccessibilitySettings(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF1B5E20),
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Blocks incognito mode · Blocks explicit websites and searches · Works on YouTube search · Blocks your custom keywords",
                style = MaterialTheme.typography.bodySmall,
                color = if (isEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Enable ClearView protection?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ClearView uses the Accessibility service to read the text currently shown on your screen inside Chrome and the Google app — only to block adult content, your blocked keywords and websites, and incognito mode.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Screen text is processed instantly on your device\n" +
                            "• It is never stored, logged, or sent anywhere\n" +
                            "• No personal data is collected\n" +
                            "• You can turn it off anytime in Settings → Accessibility",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        viewModel.openAccessibilitySettings(context)
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text("Not now")
                }
            }
        )
    }
}

// ── 2. Strict Mode ────────────────────────────────────────────────

@Composable
private fun StrictModeCard(viewModel: MainViewModel, context: Context) {
    val active = viewModel.isStrictMode
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Strict Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Adds the curated risky-but-innocent discovery terms (bikini, lingerie, cleavage, ...) on top of the always-on adult filter — in every monitored app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = active,
                onCheckedChange = { viewModel.toggleStrictMode(context) }
            )
        }
    }
}

// ── 3. Block Shorts ───────────────────────────────────────────────

@Composable
private fun BlockShortsCard(viewModel: MainViewModel) {
    val active = viewModel.blockShorts
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayCircle,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Block Shorts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Blocks YouTube Shorts in Chrome and the YouTube app — short-form videos won't open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = active,
                onCheckedChange = { viewModel.toggleBlockShorts() }
            )
        }
    }
}

// ── 4. Blocked Items (dotted, editable) ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedItemsCard(viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // Hoisted out of drawBehind: MaterialTheme is a composable read and cannot
    // be accessed inside the non-composable DrawScope lambda.
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                val dash = floatArrayOf(strokeWidth * 5, strokeWidth * 5)
                drawRoundRect(
                    color = outlineColor,
                    style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(dash)),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Blocked Items",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${viewModel.userKeywords.size} keywords · ${viewModel.blockedDomains.size} websites",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Filled.Add, contentDescription = if (expanded) "Close editor" else "Add blocked items")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Add keyword
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
                        keyboardActions = KeyboardActions(onDone = { viewModel.addKeyword() }),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.addKeyword() }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (viewModel.userKeywords.isNotEmpty()) {
                        Text(
                            text = "Keywords",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (keyword in viewModel.userKeywords) {
                                BlockedChip(
                                    label = keyword,
                                    onDelete = { viewModel.removeKeyword(keyword) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Add website
                    OutlinedTextField(
                        value = viewModel.newDomainText,
                        onValueChange = { viewModel.updateNewDomain(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Add a website to block") },
                        placeholder = { Text("e.g., youtube.com, reddit.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.addDomain() }),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.addDomain() }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add website")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (viewModel.blockedDomains.isNotEmpty()) {
                        Text(
                            text = "Websites",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (domain in viewModel.blockedDomains) {
                                BlockedChip(
                                    label = domain,
                                    onDelete = { viewModel.removeDomain(domain) }
                                )
                            }
                        }
                    }

                    if (viewModel.userKeywords.isEmpty() && viewModel.blockedDomains.isEmpty()) {
                        Text(
                            text = "Nothing blocked yet. Add keywords or websites above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedChip(label: String, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove $label",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── 5. DNS protection ─────────────────────────────────────────────

@Composable
private fun DnsCard(viewModel: MainViewModel, context: Context) {
    var showSetupSheet by remember { mutableStateOf(false) }
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
                    text = "DNS Protection",
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
                    onClick = { showSetupSheet = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("DNS Setup Guide", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.openPrivateDnsSettings(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open DNS Settings", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recommended: Cloudflare 1.1.1.3 (Family) or CleanBrowsing Family Filter",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }

    if (showSetupSheet) {
        DnsSetupSheet(
            viewModel = viewModel,
            context = context,
            onDismiss = { showSetupSheet = false }
        )
    }
}

/**
 * In-app DNS setup guide: pick a filtered-DNS provider, copy its hostname,
 * then set it manually in Private DNS settings (open settings → Private DNS
 * → paste the hostname → Save). Pure copy-paste flow — no permissions needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsSetupSheet(
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    // Acquired once (not per recomposition).
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    fun copyHostname(hostname: String) {
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("DNS hostname", hostname)
        )
        Toast.makeText(context, "Copied: $hostname", Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrollable so small screens / large font scale can't clip the guide.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "DNS Setup Guide",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Step-by-step: 1 copy → 2 open → 3 paste → 4 save.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "How to set it up",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "1. Tap a provider below and copy its hostname\n" +
                            "2. Tap \"Open DNS Settings\"\n" +
                            "3. Tap Private DNS\n" +
                            "4. Paste the hostname and tap Save",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DnsProviderCard(
                name = "Cloudflare Family (1.1.1.3)",
                description = "Blocks malware + adult content. Fast, free, no account.",
                hostname = viewModel.cloudflareFamilyHostname(),
                onCopy = { copyHostname(it) }
            )

            DnsProviderCard(
                name = "CleanBrowsing Family Filter",
                description = "Blocks adult content + malware. Strict family filter (185.228.168.168 / 185.228.169.168).",
                hostname = viewModel.cleanBrowsingFamilyHostname(),
                onCopy = { copyHostname(it) }
            )

            OutlinedButton(
                onClick = {
                    onDismiss()
                    viewModel.openPrivateDnsSettings(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open DNS Settings", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DnsProviderCard(
    name: String,
    description: String,
    hostname: String,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hostname,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { onCopy(hostname) }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy $hostname",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── 6. Advanced: Device Admin ─────────────────────────────────────

@Composable
private fun DeviceAdminCard(
    viewModel: MainViewModel,
    context: Context,
    deviceAdminLauncher: ActivityResultLauncher<Intent>
) {
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
                tint = if (isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        val intent = Intent(
                            android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                        ).apply {
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                android.content.ComponentName(
                                    context,
                                    com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java
                                )
                            )
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Activating device admin adds a deactivation step before uninstall, " +
                                    "making it harder to accidentally remove the protection app."
                            )
                        }
                        try {
                            deviceAdminLauncher.launch(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("BlockTab", "Failed to launch Device Admin: ${e.message}")
                            val fallbackIntent = Intent(
                                android.provider.Settings.ACTION_SECURITY_SETTINGS
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

// ── 6. Advanced: Uninstall Protection (Device Owner) ──────────────

@Composable
private fun UninstallProtectionCard(viewModel: MainViewModel, context: Context) {
    val isOwner = viewModel.isDeviceOwner
    val isAdmin = viewModel.isDeviceAdminEnabled
    var showRemoveOwnerConfirm by remember { mutableStateOf(false) }
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
                        text = when {
                            isOwner && isAdmin -> "✅ Uninstall completely blocked — factory reset required"
                            isAdmin -> "Device Admin active — adds uninstall friction (1 extra step)"
                            else -> "No protection — app can be uninstalled freely"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isOwner && isAdmin) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Device Owner is active — the app cannot be uninstalled. " +
                        "Tap below to lift the lock (needed to update or remove the app). " +
                        "All your data is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showRemoveOwnerConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove Uninstall Protection", fontSize = 13.sp)
                }
            }

            if (showRemoveOwnerConfirm) {
                AlertDialog(
                    onDismissRequest = { showRemoveOwnerConfirm = false },
                    title = { Text("Remove Uninstall Protection?") },
                    text = {
                        Text(
                            "The app will stop being Device Owner, so you can update or " +
                                "uninstall it normally again. Your blocked lists and settings are kept."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRemoveOwnerConfirm = false
                            viewModel.removeUninstallProtection(context)
                        }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveOwnerConfirm = false }) { Text("Cancel") }
                    }
                )
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

// ── 6. Advanced: App Lock ─────────────────────────────────────────

@Composable
private fun AppLockCard(viewModel: MainViewModel) {
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
                tint = if (hasPwd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Block Tab Password",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (hasPwd)
                        "Password set — Block tab locks when the app goes to background"
                    else
                        "No password — Block tab is open to anyone",
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
