package com.muddassir.clearview.media.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** YouTube IFrame API player states (the `event.data` of onStateChange). */
object YtState {
    const val UNSTARTED = -1
    const val ENDED = 0
    const val PLAYING = 1
    const val PAUSED = 2
    const val BUFFERING = 3
    const val CUED = 5
}

/** YouTube IFrame API player error codes (the `event.data` of onError). */
object YtError {
    const val INVALID_PARAMETER = 2
    const val HTML5_PLAYER = 5
    const val VIDEO_NOT_FOUND = 100
    const val EMBED_NOT_ALLOWED = 101
    const val EMBED_NOT_ALLOWED_2 = 150 // same as 101 for embeds
    // 152 is not in the official docs but appears on-device when the video
    // cannot be played in the embedded player (region/copyright/availability).
    const val VIDEO_UNAVAILABLE = 152
}

/**
 * In-app YouTube player built on the OFFICIAL YouTube IFrame Player API.
 *
 * The WebView loads a local page ([youtube_player.html]) that hosts a
 * `YT.Player` and forwards its REAL events — `onReady`, `onStateChange`,
 * `onError`, `onAutoplayBlocked` — to Android through a JS bridge
 * (`AndroidBridge`). No DOM probing, no guessed state: the UI is driven by the
 * player's actual state (see [onPlayerState], [onPlayerError],
 * [onAutoplayBlocked]).
 *
 * - Media never leaves the app: the player iframe stays inside this WebView.
 * - The same WebView survives recomposition and orientation changes (the
 *   activity does not recreate on rotation), so playback continues.
 * - Switching [videoId] calls `loadVideoById` on the same player — no page
 *   reload, no playback restart beyond what YouTube itself does.
 * - Renderer-crash recovery: if the system kills the WebView renderer
 *   (observed as a blank white screen on low-memory devices), the page is
 *   reloaded and the source re-applied, so the player comes back.
 */
// The JavascriptInterface lint hit here is a false positive: YtBridge's
// methods ARE annotated with @JavascriptInterface (lint loses track of the
// concrete type through the AndroidView factory lambda).
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun YoutubePlayer(
    videoId: String? = null,
    modifier: Modifier = Modifier,
    onPlayerState: (Int) -> Unit = {},
    onPlayerError: (Int) -> Unit = {},
    onAutoplayBlocked: () -> Unit = {},
    onReady: () -> Unit = {},
    /**
     * Real playback position reports (seconds) for watch-progress tracking:
     * fired when playback starts, every 5 s while playing, and once on
     * pause/end. (duration, currentTime) — duration > 0 once the player is
     * ready.
     */
    onProgress: (currentSeconds: Double, durationSeconds: Double) -> Unit = { _, _ -> },
    /**
     * The player detected a LIVE broadcast (the IFrame API reports an infinite
     * duration). Reliable runtime live detection — independent of the feed's
     * isLive thumbnail hint, which RSS rarely provides.
     */
    onLive: () -> Unit = {},
    /** Bump to force the current source to be re-applied (retry after error). */
    retryToken: Int = 0,
    /**
     * Playback position (seconds) to resume from for the current source, or
     * 0 to start from the beginning. Applied right after the video is cued.
     */
    resumeFromSeconds: Double = 0.0,
    /** Desired playback rate (e.g. 1.25); applied once the player is ready. */
    playbackRate: Double = 1.0,
    /**
     * Bump to re-seek on the CURRENT source without reloading the video: the
     * "Continue Watching" button (seek to [seekToSeconds] = resume position)
     * and the "Watch Again" button (seek to 0).
     */
    seekToken: Int = 0,
    /** Seconds to seek to when [seekToken] changes (0 = restart). */
    seekToSeconds: Double = 0.0,
    /**
     * Transport commands for the Shorts viewer: bump [commandToken] to send
     * [command] ("play" / "pause" / "mute" / "unmute") to the page.
     */
    commandToken: Int = 0,
    command: String = "",
    /**
     * The user's DESIRED mute state. Passed to every loadVideo call so each
     * new video (e.g. when swiping through Shorts) starts with the same
     * setting the user last chose — not a fresh forced-mute.
     */
    muted: Boolean = true,
    /** The player's REAL muted state (reported by the page). */
    onMuteState: (Boolean) -> Unit = {},
    /**
     * Max-quality pin (Makkah / Madinah Live tab): requests the best
     * available playback quality ('highres') and keeps re-requesting it —
     * live embeds start on "auto", which can pick a low tier, and YouTube
     * can downshift quality on its own. Passed with every load so the page
     * arms its quality watchdog (see youtube_player.html setMaxQuality).
     */
    maxQuality: Boolean = false
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val controller = remember { PlayerController() }

    // Bridge callbacks must reflect the LATEST recomposition's lambdas.
    val currentOnState by rememberUpdatedState(onPlayerState)
    val currentOnError by rememberUpdatedState(onPlayerError)
    val currentOnAutoplayBlocked by rememberUpdatedState(onAutoplayBlocked)
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnLive by rememberUpdatedState(onLive)
    val currentOnMuteState by rememberUpdatedState(onMuteState)

    // JS bridge → main thread → Compose callbacks. The bridge methods run on
    // the WebView's JS thread; everything is marshalled to the main thread.
    val bridge = remember {
        YtBridge(
            stateCallback = { s ->
                mainHandler.post {
                    controller.lastState = s
                    // Check 2 (rendering): report the WebView's geometry at the
                    // states that matter (PLAYING / PAUSED / ENDED) so we can
                    // see if it is 0x0, GONE/INVISIBLE, or detached when the
                    // video is actually decoding. Skipped for noisy transient
                    // states (BUFFERING/UNSTARTED).
                    if (s == YtState.PLAYING || s == YtState.PAUSED || s == YtState.ENDED) {
                        controller.webView?.let { v -> logWebViewGeometry("IFRAME_STATE=$s", v) }
                    }
                    currentOnState(s)
                }
            },
            errorCallback = { c -> mainHandler.post { currentOnError(c) } },
            autoplayBlockedCallback = { mainHandler.post { currentOnAutoplayBlocked() } },
            readyCallback = { mainHandler.post { currentOnReady() } },
            progressCallback = { c, d -> mainHandler.post { currentOnProgress(c, d) } },
            liveCallback = { mainHandler.post { currentOnLive() } },
            muteStateCallback = { muted -> mainHandler.post { currentOnMuteState(muted) } }
        )
    }

    // Check 3 (rendering): forward the activity lifecycle to the WebView.
    // Compose's AndroidView does NOT call WebView.onResume()/resumeTimers()
    // automatically. A WebView that never received onResume keeps its rendering
    // surface paused: the decoder keeps producing frames (audio plays, logcat
    // shows MediaCodec output) but nothing is composited to the screen. This
    // exact symptom ("decodes but doesn't display") is the signature of a
    // never-resumed WebView in a hybrid Compose host.
    val hostActivity = LocalActivity.current as? ComponentActivity
    DisposableEffect(hostActivity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.webView?.let {
                    it.onResume()
                    it.resumeTimers()
                    // Hardware layer re-applied on EVERY resume (diagnostic
                    // directive 4) in case a lifecycle transition reset it.
                    it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    Log.i(TAG, "LIFECYCLE ON_RESUME -> webView.onResume + " +
                        "resumeTimers + setLayerType(HARDWARE) -> " +
                        layerName(it.layerType))
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Tear down a showing fullscreen custom view so the video
                    // returns to the iframe before the window fades (a custom
                    // view left across a pause is a common "blank on return"
                    // cause). Orientation is left untouched — onResume restores
                    // it through the hub's immersive logic.
                    hideFullscreenCustomView(controller, hostActivity, resetOrientation = false)
                    controller.webView?.let {
                        it.onPause()
                        Log.i(TAG, "LIFECYCLE ON_PAUSE -> webView.onPause")
                    }
                }
                else -> Unit
            }
        }
        hostActivity?.lifecycle?.addObserver(observer)
        onDispose { hostActivity?.lifecycle?.removeObserver(observer) }
    }

    // Tracks whether the WebView has been released; pending callbacks must not
    // touch a destroyed WebView.
    var released by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                controller.webView = this
                // Recomposition diagnostic (user directive 3): the AndroidView
                // factory must run EXACTLY ONCE per player instance. A second
                // FACTORY_CREATED line with a different id = the WebView is
                // being torn down and recreated (surface churn → invisible
                // video). The UPDATE line fires on every recomposition but must
                // ALWAYS carry the same id as the FACTORY_CREATED line.
                Log.d(TAG, "FACTORY_CREATED id=${System.identityHashCode(this)}")
                // Hardware layer, forced unconditionally (diagnostic directive):
                // without it the GEOM log shows layer=NONE. Re-applied on every
                // ON_RESUME below in case a lifecycle/Compose transition resets
                // it. Never LAYER_TYPE_SOFTWARE (decodes but doesn't display).
                Log.d(TAG, "LAYER_SET before=${layerName(layerType)} -> requesting HARDWARE")
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                Log.d(TAG, "LAYER_SET after=${layerName(layerType)}")
                // A freshly created Compose WebView has no lifecycle wiring
                // yet — kick the compositor on immediately so the player page
                // loads into an already-running surface.
                onResume()
                resumeTimers()

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                // ROTATION FIX (root cause): with useWideViewPort(true) the
                // layout viewport is pinned to the pre-rotation size when the
                // activity handles configChanges, so after rotating to
                // landscape the page still reports the PORTRAIT CSS viewport
                // (window.innerWidth frozen at 432 while the WebView is
                // physically 2400x870 — exactly what the SIZES/GEOM logs
                // showed). With wide-viewport mode disabled the layout
                // viewport ALWAYS equals the view's CSS size and tracks every
                // view resize, so the page's own resize event fires with the
                // correct dimensions after rotation.
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                // DIAGNOSTIC (Step 2): desktop Chrome UA, isolated experiment.
                // The stock WebView UA's "Version/4.0" token makes YouTube
                // refuse to boot the player, so we test a desktop Chrome UA to
                // see whether YouTube then serves a fully playable player
                // config (vs. the mobile/WebView fallback). See IFRAME_ERROR
                // + YT_ON_ERROR_RAW logs for the outcome.
                settings.userAgentString = DESKTOP_CHROME_UA
                settings.setSupportMultipleWindows(false)
                // ROTATION FIX (backstop): whenever the WebView view resizes
                // (device rotation, the player's fullscreen button), push the
                // EXACT CSS size into the page. The page's own resize event
                // can be delayed or swallowed in a configChanges WebView, but
                // Android always knows the true view size — so it injects it
                // explicitly via resizePlayer(w, h) (which also re-lays the
                // iframe with player.setSize() and resumes playback if the
                // rotation tore down the rendering surface).
                addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
                    val nw = r - l
                    val nh = b - t
                    val ow = or - ol
                    val oh = ob - ot
                    if (nw == ow && nh == oh) return@addOnLayoutChangeListener
                    if (nw <= 0 || nh <= 0) return@addOnLayoutChangeListener
                    if (released || !controller.pageReady) return@addOnLayoutChangeListener
                    val density = resources.displayMetrics.density
                    val wCss = (nw / density).toInt().coerceAtLeast(1)
                    val hCss = (nh / density).toInt().coerceAtLeast(1)
                    Log.d(
                        TAG,
                        "RESIZE_PUSH device=${nw}x$nh css=${wCss}x$hCss " +
                            "lastState=${controller.lastState}"
                    )
                    try {
                        evaluateJavascript("resizePlayer($wCss, $hCss)", null)
                    } catch (e: Exception) {
                        Log.w(TAG, "RESIZE_PUSH_FAILED: ${e.message}")
                    }
                }
                // Diagnostic red (Check 5) REMOVED per directive: it proved the
                // WebView area composites (the page background rendered as a
                // solid red screen while audio played). Rendering diagnostics
                // now live in the JS SIZES[...] logs and the GEOM logs.
                setBackgroundColor(android.graphics.Color.BLACK)
                // The IFrame API + embed use cookies (consent/session);
                // first-party AND third-party cookie handling keeps the player
                // config requests working in the WebView.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // Keep the renderer alive when the app is backgrounded — a
                // killed renderer is what causes the permanent white screen.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setRendererPriorityPolicy(
                        WebView.RENDERER_PRIORITY_IMPORTANT,
                        false // never waive the renderer when not visible
                    )
                }

                var rendererGoneCount = 0

                val html = try {
                    ctx.assets.open("youtube_player.html")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (e: Exception) {
                    Log.e(TAG, "youtube_player.html missing from assets", e)
                    "<html><body style='background:#000'></body></html>"
                }

                fun loadPlayerPage() {
                    // https base URL: the IFrame API needs a real origin to
                    // handshake with the embed (a bare file:// page has a
                    // "null" origin and the API refuses to initialize).
                    loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
                }

                addJavascriptInterface(bridge, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    // Keep every navigation inside this WebView — nothing may
                    // leave the app.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest?
                    ): Boolean = false

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "PAGE_FINISHED url=$url")
                        controller.pageReady = true
                        applySource(view, controller, bridge)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "PLAYER_PAGE HTTP ${errorResponse?.statusCode}")
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        // Log-only: main-frame errors can fire transiently
                        // (ERR_ABORTED / ERR_CACHE_MISS during reloads) and a
                        // hard error would pin a false "can't be played" card.
                        // The IFrame API's onError is the authoritative signal.
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "PLAYER_PAGE load error code=${error.errorCode} " +
                                "desc=${error.description}")
                        }
                    }

                    // API 26+. The system killed our renderer (crash or OOM).
                    // Default behavior leaves a blank WHITE WebView — reload the
                    // page so the player comes back. Bounded retries.
                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail
                    ): Boolean {
                        rendererGoneCount++
                        // didCrash() is API 26+; the platform never invokes this
                        // callback below API 26, so the guard is defensive only.
                        val crashed = android.os.Build.VERSION.SDK_INT >= 26 && detail.didCrash()
                        Log.w(
                            TAG,
                            "RENDERER_GONE crashed=$crashed oom=${!crashed} " +
                                "count=$rendererGoneCount"
                        )
                        return if (rendererGoneCount <= 2) {
                            view.post {
                                if (!released) {
                                    Log.i(TAG, "RENDERER_GONE reloading player page")
                                    controller.pageReady = false
                                    controller.appliedVideoId = null
                                    loadPlayerPage()
                                }
                            }
                            true // handled: we reload instead of showing white
                        } else {
                            // Recovery exhausted — tell the UI the player is dead
                            // (no pageReady guard: the UI must see this).
                            bridge.onErrorInternal(YtError.HTML5_PLAYER)
                            true
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    // Surface the player's own console errors — they reveal why
                    // media fails (format selection, DRM, network).
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d(
                            TAG,
                            "CONSOLE[${consoleMessage.messageLevel()}] " +
                                "${consoleMessage.message()} @${consoleMessage.sourceId()}:" +
                                "${consoleMessage.lineNumber()}"
                        )
                        return true
                    }

                    // The player's built-in fullscreen button. The WebView
                    // hands us the ACTUAL fullscreen video surface; if we
                    // don't host it (old behavior: only rotate), the surface
                    // is orphaned and the screen goes blank while audio keeps
                    // playing. Host the view in a fullscreen overlay added to
                    // the activity's window and rotate to landscape so the
                    // hub's immersive logic hides the system bars. The overlay
                    // covers the Compose UI entirely, so nothing else can
                    // obscure the video.
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        val activity = ctx as? Activity ?: return
                        controller.customViewCallback = callback
                        val container = ensureFullscreenContainer(activity, controller)
                        container.removeAllViews()
                        view?.let {
                            container.addView(
                                it,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                        container.visibility = View.VISIBLE
                        Log.i(
                            TAG,
                            "FULLSCREEN_SHOW customView=${view?.javaClass?.simpleName ?: "null"}"
                        )
                        activity.requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }

                    override fun onHideCustomView() {
                        Log.i(TAG, "FULLSCREEN_HIDE")
                        hideFullscreenCustomView(controller, ctx as? Activity, resetOrientation = true)
                    }
                }

                loadPlayerPage()
            }
        },
        update = { webView ->
            // Recomposition diagnostic: same id as FACTORY_CREATED proves the
            // WebView is NOT recreated on recomposition (a changing id would
            // mean teardown/recreate churn). update NEVER rebuilds the view.
            Log.d(TAG, "UPDATE id=${System.identityHashCode(webView)} " +
                "videoId=$videoId retryToken=$retryToken")
            controller.videoId = videoId
            controller.retryToken = retryToken
            controller.preferredMuted = muted
            controller.maxQuality = maxQuality
            // Assigned OUTSIDE the pageReady guard: the first applySource may
            // come from onPageFinished before any ready update runs, and it
            // must see the resume position (Continue Watching on first open).
            controller.resumeFromSeconds = resumeFromSeconds
            if (controller.pageReady &&
                (controller.appliedVideoId != videoId ||
                    controller.appliedRetryToken != retryToken)
            ) {
                applySource(webView, controller, bridge)
            }
            // Playback speed: applied whenever the requested rate changes
            // (re-applied after every source change via the source block).
            if (controller.pageReady && controller.appliedRate != playbackRate) {
                controller.appliedRate = playbackRate
                try {
                    webView.evaluateJavascript("setRate($playbackRate)", null)
                } catch (e: Exception) {
                    Log.w(TAG, "SET_RATE_FAILED: ${e.message}")
                }
            }
            // Continue-Watching / Watch-Again re-seek on the current source
            // (no reload). Applied whenever the token bumps.
            if (controller.pageReady && controller.appliedSeekToken != seekToken) {
                controller.appliedSeekToken = seekToken
                try {
                    webView.evaluateJavascript("seekToSeconds($seekToSeconds)", null)
                } catch (e: Exception) {
                    Log.w(TAG, "SEEK_FAILED: ${e.message}")
                }
            }
            // Shorts transport commands (play / pause / mute). While the page
            // is still loading the command is remembered and flushed by
            // applySource once the page finishes — a pause/mute tapped during
            // buffering must not be silently lost.
            if (controller.appliedCommandToken != commandToken) {
                if (controller.pageReady) {
                    controller.appliedCommandToken = commandToken
                    sendCommandJs(webView, command)
                } else {
                    controller.pendingCommand = command
                    controller.pendingCommandToken = commandToken
                }
            }
        },
        onRelease = {
            released = true
            controller.pageReady = false
            controller.webView = null
            // If a fullscreen custom view was showing, dismiss it, detach the
            // overlay from the window, and return the activity to its natural
            // orientation (the user left the player). When no custom view was
            // showing (e.g. the user auto-rotated to landscape), orientation
            // is left exactly as it was.
            hideFullscreenCustomView(controller, hostActivity, resetOrientation = true)
            controller.fullscreenContainer?.let { container ->
                (container.parent as? ViewGroup)?.removeView(container)
            }
            controller.fullscreenContainer = null
            try {
                it.removeJavascriptInterface("AndroidBridge")
            } catch (e: Exception) {
                // ignore — WebView already torn down
            }
            it.destroy()
        }
    )
}

/**
 * Lazily creates (once per player) the fullscreen overlay that hosts the
 * WebView's custom fullscreen video view, attached to the activity's window
 * decor so it covers the entire screen including the Compose UI and the
 * status/navigation bars.
 */
private fun ensureFullscreenContainer(
    activity: Activity,
    controller: PlayerController
): FrameLayout {
    controller.fullscreenContainer?.let { return it }
    val container = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Solid black behind the video (and while it buffers).
        setBackgroundColor(Color.BLACK)
    }
    val decor = activity.window.decorView as? ViewGroup
    if (decor == null) {
        // Extremely rare (the decor is a ViewGroup on every supported API) —
        // but if it ever isn't, the custom view would silently render nowhere
        // and the screen would go blank again; log it so it's diagnosable.
        Log.w(
            TAG,
            "FULLSCREEN_DECOR_UNAVAILABLE ${activity.window.decorView.javaClass.simpleName}"
        )
    } else {
        decor.addView(container)
    }
    controller.fullscreenContainer = container
    return container
}

/**
 * Dismisses a showing fullscreen custom view: returns the video surface to the
 * player (CustomViewCallback.onCustomViewHidden) and hides the overlay. When
 * [resetOrientation] the activity returns to its natural orientation (user
 * exited fullscreen); when false (pause/release) orientation is left alone.
 */
private fun hideFullscreenCustomView(
    controller: PlayerController,
    activity: Activity?,
    resetOrientation: Boolean
) {
    val hadCustomView = controller.customViewCallback != null
    val callback = controller.customViewCallback
    controller.customViewCallback = null
    controller.fullscreenContainer?.let {
        it.removeAllViews()
        it.visibility = View.GONE
    }
    callback?.onCustomViewHidden()
    // Only restore the orientation when a fullscreen view was actually
    // dismissed — never yank the activity out of a user-initiated landscape.
    if (resetOrientation && hadCustomView) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

/**
 * Sends the current source to the IFrame API player via `loadVideoById`
 * (in-place switch — no page reload).
 */
private fun applySource(webView: WebView, controller: PlayerController, bridge: YtBridge) {
    if (!controller.pageReady) return
    val video = controller.videoId ?: return
    val resume = controller.resumeFromSeconds
    // Always pass the desired mute state so every new video honours the
    // user's last choice (Shorts swiping keeps the setting consistent); the
    // max-quality flag rides along so the embed is created with the quality
    // pin armed.
    val js = "loadVideo('$video', $resume, ${controller.preferredMuted}, ${controller.maxQuality})"
    controller.appliedVideoId = video
    controller.appliedRetryToken = controller.retryToken
    // New source → a repeated error from the old source must be able to fire
    // again (the UI resets its error state when the source changes).
    bridge.resetErrorDedup()
    try {
        webView.evaluateJavascript(js, null)
        Log.d(TAG, "SOURCE_APPLIED $js")
    } catch (e: Exception) {
        Log.w(TAG, "SOURCE_APPLY_FAILED: ${e.message}")
    }
    // Flush a transport command that arrived while the page was still loading.
    controller.pendingCommand?.let { cmd ->
        controller.pendingCommand = null
        controller.appliedCommandToken = controller.pendingCommandToken
        sendCommandJs(webView, cmd)
    }
}

/** Maps a Shorts-viewer command name to its JS call and evaluates it. */
private fun sendCommandJs(webView: WebView, command: String) {
    val js = when (command) {
        "play" -> "playVideo()"
        "pause" -> "pauseVideo()"
        "mute" -> "setMuted(true)"
        "unmute" -> "setMuted(false)"
        "back10" -> "seekBy(-10)"
        "fwd10" -> "seekBy(10)"
        else -> null
    }
    if (js != null) {
        try {
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.w(TAG, "COMMAND_FAILED: ${e.message}")
        }
    }
}

/** Shared state between the WebView factory and the update block. */
private class PlayerController {
    @Volatile var pageReady = false
    /** Live WebView reference for lifecycle forwarding + geometry logging. */
    @Volatile var webView: WebView? = null
    var videoId: String? = null
    var appliedVideoId: String? = null
    var retryToken = 0
    var appliedRetryToken = 0
    /** Resume position (seconds) for the current source (applied via loadVideo). */
    var resumeFromSeconds: Double = 0.0
    /** The user's desired mute state (applied with every source load). */
    @Volatile var preferredMuted: Boolean = true
    /** Max-quality pin (best available playback quality) for the current player. */
    @Volatile var maxQuality: Boolean = false
    /** Last seekToken honored (drives the Continue-Watching re-seek). */
    var appliedSeekToken: Int = 0
    /** Last commandToken honored (Shorts transport commands). */
    var appliedCommandToken: Int = 0
    /** Command queued while the page was still loading (flushed on page ready). */
    var pendingCommand: String? = null
    var pendingCommandToken: Int = -1
    /** Last playback rate pushed to the player (applied when it changes). */
    @Volatile var appliedRate: Double = 1.0
    /** Last player state reported by the IFrame API (for rotation diagnostics). */
    @Volatile var lastState: Int = YtState.UNSTARTED
    /** Fullscreen custom-view hosting (the player's built-in fullscreen button). */
    @Volatile var customViewCallback: WebChromeClient.CustomViewCallback? = null
    @Volatile var fullscreenContainer: FrameLayout? = null
}

/**
 * Check 2 (rendering): dumps the WebView's geometry, attachment, and
 * compositing state at the moment a player state fires, PLUS the FULL ancestor
 * chain with each ancestor's visibility (V/I/G). This answers the user's key
 * finding directly: `isShown()=false` despite VISIBLE + attached + non-zero
 * size is ONLY possible if some ancestor is GONE/INVISIBLE — the chain shows
 * exactly which one. It also detects WebView recreation (id changes) and
 * window-level visibility.
 */
private fun logWebViewGeometry(tag: String, view: WebView) {
    val visibility = when (view.visibility) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "${view.visibility}"
    }
    val layer = layerName(view.layerType)
    val chain = StringBuilder()
    var current: ViewParent? = view.parent
    var depth = 0
    while (current != null && depth < 14) {
        if (current is View) {
            chain.append(current.javaClass.simpleName)
                .append('[')
                .append(visName(current.visibility))
                .append(']')
        } else {
            chain.append(current.javaClass.simpleName)
        }
        chain.append(" > ")
        current = current.parent
        depth++
    }
    Log.d(
        TAG,
        "GEOM[$tag] id=${System.identityHashCode(view)} " +
            "w=${view.width} h=${view.height} vis=$visibility " +
            "shown=${view.isShown} attached=${view.isAttachedToWindow} " +
            "windowVis=${visName(view.windowVisibility)} layer=$layer " +
            "alpha=${view.alpha} chain=$chain"
    )
}

/** One-letter visibility for the ancestor chain: V=visible, I=invisible, G=gone. */
private fun visName(v: Int): String = when (v) {
    View.VISIBLE -> "V"
    View.INVISIBLE -> "I"
    View.GONE -> "G"
    else -> "$v"
}

/** Human-readable layer type for the LAYER_SET / GEOM logs. */
private fun layerName(t: Int): String = when (t) {
    View.LAYER_TYPE_HARDWARE -> "HARDWARE"
    View.LAYER_TYPE_SOFTWARE -> "SOFTWARE"
    View.LAYER_TYPE_NONE -> "NONE"
    else -> "$t"
}

/**
 * The object injected into the page as `window.AndroidBridge`.
 *
 * CRITICAL: the constructor parameters are deliberately named DIFFERENTLY
 * from the @JavascriptInterface methods (stateCallback / errorCallback / …).
 * A member function shadows any constructor parameter with the same name, so
 * if a param were named `onError`, then `onError(code)` inside the method
 * would recurse into ITSELF forever (StackOverflowError, seen on-device at
 * the old `YtBridge.onError`). The JavaBridge then reports the failure as
 * "Method not found" because the call never returns.
 */
private class YtBridge(
    private val stateCallback: (Int) -> Unit,
    private val errorCallback: (Int) -> Unit,
    private val autoplayBlockedCallback: () -> Unit,
    private val readyCallback: () -> Unit,
    private val progressCallback: (Double, Double) -> Unit,
    private val liveCallback: () -> Unit,
    private val muteStateCallback: (Boolean) -> Unit
) {
    /** Dedup: surface a given error code only once per loaded source. */
    @Volatile
    private var lastForwardedError: Int? = null

    @JavascriptInterface
    fun onStateChange(state: Int) {
        Log.d(TAG, "IFRAME_STATE=$state")
        stateCallback(state)
    }

    @JavascriptInterface
    fun onError(code: Int) {
        // Call-site tagged: this path is invoked ONLY from the JS onError
        // handler with YouTube's raw event.data (see YT_ON_ERROR_RAW log).
        Log.w(TAG, "IFRAME_ERROR=$code (from JS onError event.data)")
        if (lastForwardedError != code) {
            lastForwardedError = code
            errorCallback(code)
        }
    }

    /** Unhandled JS exceptions (window.onerror) — logged, never error-card. */
    @JavascriptInterface
    fun onJsError(message: String) {
        Log.w(TAG, "JS_EXCEPTION $message")
    }

    @JavascriptInterface
    fun onAutoplayBlocked() {
        Log.w(TAG, "IFRAME_AUTOPLAY_BLOCKED")
        autoplayBlockedCallback()
    }

    /**
     * Real playback position from the JS progress timer (every ~5 s while
     * playing, plus once on pause/end). The app persists the fraction so the
     * Media tab can show how much of each video was watched.
     */
    @JavascriptInterface
    fun onProgress(currentSeconds: Double, durationSeconds: Double) {
        if (durationSeconds <= 0) return
        Log.d(TAG, "IFRAME_PROGRESS ${Math.round(currentSeconds)}s/${Math.round(durationSeconds)}s")
        progressCallback(currentSeconds, durationSeconds)
    }

    /**
     * The player reported an infinite duration — this source is LIVE. Fired by
     * the page's reportProgress when getDuration() is not finite (see
     * youtube_player.html), before any finite progress would be forwarded.
     */
    @JavascriptInterface
    fun onLive() {
        Log.d(TAG, "IFRAME_LIVE")
        liveCallback()
    }

    @JavascriptInterface
    fun onReady() {
        Log.d(TAG, "IFRAME_READY")
        readyCallback()
    }

    /** The player's real muted state (1 = muted, 0 = unmuted). */
    @JavascriptInterface
    fun onMuteState(muted: Int) {
        muteStateCallback(muted == 1)
    }

    /** Internal path (non-JS errors) — fires the same Kotlin callback. */
    fun onErrorInternal(code: Int) {
        Log.w(TAG, "IFRAME_ERROR_INTERNAL=$code")
        if (lastForwardedError != code) {
            lastForwardedError = code
            errorCallback(code)
        }
    }

    /** Reset the error dedup when a new source is loaded. */
    fun resetErrorDedup() {
        lastForwardedError = null
    }
}

private const val TAG = "YoutubePlayer"

/**
 * Base URL that gives the local player page a real (https) origin — and the
 * value `window.location.origin` reports, which is ALSO what the player page
 * sends as playerVars.origin / widget_referrer, so the two are ALWAYS aligned.
 *
 * DIAGNOSTIC (Step 2): deliberately https://localhost instead of
 * https://www.youtube.com. A parent page that masquerades as youtube.com
 * itself can trip YouTube's stricter embed validation (error 152) in
 * WebViews; some embedders found a non-YouTube origin like https://localhost
 * passes the origin check instead. Flip this constant to test step 3
 * (https://www.youtube-nocookie.com) as the next variant.
 */
private const val BASE_URL = "https://localhost"

/**
 * DIAGNOSTIC desktop Chrome UA (Step 2 experiment) — set verbatim per the
 * diagnosis directive so YouTube serves its desktop player config.
 */
private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
