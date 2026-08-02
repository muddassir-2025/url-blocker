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

## Limitations and managed-device investigation

1. Verify normal Android Settings can uninstall the app; this is expected in normal mode.
2. On a dedicated test device only, follow Android Device Owner provisioning documentation and verify any policy behavior before presenting it as protection.
3. Confirm no URL, query, analytics event, account, or network upload is generated by the app.
