package com.example.url_blocker.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Device Admin Receiver — makes the app appear in Settings > Security > Device Admin.
 *
 * When the app is also set as a Device Owner via ADB:
 *   (adb shell dpm set-device-owner ...)
 * then setUninstallBlocked() truly prevents uninstall — the button is grayed out.
 *
 * Without Device Owner, Device Admin only adds friction:
 * the user must deactivate admin before uninstall, providing a confirmation step.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — uninstall now requires admin deactivation first")

        // If we are also a Device Owner, truly block uninstall
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            blockUninstallIfOwner(context)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled — app can now be uninstalled")

        // Re-allow uninstall if we had blocked it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            unblockUninstallIfOwner(context)
        }
    }

    /**
     * If the app is a Device Owner, truly block uninstallation.
     * This requires ADB setup: adb shell dpm set-device-owner com.example.url_blocker/.DeviceAdminReceiver
     */
    private fun blockUninstallIfOwner(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val component = ComponentName(context, DeviceAdminReceiver::class.java)
                dpm.setUninstallBlocked(component, context.packageName, true)
                Log.i(TAG, "SET_UNINSTALL_BLOCKED=true — app cannot be uninstalled")
            } else {
                Log.d(TAG, "Not Device Owner — uninstall can only be delayed, not blocked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block uninstall: ${e.message}")
        }
    }

    /**
     * Re-allow uninstallation when Device Admin is deactivated.
     */
    private fun unblockUninstallIfOwner(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val component = ComponentName(context, DeviceAdminReceiver::class.java)
                dpm.setUninstallBlocked(component, context.packageName, false)
                Log.i(TAG, "SET_UNINSTALL_BLOCKED=false — uninstall re-allowed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unblock uninstall: ${e.message}")
        }
    }
}
