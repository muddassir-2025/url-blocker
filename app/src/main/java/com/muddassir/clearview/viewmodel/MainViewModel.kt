package com.muddassir.clearview.viewmodel

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
import com.muddassir.clearview.repository.BlockRepository
import com.muddassir.clearview.youtubetest.YoutubeTestKeywordRepository

class MainViewModel : ViewModel() {

    // ── UI State ───────────────────────────────────────────────────

    var isAccessibilityEnabled by mutableStateOf(false)
        private set

    var isDeviceAdminEnabled by mutableStateOf(false)
        private set

    var newKeywordText by mutableStateOf("")
        private set

    var newDomainText by mutableStateOf("")
        private set

    val userKeywords = mutableStateListOf<String>()
    val blockedDomains = mutableStateListOf<String>()

    // ── Repo ───────────────────────────────────────────────────────

    private var repository: BlockRepository? = null
    private var youtubeTestKeywordRepository: YoutubeTestKeywordRepository? = null

    fun initialize(context: Context) {
        if (repository != null) return
        repository = BlockRepository(context.applicationContext)
        youtubeTestKeywordRepository = YoutubeTestKeywordRepository(context.applicationContext)
        refreshKeywords()
        refreshDomains()
        checkHasPassword()
        refreshStrictMode()
        refreshBlockShorts()
        refreshYouTubeChromeTest()
        refreshYoutubeTestKeywords()
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

    // ── Block Shorts (YouTube Shorts) ────────────────────────────────

    var blockShorts by mutableStateOf(false)
        private set

    fun toggleBlockShorts() {
        val newValue = !blockShorts
        repository?.blockShorts = newValue
        blockShorts = newValue
        android.util.Log.i("MainViewModel", "Block Shorts ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshBlockShorts() {
        blockShorts = repository?.blockShorts ?: false
    }

    // ── YouTube Chrome Test (Stage 1 feasibility experiment) ────────

    var youTubeChromeTest by mutableStateOf(false)
        private set

    fun toggleYouTubeChromeTest() {
        val newValue = !youTubeChromeTest
        repository?.youTubeChromeTest = newValue
        youTubeChromeTest = newValue
        android.util.Log.i("MainViewModel", "YouTube Chrome Test ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshYouTubeChromeTest() {
        youTubeChromeTest = repository?.youTubeChromeTest ?: false
    }

    // ── YouTube Chrome Test Keywords (separate test-only list) ──────

    var newYoutubeTestKeywordText by mutableStateOf("")
        private set

    val youtubeTestKeywords = mutableStateListOf<String>()

    fun updateNewYoutubeTestKeyword(text: String) {
        newYoutubeTestKeywordText = text
    }

    fun addYoutubeTestKeyword() {
        val keyword = newYoutubeTestKeywordText.trim()
        if (keyword.isEmpty()) return
        youtubeTestKeywordRepository?.addKeyword(keyword)
        newYoutubeTestKeywordText = ""
        refreshYoutubeTestKeywords()
    }

    fun removeYoutubeTestKeyword(keyword: String) {
        youtubeTestKeywordRepository?.removeKeyword(keyword)
        refreshYoutubeTestKeywords()
    }

    fun clearYoutubeTestKeywords() {
        youtubeTestKeywordRepository?.clearKeywords()
        refreshYoutubeTestKeywords()
    }

    private fun refreshYoutubeTestKeywords() {
        youtubeTestKeywords.clear()
        youtubeTestKeywords.addAll((youtubeTestKeywordRepository?.getKeywords() ?: emptySet()).sorted())
    }

    // ── Private DNS (network-level filtering) ───────────────────────

    /**
     * Cloudflare Family DNS hostname (1.1.1.3) — blocks malware AND adult
     * content at the network level. Using the DoT hostname form so it works
     * as an Android Private DNS provider on all networks.
     */
    fun cloudflareFamilyHostname(): String = "family.cloudflare-dns.com"

    /**
     * CleanBrowsing Family Filter hostname — blocks adult content + malware
     * at the network level (185.228.168.168 / 185.228.169.168). The DoT
     * hostname form works as an Android Private DNS provider.
     */
    fun cleanBrowsingFamilyHostname(): String = "family-filter-dns.cleanbrowsing.org"

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

    // ── Device Admin (uninstall friction) / Device Owner (true uninstall block) ──

    var isDeviceOwner by mutableStateOf(false)
        private set

    var isUninstallBlocked by mutableStateOf(false)
        private set

    fun checkDeviceAdminStatus(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java)
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
                    val component = ComponentName(context, com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java)
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
        return "adb shell dpm set-device-owner com.muddassir.clearview/.receiver.DeviceAdminReceiver"
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
                com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java
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
            val componentName = ComponentName(context, com.muddassir.clearview.LauncherActivity::class.java)
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

    // ── Helpers ────────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // The settings value stores the FULLY-QUALIFIED component name
        // ("pkg/com.pkg.Cls"), not the shorthand form ("pkg/.Cls") — comparing
        // against the shorthand always failed, so the toggle showed OFF even
        // when the service was enabled in Settings. flattenToString() produces
        // the exact form the system stores.
        val serviceName = ComponentName(
            context,
            com.muddassir.clearview.service.UrlBlockerService::class.java
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }
}
