package com.muddassir.clearview.quran.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Subtle haptic feedback for the Dhikr Counter. Respects the user's
 * vibration toggle — every function is a no-op when [enabled] is false (or
 * the device has no vibrator). Kept deliberately short so counting feels
 * instant, never buzzy.
 */
object DhikrVibrator {

    /** A single very short tick (≈25 ms) on every count. */
    fun tick(context: Context, enabled: Boolean) {
        if (!enabled) return
        vibrate(context, 25)
    }

    /** A gentle triple pulse when the target is reached. */
    fun celebrate(context: Context, enabled: Boolean) {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrate(
                context,
                VibrationEffect.createWaveform(
                    longArrayOf(0, 35, 45, 35, 45, 60),
                    -1 // play once, not repeating
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrateLegacy(context, 140)
        }
    }

    private fun vibrate(context: Context, durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrate(
                context,
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrateLegacy(context, durationMs)
        }
    }

    private fun vibrate(context: Context, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (vibrator.hasVibrator()) vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrateLegacy(context, 25)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateLegacy(context: Context, durationMs: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (vibrator.hasVibrator()) vibrator.vibrate(durationMs)
    }
}
