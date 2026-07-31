package com.example.url_blocker.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.url_blocker.ui.theme.UrlblockerTheme
import kotlinx.coroutines.delay

/**
 * Full-screen blocking overlay that shows when blocked content is detected.
 *
 * This activity:
 *   1. Immediately covers the blocked content
 *   2. Shows a blocking message
 *   3. Waits briefly for safe navigation to take effect
 *   4. Exits — to Home for content/keyword blocks, or to NORMAL Chrome for
 *      incognito blocks (the service's adaptive Back sequence has already
 *      closed every incognito tab, so the user lands back in normal Chrome
 *      automatically)
 *
 * The user cannot dismiss this overlay to reveal the blocked content
 * underneath. It auto-navigates to its destination.
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BlockOverlayActivity"
        private const val EXIT_DELAY_MS = 1500L
    }

    /** True once the destination navigation has run (auto-exit timer, button
     *  and onBackPressed can all fire; only the first may navigate). */
    private var exited = false

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
                    exitLabel = "Return Home",
                    statusText = "Returning to Home screen...",
                    onDismiss = { exitToDestination() }
                )
            }
        }

        // Auto-exit to the destination after a brief delay
        Handler(Looper.getMainLooper()).postDelayed({
            exitToDestination()
        }, EXIT_DELAY_MS)
    }

    /**
     * Always exits to Home. Idempotent: the auto-exit timer, the button and
     * onBackPressed can all call this; only the first call may navigate.
     */
    private fun exitToDestination() {
        if (exited) return
        exited = true
        exitToHome()
    }

    private fun exitToHome() {
        Log.i(TAG, "Exiting to Home")
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exit to Home: ${e.message}")
        }
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Block back button - user cannot go back to the blocked content.
        // Navigate to the destination (normal Chrome for incognito, Home otherwise).
        exitToDestination()
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
    exitLabel: String,
    statusText: String,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

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
            contentAlignment = Alignment.Center
        ) {
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
                    text = statusText,
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
                    Text(exitLabel)
                }
            }
        }
    }
}
