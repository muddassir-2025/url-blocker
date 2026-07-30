package com.example.url_blocker.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver — makes the app appear in Settings > Security > Device Admin.
 *
 * This does NOT prevent uninstallation, but adds a friction step:
 * the user must first deactivate the device admin before they can uninstall the app.
 * This provides a warning/confirmation step that can deter accidental uninstallation.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — uninstall now requires admin deactivation first")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled — app can now be uninstalled")
    }
}
