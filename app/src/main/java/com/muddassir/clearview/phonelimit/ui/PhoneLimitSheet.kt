package com.muddassir.clearview.phonelimit.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.muddassir.clearview.R
import com.muddassir.clearview.phonelimit.PhoneLimitCoordinator
import com.muddassir.clearview.phonelimit.PhoneLimitService
import com.muddassir.clearview.receiver.DeviceAdminReceiver
import kotlinx.coroutines.delay

/**
 * Phone Limit bottom sheet — opened from Quran tab → ⋮ menu → Set Phone
 * Limit (via the settings sheet's card).
 *
 * Lifecycle: IDLE → SETTING (this sheet) → ACTIVE (countdown shown here and
 * in the notification/widget) → EXPIRED → DEVICE LOCKED (when the app is a
 * Device Admin; the sheet itself explains the requirement and offers to
 * enable admin).
 *
 * There is no cancel/stop control: once started the limit runs until the
 * timer expires (the phone locks via DevicePolicyManager.lockNow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLimitSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Ticks every second so the countdown below (read from the store) stays
    // live while the sheet is open, and flips back to the setup view the
    // moment the timer expires.
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            tick = System.currentTimeMillis()
        }
    }
    val remaining = remember(tick) { PhoneLimitCoordinator.remainingMillis(context) }
    val active = remaining > 0L

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(
                    if (active) R.string.phone_limit_active else R.string.phone_limit_setup_title
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (active) {
                ActiveLimitView(remainingMillis = remaining)
            } else {
                SetupLimitView(onStarted = { tick = System.currentTimeMillis() })
            }
        }
    }
}

/** ACTIVE state: the live countdown, with no cancel control. */
@Composable
private fun ActiveLimitView(remainingMillis: Long) {
    Text(
        text = PhoneLimitCoordinator.format(remainingMillis),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = stringResource(R.string.phone_limit_active_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (PhoneLimitCoordinator.lockCapability(LocalContext.current) !=
        PhoneLimitCoordinator.LockCapability.READY
    ) {
        Spacer(Modifier.height(4.dp))
        AdminRequiredNote()
    }
}

/** SETTING state: hours / minutes / seconds, warning, start. */
@Composable
private fun SetupLimitView(onStarted: () -> Unit) {
    val context = LocalContext.current
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val zeroError = stringResource(R.string.phone_limit_zero)
    // Today's real screen time (0 when Usage Access isn't granted — the
    // format hint shows instead). Read once; it can't change while the sheet
    // is open.
    val hasUsageAccess = PhoneLimitCoordinator.hasUsageAccess(context)
    val screenTimeToday = remember { PhoneLimitCoordinator.screenTimeToday(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun start() {
        val total = (hours.toLongOrNull() ?: 0L) * 3_600_000L +
            (minutes.toLongOrNull() ?: 0L) * 60_000L +
            (seconds.toLongOrNull() ?: 0L) * 1_000L
        if (total <= 0L) {
            error = zeroError
            return
        }
        error = null
        // Android 13+: ask once for the notification permission so the
        // countdown is visible in the shade. The limit still runs when denied
        // (the widget keeps showing it); only the shade display is lost.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PhoneLimitService.start(context, total)
        onStarted()
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = hours,
            onValueChange = { hours = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.phone_limit_hours)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.phone_limit_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = seconds,
            onValueChange = { seconds = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.phone_limit_seconds)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
    if (hasUsageAccess) {
        // Real screen time today (e.g. "3h 55m") — the at-a-glance stat the
        // user wants when deciding how long to set the limit.
        Text(
            text = stringResource(
                R.string.phone_limit_screen_today,
                PhoneLimitCoordinator.formatScreenTime(screenTimeToday)
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    } else {
        // No Usage Access yet: the one-tap system prompt — the only extra
        // setting this card asks for beyond the alarm/notification notes.
        Spacer(Modifier.height(8.dp))
        PermissionNote(
            text = stringResource(R.string.phone_limit_usage_access_note),
            actionLabel = stringResource(R.string.phone_limit_usage_access_allow),
            onAction = { openUsageAccessSettings(context) }
        )
    }

    if (error != null) {
        Text(
            text = error.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    // Explicit warning before the user commits — the spec's required copy.
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = stringResource(R.string.phone_limit_warning),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }

    if (PhoneLimitCoordinator.lockCapability(context) !=
        PhoneLimitCoordinator.LockCapability.READY
    ) {
        AdminRequiredNote()
    }

    // Exact alarms (Android 12+): the expiry alarm falls back to inexact and
    // can fire minutes late when the user hasn't allowed them. Offer the
    // one-tap system link, exactly like the Todo editor does.
    if (!PhoneLimitCoordinator.hasExactAlarmPermission(context)) {
        Spacer(Modifier.height(8.dp))
        PermissionNote(
            text = stringResource(R.string.phone_limit_exact_alarm_note),
            actionLabel = stringResource(R.string.phone_limit_exact_alarm_allow),
            onAction = { openExactAlarmSettings(context) }
        )
    }

    // Android 13+: without POST_NOTIFICATIONS the countdown notification (the
    // closest thing to a status-bar timer) is invisible. Ask for it here so
    // the user knows the timer is actually running.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !NotificationManagerCompat.from(context).areNotificationsEnabled()
    ) {
        Spacer(Modifier.height(8.dp))
        PermissionNote(
            text = stringResource(R.string.phone_limit_notification_note),
            actionLabel = stringResource(R.string.phone_limit_notification_allow),
            onAction = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        )
    }

    Button(
        onClick = ::start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.phone_limit_start_button))
    }
}

/** Compact permission-guidance row (exact alarms / notifications). */
@Composable
private fun PermissionNote(
    text: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/** Opens the system page where the user can allow exact alarms (Android 12+). */
private fun openExactAlarmSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }
}

/** Opens the system page where the user can grant Usage Access (screen time). */
private fun openUsageAccessSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

/**
 * Explains the exact device-lock prerequisite and offers the right one-tap
 * action for each state:
 *  - NOT_ADMIN: activate Device Admin (opens the system activation screen).
 *  - POLICY_PENDING: the admin is active but the force-lock policy added by
 *    this update hasn't been accepted yet (Android keeps the old policies
 *    until the user confirms) — opens the Device admin settings list.
 * Renders nothing when locking is fully ready. Self-contained so both the
 * setup and active views can show it; re-checks when the user returns.
 */
@Composable
private fun AdminRequiredNote() {
    val context = LocalContext.current
    // Hoisted so the click lambda below never reads resources via
    // LocalContext (lint: LocalContextGetResourceValueCall).
    val adminExplanation = stringResource(R.string.phone_limit_admin_explanation)
    var capability by remember {
        mutableStateOf(PhoneLimitCoordinator.lockCapability(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        capability = PhoneLimitCoordinator.lockCapability(context)
    }
    if (capability == PhoneLimitCoordinator.LockCapability.READY) return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (capability) {
                PhoneLimitCoordinator.LockCapability.NOT_ADMIN -> {
                    Text(
                        text = stringResource(R.string.phone_limit_requires_admin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(
                                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    ComponentName(context, DeviceAdminReceiver::class.java)
                                )
                                putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    adminExplanation
                                )
                            }
                            runCatching { launcher.launch(intent) }
                        }
                    ) {
                        Text(stringResource(R.string.phone_limit_enable_admin))
                    }
                }

                PhoneLimitCoordinator.LockCapability.POLICY_PENDING -> {
                    Text(
                        text = stringResource(R.string.phone_limit_admin_policy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                // Raw action: Settings.ACTION_APPLICATION_DEVICE_ADMIN_SETTINGS
                                // is no longer exposed by the SDK 37 stubs.
                                context.startActivity(
                                    Intent("android.settings.APPLICATION_DEVICE_ADMIN_SETTINGS")
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.phone_limit_admin_policy_action))
                    }
                }

                PhoneLimitCoordinator.LockCapability.READY -> Unit
            }
        }
    }
}
