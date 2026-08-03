package com.example.url_blocker.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Man
import androidx.compose.material.icons.outlined.OpenInNew
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
import com.example.url_blocker.viewmodel.MainViewModel

/**
 * Block tab — the password-protected security dashboard.
 *
 * Cards (top to bottom):
 *  1. Protection toggle — reflects the Accessibility Service state, turns
 *     green when active, and opens the system settings to enable/disable it.
 *  2. Chrome Strict Mode — blocks generic terms (women, man, ...) on Google
 *     image/search tabs inside Chrome.
 *  3. Google Strict Mode — blocks those same terms on Google search (the
 *     Google app can't reveal its active tab, so image-tab-only blocking is
 *     not possible there).
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
        item { ChromeStrictCard(viewModel, context) }
        item { GoogleStrictCard(viewModel) }
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
                    onCheckedChange = { turnOn ->
                        // The Accessibility Service can't be toggled programmatically;
                        // route the user to the system settings either way.
                        viewModel.openAccessibilitySettings(context)
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
}

// ── 2. Chrome Strict Mode ─────────────────────────────────────────

@Composable
private fun ChromeStrictCard(viewModel: MainViewModel, context: Context) {
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
                    text = "Chrome Strict Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Blocks generic terms like women, man on Google image and search tabs inside Chrome.",
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

// ── 3. Google Strict Mode ─────────────────────────────────────────

@Composable
private fun GoogleStrictCard(viewModel: MainViewModel) {
    val active = viewModel.blockGenderTermsInGoogleApp
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
                imageVector = Icons.Outlined.Man,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Google Strict Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Blocks terms like women, man on Google search. Image-tab-only blocking isn't possible in the Google app, so this mode handles search protection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = active,
                onCheckedChange = { viewModel.toggleBlockGenderTermsInGoogleApp() }
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
                    onClick = { viewModel.openPrivateDnsSettings(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open DNS Settings", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { viewModel.openDnsSetupGuide(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                    com.example.url_blocker.receiver.DeviceAdminReceiver::class.java
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
