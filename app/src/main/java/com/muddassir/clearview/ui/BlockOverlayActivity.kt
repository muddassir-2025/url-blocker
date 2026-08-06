package com.muddassir.clearview.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.ui.theme.UrlblockerTheme

/**
 * Full-screen blocking overlay that shows when blocked content is detected.
 *
 * This activity:
 *   1. Immediately covers the blocked content
 *   2. Shows a blocking message
 *   3. STAYS on screen until the user dismisses it manually — the ✕ button
 *      (top-right) or the "Return Home" button both navigate to Home. There
 *      is NO auto-exit: the overlay must not flash and vanish on its own,
 *      and the user decides when to leave.
 *
 * The user cannot dismiss this overlay to reveal the blocked content
 * underneath — dismissal always goes Home.
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BlockOverlayActivity"
    }

    /** True once the destination navigation has run (button, ✕ and
     *  onBackPressed can all fire; only the first may navigate). */
    private var exited = false

    /**
     * Stray-input guard. The blocking sequence (closing tabs, sending back
     * presses) is still settling for a couple of seconds after the overlay
     * appears, and a stray accessibility click/back can land on this screen in
     * that window — observed as the overlay dismissing itself ~1.3s after
     * showing with no user input at all. Dismissals inside the grace window are
     * ignored; a real user can't read and reach a control that fast (the
     * fade-in alone is 150ms).
     */
    private val createdAt = SystemClock.elapsedRealtime()
    private val dismissalGraceMs = 2_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make this activity full-screen and impossible to bypass
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        addWindowFlags()

        val blockedItem = intent.getStringExtra("blocked_item") ?: "content"
        val blockedType = intent.getStringExtra("blocked_type") ?: "MATCHED"

        // NOTE: incognito never reaches this overlay anymore — the service
        // closes the incognito tabs and lands the user on Home directly (simple,
        // clean behavior). This overlay is only for keyword/domain blocks and
        // always exits to Home.
        Log.i(TAG, "Block overlay shown: $blockedItem ($blockedType)")

        setContent {
            UrlblockerTheme(darkTheme = true) {
                BlockOverlayScreen(
                    blockedItem = blockedItem,
                    blockedType = blockedType,
                    onDismiss = { exitToDestination() }
                )
            }
        }
    }

    /**
     * Always exits to Home. Idempotent: the button, the ✕ and onBackPressed
     * can all call this; only the first call may navigate.
     */
    private fun exitToDestination() {
        if (exited) return
        // Ignore dismissals inside the grace window (see [createdAt]) — a stray
        // input from the still-settling blocking sequence must not close the
        // overlay before the user has even seen it.
        if (SystemClock.elapsedRealtime() - createdAt < dismissalGraceMs) {
            Log.d(TAG, "Dismiss ignored (grace window)")
            return
        }
        exited = true
        exitToHome()
    }

    private fun exitToHome() {
        // Never navigate from an already-finishing/destroyed activity (a second
        // rapid tap, or a config-change recreate mid-exit).
        if (isFinishing || isDestroyed) {
            Log.d(TAG, "Exit skipped (finishing/destroyed)")
            return
        }
        Log.i(TAG, "Exiting to Home")
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            // Only launch Home if a home activity actually resolves (launcher
            // disabled/updating, kiosk mode…). If none, fall straight through
            // to the graceful fallback below instead of relying on the catch.
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                // No enter animation for the launcher: prevents the "floating
                // app icon" fly animation some launchers play when Home appears.
                overridePendingTransition(0, 0)
            } else {
                Log.w(TAG, "No home activity resolved; dismissing overlay only")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exit to Home: ${e.message}")
        }
        // Graceful fallback: whatever happened above, always finish so the user
        // is never stuck on a dead overlay. Wrapped so a window/teardown quirk
        // (e.g. an OEM launcher) can never take the process down.
        try {
            finish()
            // No exit animation for the overlay either — the theme's alpha-only
            // windowAnimationStyle is a second line of defense (see
            // Theme.Urlblocker.Overlay); this call kills the animation for the
            // finish() above in one go, so the transition back to the launcher
            // is a clean, immediate hand-off with no icon animation.
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Finish/transition failed: ${e.message}")
        }
    }

    @Deprecated("Suppress deprecated flag usage", ReplaceWith(""))
    @Suppress("DEPRECATION")
    private fun addWindowFlags() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }
}

@Composable
private fun BlockOverlayScreen(
    blockedItem: String,
    blockedType: String,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    // Intercept the system back — BOTH the button and the predictive-back
    // gesture (on targetSdk 37 the gesture never calls onBackPressed; without
    // OnBackPressedDispatcher handling, a swipe-back would finish this overlay
    // and reveal the blocked content underneath). Back always dismisses to
    // Home via onDismiss — never to the blocked content.
    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            // Center the message block vertically; the ✕ overrides its own
            // alignment below.
            contentAlignment = Alignment.Center
        ) {
            // ✕ close button — top-right, always visible. The overlay stays on
            // screen until the user taps it (or "Return Home").
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🛡️",
                    fontSize = 64.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Content Blocked",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when (blockedType) {
                        "DOMAIN" -> "This website has been blocked"
                        "INCOGNITO" -> "Incognito browsing is blocked"
                        else -> "This content contains blocked keywords"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = blockedItem,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Tap ✕ to dismiss when ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Return Home")
                }
            }
        }
    }
}
