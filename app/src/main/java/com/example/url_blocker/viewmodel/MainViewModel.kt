package com.example.url_blocker.viewmodel

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.url_blocker.repository.BlockRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    // ── UI State ───────────────────────────────────────────────────

    var isAccessibilityEnabled by mutableStateOf(false)
        private set

    var newKeywordText by mutableStateOf("")
        private set

    var newDomainText by mutableStateOf("")
        private set

    val userKeywords = mutableStateListOf<String>()
    val blockedDomains = mutableStateListOf<String>()
    val logEntries = mutableStateListOf<String>()

    // ── Repo ───────────────────────────────────────────────────────

    private var repository: BlockRepository? = null

    fun initialize(context: Context) {
        if (repository != null) return
        repository = BlockRepository(context.applicationContext)
        refreshKeywords()
        refreshDomains()
        refreshLog()
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

    private fun refreshKeywords() {
        userKeywords.clear()
        userKeywords.addAll(repository?.getUserKeywords() ?: emptySet())
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
        blockedDomains.addAll(repository?.getBlockedDomains() ?: emptySet())
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
