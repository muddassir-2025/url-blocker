# URL Blocker manual test plan

Run on a physical or emulator device with Chrome, the Google app, and the URL Blocker Accessibility Service enabled. Use a disposable test profile because Accessibility-based URL extraction varies by Android and Chrome version.

## Protection and Chrome

1. Open an allowed Chrome URL; it remains visible.
2. Open a blocked keyword in the hostname, path, query, and fragment; each is blocked.
3. Repeat a blocked URL with mixed case; it is blocked case-insensitively.
4. Open YouTube home, then navigate to a video without a blocked URL term; it remains allowed.
5. Navigate within YouTube without a full reload; a newly blocked URL is detected.
6. Follow an allowed Google result into Chrome, then navigate to blocked content; Chrome monitoring continues.
7. Leave Chrome while a blocked tab is present, return to Chrome, and confirm the same URL is evaluated again.
8. Confirm an allowed URL containing a blocked-looking word only in a recommendation title is not blocked.

## Google app

1. Search an allowed term; results remain visible.
2. Search a blocked term and confirm blocking occurs with the search bar focused.
3. Search a blocked term, dismiss focus/keyboard, and confirm blocking still occurs without tapping the search bar.
4. Search an allowed term whose result title contains a blocked word; confirm it is not blocked.
5. Leave Google with blocked results visible, return, and confirm the query is evaluated again.
6. Click an allowed result into Chrome and confirm Chrome URL monitoring takes over.
7. Search a blocked term, submit it, and confirm blocking occurs before interacting with the search box again.
8. From Google, open a YouTube result in an embedded/custom-tab surface. If an address-bar URL is exposed, confirm a blocked YouTube/Shorts rule is enforced; if no URL is exposed, record the diagnostic tree and treat this as an Android platform limitation rather than a passing block test.

## Google app in-app browser (websites opened from search results)

Logcat filter for these tests: `adb logcat -s GoogleAppUrlExtractor ContentExtractor UrlBlockerService`

1. Open the Google app, search an allowed term, and tap a result (e.g. `youtube.com`). Confirm the site opens inside the Google app (package stays `com.google.android.googlequicksearchbox`).
2. While the site is open, inspect Logcat. Expected outcomes:
   - `GOOGLE_APP_WEBVIEW_NODE` — a WebView node is exposed.
   - `GOOGLE_APP_CLOSE_DOMAIN_DETECTED domain=youtube.com` — the toolbar close button carries the domain (best case).
   - `GOOGLE_APP_DOMAIN_CANDIDATE_BARE domain=youtube.com` — a bare-domain chip is visible (diagnostic only).
   - `GOOGLE_APP_EXTRACT_RESULT inAppBrowserActive=true domain=youtube.com` — extraction succeeded.
   If none of these appear, record the tree (the service's `GOOGLE_DIAGNOSTIC_*` lines) and treat this Google app version as not exposing the site identity — an Android platform limitation.
3. Add `youtube.com` as a blocked website in the app (Websites tab). Open YouTube from Google search again inside the in-app browser; the overlay must appear. Remove `youtube.com` afterwards and confirm YouTube is allowed again (nothing hardcoded).
4. Add a custom keyword, then open a site whose **URL** contains it (e.g. a site whose domain or path contains the keyword). If the in-app browser exposes an address bar (tap the domain chip), the full-URL keyword match must block; if no URL is exposed, record the limitation.
5. From the in-app browser, perform a site-internal search (e.g. search a blocked keyword on YouTube). Confirm whether `GOOGLE_APP_*` logs show the URL or query. If only the page title is exposed (`... - YouTube`), the existing title rule (step 5b) may catch it; otherwise record that in-app website search queries are not filterable without URL/query exposure.
6. Confirm the Google search-results page itself is never treated as a website: with no blocked domain/keyword, results remain visible and `GOOGLE_APP_EXTRACT_RESULT` reports `inAppBrowserActive=false`.
7. Explicit Google search of a blocked keyword inside the Google app must still block (regression check — TEST 3).

## YouTube app

1. Open the YouTube app and play a video with a title that contains a blocked keyword (user or built-in); the video must be blocked and the overlay must appear.
2. Play a Shorts video from the YouTube app while `shorts` is in a blocked-domain rule (or the video title matches a keyword); confirm the block occurs.
3. Play an allowed video (no keyword matches); confirm it remains visible.
4. Navigate between videos by swiping/tapping; confirm each transition is evaluated.
5. Leave YouTube while a blocked video was detected, reopen YouTube, and confirm content is evaluated again.
6. Confirm blocking occurs before the user can watch a full video (detection delay is at most 1-2 poll cycles; instant detection is not guaranteed by Android's accessibility framework).

## Watch-page NSFW safety net (innocent title + explicit player frame)

Logcat filter: `adb logcat -s UrlBlockerService ThumbnailSafetyAnalyzer`

Prerequisites: same as feed blocking — Accessibility Service, "Display over other apps", and the NSFW model present.

1. On a YouTube feed, click a video whose TITLE is clean but whose player frame is explicit (a video the feed image pipeline would have missed). Expect `WATCH_MONITOR_START url=... videoId=...` then a `WATCH_NSFW_INFERENCE ... capture=N` line every ~1s (prime phase, first 8) / ~3s (steady phase, indefinite) while the same video is on screen, and `WATCH_PAGE_NSFW_BLOCK score=0.xx capture=N` + the standard block overlay (close tabs + Home) the moment ANY single frame scores >= 0.6 — no averaging, so a short explicit scene must still block.
2. Safe video: click a genuinely safe video — expect `WATCH_MONITOR_FRAME_ALLOW ... capture=N` lines for every capture and the video plays normally. Monitoring is INDEFINITE at the 3s steady cadence while the SAME video stays on screen (each capture is a single ~50ms inference — battery-friendly). Leave the video playing for several minutes and captures must keep appearing; explicit content that appears LATER in the video must still block. Monitoring stops only when you leave the video/page (`WATCH_MONITOR_STOP videoChangedOrLeft` / `WATCH_MONITOR_END`), switch apps, or a block fires.
3. Crop strategy: `WATCH_NSFW_INFERENCE` logs the region. With tree-based detection the region is the actual `<video>` element rect (16:9-ish, top half, >=60% width); otherwise the fallback is the top 50% of the screen (`region=[0,0,W,H*0.5]`). A `capture=1` region of `[0,0,W,H*0.5]` must cover the player — if the player sits below it, the tree-based detection is not finding the `<video>` node on this device and the fixed fraction needs raising.
4. Per-video dedup: staying on the same video must NOT re-log `WATCH_MONITOR_START` on every 500ms poll — only one monitor per video id per foreground session (cleared on app switch).
5. Monitor stops: navigating back to the feed (or to another video, or away from Chrome) must log `WATCH_MONITOR_STOP videoChangedOrLeft` and stop capturing. The video it was on must never be blocked after leaving.
6. No-op check: ordinary websites (non-YouTube), YouTube feed/search pages, and the YouTube app (URL never exposed) must NOT log `WATCH_MONITOR_START`.
7. Rate-limit interplay: clicking a video right after a feed analysis may throttle the first screenshot — expect `WATCH_MONITOR_CAPTURE_SKIPPED (throttled)` and the next cadence tick retries. No `SCREENSHOT_FAILED code=3` spam expected thanks to the shared 1/s gate.

## YouTube feed blocking in Chrome (pre-emptive card markers)

Logcat filter for these tests: `adb logcat -s UrlBlockerService KeywordMatcher ContentExtractor ThumbnailSafetyAnalyzer FeedBadgeOverlay`

Prerequisites: Accessibility Service enabled, "Display over other apps" granted, and the NSFW model present (`app/src/main/assets/nsfw_detector.tflite`).

1. Verify the model loads on-device: start the service and confirm `MODEL_LOADED input=[1, 224, 224, 3] inputIsFloat=true output=[1, 2]` in logcat. If `MODEL_MISSING` appears, the image signal is disabled (text signals still work).
2. Open `m.youtube.com` in Chrome and search a blocked keyword (e.g. "cleavage"). Every matching result card must be marked BEFORE any video is opened: the card's THUMBNAIL area (top ~60%) is replaced by a blurred image with a centered `🚫 BLOCKED / Explicit Content` label, and the title below stays visible. `YOUTUBE_FEED_CARDS count=N` then `FEED BLOCK MARKED ... cards=M` confirm the flow.
3. Tap anywhere on a blocked card — thumbnail or title — nothing must happen (the overlay window covers the whole card and is inert; the video underneath cannot be opened).
4. Thumbnail image analysis (Android 11+): on a feed with no text-blocked cards, expect `THUMBNAIL_ANALYSIS_START cards=N new=N cached=0` followed by a `THUMBNAIL_INFERENCE title=... score=0.xx` line per card, then `FEED IMAGE BLOCK MARKED` when a thumbnail crosses 0.6. A HARDWARE-config screenshot is converted to software automatically — if you see `SCREENSHOT_CONVERT_FAILED` or `INFERENCE_FAILED: Config#HARDWARE`, the conversion regressed. The model's `[1,2]` output tensor is mirrored with a nested `float[1][2]` array; `INFERENCE_FAILED: Cannot copy ... with shape [1, 2] to a Java object with shape [2]` means the output-allocation shape-mirroring regressed.
5. Deceptive-video case: a card whose TITLE is safe but whose channel name is suggestive (or whose thumbnail is NSFW) must still be marked — `YOUTUBE_FEED_SCORE score=... reasons=[CHANNEL:...]` / `reasons=[THUMBNAIL IMAGE: NN%]` confirm the weighted risk score fired (score >= 0.3). Image-blocked cards show the BLURRED real thumbnail (not a flat scrim).
6. Dedup: scrolling away and back to the same cards must NOT re-run inference — expect `THUMBNAIL_ANALYSIS_START ... cached=N` (N > 0) and no repeated `THUMBNAIL_INFERENCE` lines for the same titles. The 5s throttle + per-session score cache prevent churn on `WINDOW_CONTENT_CHANGED`. A text-blocked card set logs `FEED BLOCK MARKED` ONCE per distinct set (`cards=` titles) — repeat scans while the markers stay on screen must not re-log it. Event-driven tree scans are throttled to 1/250ms, so a `TYPE_WINDOW_CONTENT_CHANGED` storm must not jank the UI (`Skipped N frames` in logcat indicates a regression).
   - Screenshot throttling: ALL screenshot takers (analysis + blur fetch) share one global 1/s gate (`SCREENSHOT_THROTTLED` in logcat). If you see `SCREENSHOT_FAILED code=3`, captures are exceeding the system rate limit. A text-blocked card whose first blur fetch was rate-limited retries ~1.6s later — expect the marker to upgrade from scrim to the blurred thumbnail without any interaction (`FEED_BADGES_SHOWN ... blurred=M` rising).
   - Crop verification: `THUMBNAIL_BOUNDS=[l,t,r,b] THUMBNAIL_SIZE=WxH` is logged per card at extraction time — this is the 16:9 IMAGE band the NSFW pipeline crops (from the full-card node's top edge, or the band directly ABOVE a text-only title row). Each `THUMBNAIL_INFERENCE` then logs the actual `region=[l,t,r,b]` plus the source `thumb=[l,t,r,b]` and `cardSize=WxH`. A tiny text-sized region (e.g. 800x79) must NEVER appear in `THUMBNAIL_INFERENCE` — such cards are skipped with `THUMBNAIL_REGION_REJECTED ... (too small to be a thumbnail...)` (size gate: width >= 200 AND height >= 100). If a title-row strip still gets inferred, the geometry regressed.
   - Chrome NTP tiles must NOT be analyzed as thumbnails: the Chrome new-tab page tiles ("Ask AI Mode", "New Incognito tab", measured 490x110 on-device) must never appear in `THUMBNAIL_INFERENCE` — they fail the raised card-height gate (>= 200px) AND the `isYouTubeCardText` metadata check AND the title deny-list. If NTP tiles reappear in the logs, the feed-card gate regressed.
   - Embedded-browser probe: launcher / system UI / keyguard / settings / IME packages must log `EMBEDDED_PROBE_SKIPPED` and never `EMBEDDED_BROWSER_DETECTED` (observed false positives: com.motorola.launcher3, com.android.systemui).
7. Scrolling the feed re-positions the markers (poll-time re-draw). Markers disappear when the feed page is left (`FEED_BADGES_CLEARED`). A text-blocked card first shows the dark scrim; when the blurred thumbnail arrives a moment later the marker REDRAWS with the real (blurred) thumbnail — verify `FEED_BADGES_SHOWN count=N blurred=M` shows M rising after the first scrim-only draw. The blurred bitmaps are owned by the service and recycled exactly once when the markers clear (a double-recycle shows up as `RuntimeException: Canvas: trying to use a recycled bitmap`).
8. If a blocked video opens anyway (related rail, reopened tab), the watch-page full block must fire (`BLOCK DETECTED` + overlay) — the fallback when markers are not possible.
9. Without the overlay permission, no markers are drawn (`FEED_BADGE_PERMISSION_MISSING`) and the full block fires on the watch page instead.
10. Search pages are never blocked as a whole: searching a blocked word (e.g. strict-mode `cleavage`) on m.youtube.com must NOT trigger a full page block — the `/results?search_query=...` URL and the "<query> - YouTube" page title are the USER'S QUERY, not video content. Only individual result CARDS are checked (`YOUTUBE_FEED_SIGNAL` / `FEED BLOCK MARKED` with a card list). A blocked DOMAIN rule still applies to the host.
11. Block-reason attribution: the logs distinguish WHY a card was blocked — `THUMBNAIL_ACTION=BLUR_BY_IMAGE` (the NSFW model scored it >= 0.6) vs `THUMBNAIL_ACTION=BLOCK_BY_TEXT` (title/channel/description keyword matched; the `keyword=` field names it). A `BLOCK_BY_TEXT` card may carry `nsfwScore=0.00` — that is correct, not a bug.

## Channels tab (permanent channel blocklist)

1. Open the Channels tab and add a channel name (with or without `@`, any case). Confirm it appears normalized and is persisted across an app restart.
2. Play a video from that channel (YouTube app or Chrome) with a clean title — it must still be blocked (`YOUTUBE_CHANNEL` / `CHANNEL_IN_CHROME` log entry).
3. Remove the channel; its videos must be allowed again.
4. Auto-block check: without adding anything, watch 2 different blocked videos from the same unidentified channel; the channel is added automatically (`CHANNEL_AUTO_BLOCKED`), then the 3rd video from it blocks on title alone.

## YouTube via Custom Tab / embedded browser

1. From Google Search, tap a YouTube result that opens inside the Google app (Custom Tab). If a URL bar is visible, confirm domain/keyword blocking works. If no URL bar is visible, check logs for DIAG/SIGNAL entries and record the outcome as a platform limitation.
2. From a non-Chrome app (e.g., Reddit, Twitter), tap a YouTube link. If an external browser opens, Chrome monitoring applies. If an in-app browser opens without URL exposure, content-based blocking is not available.

## Shorts detection

1. Open youtube.com/shorts/... in Chrome; confirm the URL `shorts` in the path is checked by URL matching.
2. Open the YouTube app and navigate to Shorts; confirm the "Shorts" accessibility label is detected and logged.
3. Add a custom keyword `shorts`; confirm title-based blocking triggers on Shorts content in the YouTube app.

## Rules and persistence

1. Add, edit, and remove a custom keyword; removal requires confirmation.
2. Expand/search the custom list and confirm every saved keyword is reachable.
3. Add `example.com`; confirm `example.com` and `sub.example.com` block, while `notexample.com` does not.
4. Restart the app, service, Chrome, and device; confirm rules remain.
5. Confirm built-in rules are always active and cannot be edited or removed.
6. Keyword false-positive regression: play a clean video titled "First dates ..." (any channel) with Strict Mode enabled — it must NOT be blocked (bare `date`/`dates` were removed from Strict Mode because they matched inside "dates"; `dating` still blocks "Dating Tips").

## Blocking safety and service status

1. When a block occurs, confirm the blocked page is not revealed after dismissing the blocking screen or pressing Back.
2. Confirm repeated accessibility events do not launch duplicate overlays.
3. Return to Chrome after blocking and confirm the blocked URL is blocked again without an infinite overlay loop.
4. Disable the Accessibility Service externally; the dashboard must say `Protection Inactive` and offer `Restore Protection`.
5. Re-enable the service and confirm the dashboard returns to `Protection Active`.

## Quran Reminder widget

1. Add the widget: it renders 3 cells wide × 1 cell tall (`targetCellWidth=3`, `targetCellHeight=1`, `minWidth=180dp`, `minHeight=40dp`). The title row shows two icon buttons (copy 📋 and refresh 🔄 — vector drawables tinted to the theme, no text labels); the verse text (2 lines max) and reference (Surah S:V · Name) sit below. The verse loads automatically once the one-time download completes (or immediately when already cached).
2. Copy: tap the copy icon — the current verse (reference + text) goes to the clipboard and a `Verse copied to clipboard` toast appears. Paste it anywhere to verify.
3. Refresh: tap the refresh icon — the widget immediately shows `Picking a new verse…` then a NEW verse (different from the current one) within a moment, fully offline. Tap it repeatedly: it must never show the exact same verse twice in a row. The icons must stay visible and tappable during the loading flash.
4. Tap the widget body (not the buttons): opens the full verse details screen (`QuranVerseActivity`) with the complete verse, a `New Verse` action, and a top-bar copy icon.
5. Full-verse copy: in the verse details screen tap the copy icon (top bar) or the `Copy verse` button — the current verse (reference + text) goes to the clipboard and a `Verse copied to clipboard` toast appears. Paste it anywhere to verify.
6. Configurable refresh interval: in the Quran tab the `New verse every` section shows chips for 1, 2, 3, 4, 6, 8, 12, 24 hr (6 hr is the default). Select e.g. `1 hr` — a `New verse every 1 hour` toast appears and `QuranWorkScheduler.reschedule` re-enqueues `quran_reminder_periodic_refresh` with the chosen interval (`ExistingPeriodicWorkPolicy.UPDATE` — the countdown restarts from the change). The footer note updates to `Refreshes automatically every 1 hour · works offline`. Verify in logcat (`QuranDownloadWorker`) that a new verse appears ~1 hour later, and that the choice survives an app restart.
7. Default schedule: `QuranWorkScheduler.ensureScheduled` is called at app startup AND on widget add; it enqueues a periodic `quran_reminder_periodic_refresh` work at the stored interval (default 6 hours, `MODE_REFRESH`, offline-safe, `ExistingPeriodicWorkPolicy.KEEP` so plain app launches don't reset the countdown). Verify in logcat (`QuranDownloadWorker`) that the refresh runs and `refreshAllWidgets` re-renders a new verse on schedule even if the app is never reopened (WorkManager persists the schedule across reboots).

## Quran hub (Quran / Media / Live tabs)

1. Tap the widget → the Quran hub opens on the **Quran** tab with a bottom navigation bar (Quran / Media / Live). The tab shows the surah name, `Surah X · Ayah Y`, the **Arabic verse** (Uthmani script — downloaded once with the initial cache; English-only until then) and the English translation.
2. Arabic pipeline: first run downloads BOTH editions (`en.sahih` + `quran-uthmani`, ~14 MB total one-time). If only English is cached, the verse screen still works and simply omits the Arabic line.
3. Copy / Share / Bookmark: the top bar has share 📤, bookmark 🔖 (fills when saved) and copy 📋 icons. Copy → clipboard (reference + Arabic + English). Share → the system share sheet. Bookmark → `Verse bookmarked` toast; the icon stays filled while that verse is current.
4. Media tab: shows `Saved Channels` (Safina Society seeded by default; add via the `+ Add` chip — paste a `@handle`, channel URL or bare `UC…` id; remove via the ✕ on a channel chip with a confirmation dialog). `Latest Videos` loads instantly from the local cache (a `cached` hint shows), then refreshes from `https://www.youtube.com/feeds/videos.xml?channel_id=…` in the background. Offline first open → an in-app error card, no crash.
5. Tap a video card → the in-app player opens, built on the **official YouTube IFrame Player API** (the WebView loads `assets/youtube_player.html` via `loadDataWithBaseURL(BASE_URL, …)` — a real https origin, never a `file://` URL; hosts a `YT.Player` and forwards REAL events — `onReady` / `onStateChange` / `onError` / `onAutoplayBlocked` — to Android through a JS bridge; see `IFRAME_STATE` / `IFRAME_ERROR` / `IFRAME_READY` logcat lines). DIAGNOSTIC: `BASE_URL` is currently `https://localhost` (and playerVars.origin/widget_referrer = `window.location.origin`, so they are always aligned) — YouTube rejects in-WebView embeds with error 152 when the referrer/origin handling isn't accepted; variants to try by flipping the `BASE_URL` constant: `https://localhost` (current), `https://www.youtube-nocookie.com` (step 3), back to `https://www.youtube.com`. The page also sets `<meta name="referrer" content="strict-origin-when-cross-origin">` and applies YouTube's official embed iframe attributes (`title`, `referrerpolicy`, `allow` incl. `web-share`) to the generated iframe. The exact playerVars are traced as `CONSOLE[log] PLAYER_VARS {…}` and YouTube's raw error value as `YT_ON_ERROR_RAW value=…`. The JS→Android bridge respects JavaBridge arity and has no name-shadowing recursion. Playback stays in-app; on any playback failure (152/100/101/150 or timeout) the card offers **Retry** (in-app) and an **opt-in `Open in YouTube app`** button that launches the YouTube app (or a browser if it's not installed) ONLY when the user taps it — never automatically. Every RSS item is traced with `RSS_VIDEO channelId=… videoId=… videoUrl=… title=…` (logcat). If autoplay is blocked, `IFRAME_AUTOPLAY_BLOCKED` is logged and a `Tap to play` hint shows.
6. Landscape fullscreen (video + Live): rotate to landscape (or tap the player's fullscreen button) while a video plays — status/navigation bars hide, the player fills the screen, and playback CONTINUES (the activity declares `configChanges` so it is not recreated). **Rotation regression (fixed): the WebView previously rotated physically (`GEOM … w=2400 h=870`) while the page's CSS viewport stayed frozen at the portrait size (`SIZES … doc=432x243`) — a `useWideViewPort(true)` + `configChanges` quirk — so the iframe never re-sized and the video rendered blank after the rotation tore down the surface. Fixes: (1) `settings.useWideViewPort = false` / `loadWithOverviewMode = false` so the layout viewport always equals the view's CSS size and tracks view resizes; (2) an `OnLayoutChangeListener` pushes the exact size into the page on every view resize — expect a `RESIZE_PUSH device=… css=… lastState=…` logcat line and `CONSOLE[log] SIZES[RESIZE] player=<landscape CSS w>x<h>` with non-zero values; (3) `resizePlayer(w,h)` re-lays the iframe via `player.setSize()` and RESUMES playback if the video was playing when the rotation paused it (a user-initiated pause before rotating stays paused). Verify: portrait → landscape → portrait cycles keep the video visible and playing at every step (test both a regular video and each Live channel).
7. Live tab: `[🕋 Makkah Live] [🕌 Madinah Live]` chips above the player. Each channel's CURRENT live broadcast video id is resolved at runtime from the official YouTube channel (`LiveStreamResolver` fetches `https://www.youtube.com/channel/<id>/live`, extracts the `videoId` gated on `"isLive":true`, logged as `LIVE_RESOLVED channelId=… videoId=…`) and played with the **same in-app IFrame player as regular videos** — no HLS/CDN. Sources: Makkah = قناة القرآن الكريم (`UCos52azQNBgW63_9uDJoPDA`, Masjid al-Haram), Madinah = قناة السنة النبوية (`UCROKYPep-UuODNwyipe6JMw`, Masjid an-Nabawi). **Makkah must show Masjid al-Haram and Madinah must show Masjid an-Nabawi — the previous Al Jazeera CDN URLs (`getaj.net/AJA/…`) and the Saudi CDN HLS URLs (`svs.itworkscdn.net/…`, which return HTML block pages in practice) are removed.** Only one stream loads at a time; a `Connecting to live stream…` indicator shows while resolving/buffering; the caption flips to `Live · playing inside the app` when `IFRAME_STATE=1`. If nothing is airing, resolution fails, or the embed errors (log `LIVE_NOT_ACTIVE` / `LIVE_EMBED_ERROR`), the in-app `Live stream currently unavailable. Please try again later.` card appears with a `Retry` (forces a fresh resolve). Nothing ever redirects externally. Landscape → immersive fullscreen for the player.
8. Renderer-crash recovery: if the system kills the WebView renderer (low-memory devices — logcat `RENDERER_GONE … oom=true`), the player page reloads automatically (bounded to 2 retries) and the current source is re-applied instead of leaving a permanent blank white screen. Backgrounding the app must not break playback on return (the renderer priority policy keeps it alive).
8b. **Video-surface rendering ("decodes but doesn't display") diagnostics** — logcat filter `adb logcat -s YoutubePlayer VideoPlayerScreen`. Play any video (and test Live too) and check:
    - **ROOT CAUSE (confirmed on-device): `CONSOLE[log] SIZES[…]: player=432x0 iframe=432x0`** — the player div/iframe measured **0 height** on every run, so `YT.Player` created a 0px-tall iframe and the video rendered into an invisible box while audio kept decoding. Mechanism (from the failed first attempt): in `loadDataWithBaseURL` WebViews the `html > body > #player` percentage-HEIGHT chain collapses to 0, AND `YT.Player` REPLACES the `#player` div with the iframe, so the iframe's own `height:100%` re-couples to the broken chain (re-applying `%` in JS snapped it back to 0). FIXED (round 2) in `youtube_player.html`: the player is sized with **viewport units** — `#player { position:fixed; width:100vw; height:100vh }`, which resolve against the live viewport and **cannot be 0** — and a JS `applySize()` forces **explicit pixels** on the container AND the iframe (`width/height` attrs + style) and calls `player.setSize(w, h)` (the IFrame API's official resize) right before player creation, in onReady, on `resize`, in `loadVideo`, and from a **250 ms watchdog** (`SIZES[TICK] tick=… healthy=true/false`) that keeps re-applying until the iframe reports a non-zero height (then self-stops). **Verify: `SIZES[…]` shows `player=432x243` / `iframe=432x243` (non-zero height — 432x732 for fullscreen Live), `SIZES[TICK] … healthy=true`, and the video is VISIBLE.**
    - `LIFECYCLE ON_RESUME -> webView.onResume + resumeTimers + setLayerType(HARDWARE) -> HARDWARE` — the hardware layer is now forced unconditionally at WebView creation AND re-applied on every resume (`LAYER_SET before=… -> requesting HARDWARE` / `LAYER_SET after=HARDWARE` logs). GEOM must consistently show `layer=HARDWARE` now (the earlier `layer=NONE` was the default after the call was removed, not a reset — the codebase contains no other `setLayerType` call).
    - `FACTORY_CREATED id=…` must appear EXACTLY ONCE per player instance, and every `UPDATE id=…` line must carry the SAME id — a changing id means the WebView is being torn down and recreated by recomposition (surface churn → invisible video).
    - `GEOM[IFRAME_STATE=1|2] …` with `id=`, `w`/`h` (non-zero), `vis=VISIBLE`, `shown=true/false`, `attached=true`, `windowVis=V`, `layer=HARDWARE`, and the FULL ancestor `chain=` with per-view V/I/G markers.
    - Overlay isolation: in portrait the details panel sits BELOW the 16:9 player box (Column layout) and all overlays (spinner / error / tap-to-play) are confined inside the player box — no sibling can ever cover the WebView.
9. URL-blocker isolation: with the Accessibility Service active, opening the hub and playing Media/Live must NEVER trigger a block overlay — `UrlBlockerService` early-returns for its own package (`com.example.url_blocker`) before the embedded-browser probe (line ~362 `if (currentForegroundPackage == OUR_PACKAGE) return`), and `GLOBAL_ACTION_HOME` only runs in blocking sequences for Chrome/Google/YouTube. The `Foreground package changed: com.example.url_blocker -> com.motorola.launcher3` log lines are the service OBSERVING the user pressing Home, not the service causing it. Check logcat for the absence of `EMBEDDED_BROWSER_DETECTED` for the app's own package.

## Limitations and managed-device investigation

1. Verify normal Android Settings can uninstall the app; this is expected in normal mode.
2. On a dedicated test device only, follow Android Device Owner provisioning documentation and verify any policy behavior before presenting it as protection.
3. Confirm no URL, query, analytics event, account, or network upload is generated by the app.
