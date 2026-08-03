package com.example.url_blocker.viewmodel

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.url_blocker.repository.BlockRepository
import com.example.url_blocker.repository.ChannelBlocklist
import com.example.url_blocker.service.ProtectionMonitorService

class MainViewModel : ViewModel() {

    // ── UI State ───────────────────────────────────────────────────

    var isAccessibilityEnabled by mutableStateOf(false)
        private set

    /**
     * "Display over other apps" (SYSTEM_ALERT_WINDOW) permission — needed to
     * draw the floating "Blocked" cards over YouTube feed videos in Chrome.
     * This is a SPECIAL permission: Android never shows a dialog for it, so
     * the app must send the user to the Settings screen to grant it.
     */
    var isOverlayPermissionEnabled by mutableStateOf(false)
        private set

    var isDeviceAdminEnabled by mutableStateOf(false)
        private set

    var isMonitorServiceRunning by mutableStateOf(false)
        private set

    var newKeywordText by mutableStateOf("")
        private set

    var newDomainText by mutableStateOf("")
        private set

    var newChannelText by mutableStateOf("")
        private set

    val userKeywords = mutableStateListOf<String>()
    val blockedDomains = mutableStateListOf<String>()
    val blockedChannels = mutableStateListOf<String>()
    val logEntries = mutableStateListOf<String>()

    // ── Repo ───────────────────────────────────────────────────────

    private var repository: BlockRepository? = null
    private var channelBlocklist: ChannelBlocklist? = null

    fun initialize(context: Context) {
        if (repository != null) return
        repository = BlockRepository(context.applicationContext)
        // The accessibility service holds its own ChannelBlocklist instance —
        // both share the same SharedPreferences, so a channel added here is
        // visible to the service immediately (it reads the blocklist on every
        // match check) and vice-versa. NOTE: each save() writes the whole
        // blocked-set snapshot (last-writer-wins), so a manual add/remove
        // racing a service-side strike record could theoretically lose one —
        // acceptable in practice (UI and service rarely mutate simultaneously).
        channelBlocklist = ChannelBlocklist(context.applicationContext)
        refreshKeywords()
        refreshDomains()
        refreshChannels()
        refreshLog()
        checkHasPassword()
        refreshStrictMode()
        refreshBlockGenderTermsInGoogleApp()
        ensureLauncherEnabled(context)   // cleanup stale disabled state first
        checkDeviceAdminStatus(context)  // then apply correct hide/show based on admin status
        // Auto-lock if password is set (app was restarted)
        if (hasPassword) {
            isAppLocked = true
        }
    }

    // ── App Password / Lock ────────────────────────────────────────

    var isAppLocked by mutableStateOf(false)
        private set

    var hasPassword by mutableStateOf(false)
        private set

    /** When set to true, triggers the lock screen in setup mode (no password yet). */
    var appLockTriggered by mutableStateOf(false)

    /** Check whether a password is configured. */
    fun checkHasPassword() {
        val has = repository?.hasPassword() ?: false
        hasPassword = has
        // If no password is set, unlock; otherwise keep current lock state
        if (!has) {
            appLockTriggered = false
        }
    }

    /** Set a new password. */
    fun setAppPassword(password: String) {
        repository?.setPassword(password)
        hasPassword = true
        isAppLocked = true
        appLockTriggered = false
    }

    /** Verify password attempt. Returns true if correct, unlocks if so. */
    fun verifyAppPassword(password: String): Boolean {
        val correct = repository?.verifyPassword(password) ?: false
        if (correct) {
            isAppLocked = false
            appLockTriggered = false
        }
        return correct
    }

    /** Lock the app (hides protected content). */
    fun lockApp() {
        if (hasPassword) {
            isAppLocked = true
        }
        // NOTE: When no password is set, backgrounding the app must NOT trigger
        // the password-setup screen. Otherwise every trip to another app/screen
        // (e.g., Accessibility Settings to toggle protection) would hijack the
        // dashboard with the "Set App Password" lock screen. Setup is triggered
        // only explicitly from the App Lock card's "Set" button.
    }

    /** Unlock the app (used after password verified). */
    fun unlockApp() {
        isAppLocked = false
        appLockTriggered = false
    }

    /** Clear the password and unlock. */
    fun clearAppPassword() {
        repository?.clearPassword()
        hasPassword = false
        isAppLocked = false
        appLockTriggered = false
    }

    /** Should the lock screen be shown (either locked with password or for setup). */
    fun shouldShowLockScreen(): Boolean {
        return isAppLocked || appLockTriggered
    }

    // ── Strict Mode (broad keyword blocking) ───────────────────────

    var isStrictMode by mutableStateOf(false)
        private set

    fun toggleStrictMode(context: Context) {
        val newValue = !isStrictMode
        repository?.isStrictMode = newValue
        isStrictMode = newValue
        android.util.Log.i("MainViewModel", "Strict Mode ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshStrictMode() {
        isStrictMode = repository?.isStrictMode ?: false
    }

    // ── Google App gender terms (all tabs) ────────────────────────

    var blockGenderTermsInGoogleApp by mutableStateOf(false)
        private set

    fun toggleBlockGenderTermsInGoogleApp() {
        val newValue = !blockGenderTermsInGoogleApp
        repository?.blockGenderTermsInGoogleApp = newValue
        blockGenderTermsInGoogleApp = newValue
        android.util.Log.i("MainViewModel", "Google App gender terms ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshBlockGenderTermsInGoogleApp() {
        blockGenderTermsInGoogleApp = repository?.blockGenderTermsInGoogleApp ?: false
    }

    // ── Private DNS (network-level filtering) ───────────────────────

    fun openPrivateDnsSettings(context: Context) {
        try {
            // Try the direct Private DNS intent first (works on most stock Android)
            val intent = Intent("android.settings.PRIVATE_DNS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e1: Exception) {
            // Fallback: open Network & internet settings (Private DNS is a sub-menu here)
            try {
                android.util.Log.w("MainViewModel", "Direct Private DNS intent failed, trying Wireless settings: ${e1.message}")
                val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                android.util.Log.e("MainViewModel", "Failed to open any network settings: ${e2.message}")
            }
        }
    }

    fun openDnsSetupGuide(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://cleanbrowsing.org/filters/")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to open DNS guide: ${e.message}")
        }
    }

    // ── Accessibility ──────────────────────────────────────────────

    fun checkAccessibilityStatus(context: Context) {
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── Display over other apps (feed-block markers) ───────────────

    fun checkOverlayPermissionStatus(context: Context) {
        isOverlayPermissionEnabled = try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open the system screen where the user grants "Display over other apps".
     * SYSTEM_ALERT_WINDOW is a special permission: it cannot be requested with
     * a runtime dialog — the user must toggle it in this Settings screen.
     */
    fun openOverlayPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to open overlay permission settings: ${e.message}")
            // Fallback: deep-link to this app's details page — the "Display over
            // other apps" toggle lives there on every Android version.
            try {
                val fallback = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (e2: Exception) {
                android.util.Log.e("MainViewModel", "Failed to open app details fallback: ${e2.message}")
            }
        }
    }

    // ── Device Admin (uninstall friction) / Device Owner (true uninstall block) ──

    var isDeviceOwner by mutableStateOf(false)
        private set

    var isUninstallBlocked by mutableStateOf(false)
        private set

    fun checkDeviceAdminStatus(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, com.example.url_blocker.receiver.DeviceAdminReceiver::class.java)
        isDeviceAdminEnabled = dpm.isAdminActive(component)
        checkDeviceOwnerStatus(context)
    }

    /**
     * Check if this app is a Device Owner (set via ADB).
     * Device Owner status allows us to truly block uninstall.
     */
    private fun checkDeviceOwnerStatus(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
                if (isDeviceOwner) {
                    val component = ComponentName(context, com.example.url_blocker.receiver.DeviceAdminReceiver::class.java)
                    isUninstallBlocked = try {
                        // setUninstallBlocked doesn't have a getter, so we infer from admin status + owner
                        dpm.isAdminActive(component) && isDeviceOwner
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    isUninstallBlocked = false
                }
            } else {
                isDeviceOwner = false
                isUninstallBlocked = false
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to check Device Owner: ${e.message}")
            isDeviceOwner = false
            isUninstallBlocked = false
        }
    }

    /**
     * Show ADB setup instructions for Device Owner.
     * Returns the ADB command to run.
     */
    fun getDeviceOwnerAdbCommand(): String {
        return "adb shell dpm set-device-owner com.example.url_blocker/.receiver.DeviceAdminReceiver"
    }

    /**
     * Lifts the Device Owner lock (set via ADB) so the app can be updated or
     * uninstalled normally again. Only works while this app IS the device
     * owner. Order matters: unblock uninstall FIRST (requires owner powers),
     * then clear the owner flag. All app data is kept — this only drops the
     * lock. The device-admin status (if any) is left intact and can be turned
     * off from Settings > Device admin apps if desired.
     */
    @Suppress("DEPRECATION")
    fun removeUninstallProtection(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                android.util.Log.i("MainViewModel", "Not device owner — nothing to remove")
                return
            }
            val component = ComponentName(
                context,
                com.example.url_blocker.receiver.DeviceAdminReceiver::class.java
            )
            // 1) Lift the hard uninstall block (set by DeviceAdminReceiver) while
            //    we still hold owner powers.
            dpm.setUninstallBlocked(component, context.packageName, false)
            // 2) Clear the Device Owner flag. This API may only be called by the
            //    device owner app itself.
            dpm.clearDeviceOwnerApp(context.packageName)
            isDeviceOwner = false
            isUninstallBlocked = false
            checkDeviceAdminStatus(context)
            android.util.Log.i("MainViewModel", "Device Owner removed — uninstall/updates allowed again")
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to remove device owner: ${e.message}")
            checkDeviceAdminStatus(context)
        }
    }

    /**
     * Safety net: re-enable LauncherActivity if it was disabled by a
     * previous version's icon-hiding code. Called once on initialize().
     */
    private fun ensureLauncherEnabled(context: Context) {
        try {
            val componentName = ComponentName(context, com.example.url_blocker.LauncherActivity::class.java)
            val currentState = context.packageManager.getComponentEnabledSetting(componentName)
            if (currentState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                currentState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                android.util.Log.i("MainViewModel", "Re-enabled LauncherActivity (safety net)")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to ensure launcher enabled: ${e.message}")
        }
    }

    // ── Protection Monitor Service ─────────────────────────────────

    fun startMonitorService(context: Context) {
        ProtectionMonitorService.start(context)
        isMonitorServiceRunning = true
    }

    fun stopMonitorService(context: Context) {
        ProtectionMonitorService.stop(context)
        isMonitorServiceRunning = false
    }

    // ── Keywords ───────────────────────────────────────────────────

    fun updateNewKeyword(text: String) {
        newKeywordText = text
    }

    fun addKeyword() {
        val keyword = newKeywordText.trim()
        if (keyword.isEmpty()) return
        repository?.addUserKeyword(keyword)
        newKeywordText = ""
        refreshKeywords()
    }

    fun removeKeyword(keyword: String) {
        repository?.removeUserKeyword(keyword)
        refreshKeywords()
    }

    fun editKeyword(oldKeyword: String, newKeyword: String) {
        repository?.replaceUserKeyword(oldKeyword, newKeyword)
        refreshKeywords()
    }

    private fun refreshKeywords() {
        userKeywords.clear()
        userKeywords.addAll((repository?.getUserKeywords() ?: emptySet()).sorted())
    }

    // ── Domains ────────────────────────────────────────────────────

    fun updateNewDomain(text: String) {
        newDomainText = text
    }

    fun addDomain() {
        val domain = newDomainText.trim()
        if (domain.isEmpty()) return
        repository?.addBlockedDomain(domain)
        newDomainText = ""
        refreshDomains()
    }

    fun removeDomain(domain: String) {
        repository?.removeBlockedDomain(domain)
        refreshDomains()
    }

    private fun refreshDomains() {
        blockedDomains.clear()
        blockedDomains.addAll((repository?.getBlockedDomains() ?: emptySet()).sorted())
    }

    // ── Channels (permanently blocked YouTube channels) ────────────
    // Channels match case-insensitively and without a leading "@" (the
    // blocklist normalizes on both write and read), so "CNN", "@cnn" and
    // " @CNN " all block the same channel.

    fun updateNewChannel(text: String) {
        newChannelText = text
    }

    fun addChannel() {
        val channel = newChannelText.trim()
        if (channel.isEmpty()) return
        channelBlocklist?.addChannel(channel, "Added manually")
        newChannelText = ""
        refreshChannels()
    }

    fun removeChannel(channel: String) {
        channelBlocklist?.removeChannel(channel)
        refreshChannels()
    }

    /**
     * Re-read blocked channels from the blocklist. Public so the UI's periodic
     * status loop can pick up channels auto-blocked by the service (which holds
     * its own ChannelBlocklist instance over the same prefs).
     */
    fun refreshChannels() {
        blockedChannels.clear()
        blockedChannels.addAll((channelBlocklist?.getBlockedChannels() ?: emptySet()).sorted())
    }

    // ── Event Log ──────────────────────────────────────────────────

    fun refreshLog() {
        logEntries.clear()
        logEntries.addAll(repository?.getLogEntries() ?: emptyList())
    }

    fun clearLog() {
        repository?.clearLog()
        logEntries.clear()
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceStr = "${context.packageName}/.service.UrlBlockerService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(serviceStr, ignoreCase = true) }
    }
}
