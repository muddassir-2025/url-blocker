# URL Blocker architecture analysis

Baseline reviewed: `ab7dfd7` (`google working`). The worktree already contained uncommitted Google re-scan changes in `UrlBlockerService.kt`; those changes are treated as user work and preserved.

## Current architecture

- `MainActivity` is a Jetpack Compose single-activity UI with Dashboard, Keywords, Websites, and Log tabs.
- `MainViewModel` owns UI state and delegates local persistence to `BlockRepository`.
- `BlockRepository` stores custom keywords and domains in `SharedPreferences`; built-in keywords are an immutable in-code set. The event log is session-only memory.
- `UrlBlockerService` is an `AccessibilityService` that watches Chrome variants and the Google app, scans the active accessibility tree, and polls while a target app is foreground.
- `ContentExtractor` converts accessibility nodes/events/window titles into a `ContentSnapshot` containing URL, query, and title.
- `KeywordMatcher` applies URL/domain matching for Chrome and URL/query matching for Google. Page titles are logged only, not blocked.
- `BlockOverlayActivity` presents the blocking screen and sends the user to Home. The service attempts to clear or navigate the target before showing it.

## Existing behavior that works

- Chrome URL-bar extraction is preferred over arbitrary page text.
- Chrome URLs are checked as a complete string, including paths and query parameters.
- Google has a dedicated polling loop and a window-title query fallback for unfocused result pages.
- Custom keywords and domains persist locally and are read by the service on every match check.
- Domain matching uses hostname equality or a dot-prefixed subdomain suffix, avoiding `notexample.com` false matches.
- The UI reports Accessibility Service status and provides a route to Android Accessibility Settings.
- The overlay is excluded from recents and its back action returns Home instead of revealing the previous task.

## Prioritized findings

### P0 — critical blocking/security issues

1. `ContentExtractor` falls back to the shortest arbitrary Google page text. A result title or snippet can be mistaken for the current query and cause a false positive.
2. Google accessibility events currently accept almost any non-URL text as a query. Result content can therefore be treated as the search query.
3. Entering the overlay currently resets `blockingState` to `NORMAL`. This permits event storms and can reveal the blocked task when the overlay exits.
4. A Chrome safe-state check treats an unextracted URL as allowed. An extraction failure must not be equivalent to confirmed safe navigation.
5. Re-evaluation must be fresh after leaving and returning to Chrome or Google; cached snapshot IDs may only suppress duplicate work within the same foreground session.

### P1 — reliability issues

1. Domain input is stored as loosely cleaned text, so paths, ports, malformed hosts, and repeated `www.` prefixes can produce inconsistent rules.
2. Short built-in keywords such as `sex` and `ass` can match unrelated words when searched as raw substrings.
3. Custom keyword deletion is immediate and there is no edit flow.
4. The session log is not durable, so the debug view cannot explain behavior across a service restart.
5. Window titles and event text are not represented with confidence/source metadata, making diagnosis difficult.

### P2 — UI/UX improvements

1. Custom lists need explicit search/filter and confirmation dialogs for removal.
2. Built-in protection should be visibly identified as always-on without exposing an edit/delete control.
3. The dashboard should distinguish `Protection Active` from `Protection Inactive` and use a restore action when the service is off.

### P3 — future features

1. Website usage limits and local usage accounting.
2. A developer-only diagnostics surface backed by durable last-state metadata.
3. Device Owner provisioning documentation and policy integration on managed test devices.

## Android limitations

Normal apps cannot prevent uninstall from Android Settings, prevent Accessibility Service disablement, force-kill Chrome, or survive a factory reset/reflash. Device Owner is an explicit managed-device deployment mode and cannot be silently enabled; it requires provisioning on a dedicated test device and still does not remove physical-control limitations.

Google-specific limitations are similar. An Accessibility Service can inspect text, view IDs, window titles, and exposed address-bar controls. It cannot inspect network requests or force a WebView/Custom Tab to reveal its current URL. A Google result opened in an embedded WebView is therefore blockable when the surface exposes a URL or a reliable search-control signal, but not reliably classifiable as a YouTube Short when only page pixels/content are available. Scanning arbitrary result links or page text would create false positives and is intentionally not used.

## YouTube detection analysis

### Accessibility Service signals per scenario

#### 1. Chrome normal YouTube page (`com.android.chrome`)
- **URL bar**: YES — `youtube.com/watch?v=...` or `youtube.com/shorts/...` is visible in the Chrome URL bar.
- **Page text**: Available but intentionally NOT used for blocking (URL-based blocking only).
- **Reliability**: HIGH — domain matching for `youtube.com` works; keyword matching on URL works.
- **Detection speed**: Typically within one poll cycle (800ms) or triggered by TYPE_WINDOW_STATE_CHANGED / TYPE_VIEW_TEXT_CHANGED events.
- **Current status**: Fully supported by existing Chrome URL extraction.

#### 2. Google Search result page (`com.google.android.googlequicksearchbox`)
- **Query**: YES — extracted from window title, search bar, or event text.
- **YouTube links**: Present as search results but NOT used for blocking (only the user's search query is checked).
- **Reliability**: HIGH for query detection.
- **Current status**: Fully supported by Google query extraction.

#### 3. YouTube opened from Google Search (embedded Custom Tab in Google app)
- **Package**: Still `com.google.android.googlequicksearchbox` (Google app).
- **URL bar**: SOMETIMES — Google Custom Tabs may expose a simplified URL bar. The `isUrlBarId()` matcher catches `url_bar`, `location_bar`, `omnibox`, `address_bar`, `address_field`, `web_url` resource IDs.
- **Window title**: SOMETIMES — may contain the video title if the Custom Tab has a title bar.
- **Page content**: Available but unreliable for blocking (arbitrary web text).
- **Reliability**: LOW-MEDIUM — URL extraction when the address bar is visible; otherwise the YouTube content is indistinguishable from any other Google-hosted web page.
- **Detection gap**: If Google's Custom Tab hides the URL bar, the video title from the window title is the only available signal, and it may or may not be present.

#### 4. YouTube Shorts
- **In Chrome**: URL `youtube.com/shorts/...` is visible → HIGH reliability.
- **In YouTube app**: No URL bar. The "Shorts" label/chip is visible as accessibility text → MEDIUM reliability via `extractYouTubeSignals()`.
- **Title-based detection**: The Shorts video title is exposed in the accessibility tree → MEDIUM reliability.
- **Detection speed**: Polling-based (800ms interval), or triggered by TYPE_WINDOW_STATE_CHANGED events.

#### 5. Native YouTube app (`com.google.android.youtube`)
- **URL bar**: NO — YouTube app does not expose a URL bar in its accessibility tree.
- **Video title**: YES — visible as a large TextView in the player UI. Extracted by `extractYouTubeTitle()`.
- **Shorts indicator**: YES — "Shorts" label/chip visible when viewing Shorts. Extracted by `extractYouTubeSignals()`.
- **Channel name**: YES — visible but filtered as UI text (may produce false positives if used for blocking).
- **Description/hashtags**: YES — visible in the description section. Hashtags like `#gaming` are extracted.
- **Reliability**: MEDIUM-HIGH for title-based blocking; LOW for domain blocking (no URL).
- **Detection speed**: Polling-based (800ms), or TYPE_WINDOW_STATE_CHANGED when navigating between videos.
- **Blocking mechanism**: Title text is checked against keywords (Title matching is safe for YouTube because the video title IS the content identifier — unlike arbitrary web page text).

#### 6. Embedded WebView / in-app browser (non-Chrome, non-Google apps)
- **URL bar**: RARELY — most in-app browsers (Twitter, Reddit, etc.) do not expose a URL bar to accessibility.
- **Page content**: Available but unreliable for blocking (arbitrary web text).
- **Reliability**: VERY LOW — blocking on page text in arbitrary WebViews would create many false positives.
- **Current status**: Not supported. Documentation should note this as an Android platform limitation.

### Blocking strategy per package

| Package | URL | Query | Title | Signals | Notes |
|---------|-----|-------|-------|---------|-------|
| Chrome | YES | NO | Log only | NO | URL is the only source of truth |
| Google app | YES | YES | Log only | NO | URL + search query |
| YouTube app | NO | YES | YES | YES (Shorts, hashtags) | Title-based blocking (safe because title = content) |
| Generic | YES | YES | Log only | NO | Fallback for new packages |

### Known limitations

1. **YouTube in Google Custom Tab (no URL bar)**: When Google opens a YouTube video in an embedded Custom Tab without a URL bar, the current framework cannot reliably detect YouTube content specifically. The window title may help, but this is not guaranteed.
2. **YouTube Shorts in the YouTube app**: Shorts detection relies on finding the "Shorts" label in the accessibility tree. If YouTube changes its UI layout, this may break.
3. **Video title extraction accuracy**: The `extractYouTubeTitle()` heuristic prefers the longest non-UI TextView text. A very long video description could overshadow a short title.
4. **No instant detection**: YouTube polling runs at 800ms intervals. Between polls, the user may briefly see content before the overlay appears. This is an Android Accessibility Service limitation.
5. **False positives on title**: A video titled "How porn is made (educational)" would be blocked by the "porn" keyword even if the content is actually educational. This is inherent to keyword-based blocking.

## Google dynamic-result investigation

The service already receives `TYPE_WINDOW_CONTENT_CHANGED` and runs a 500 ms Google tree poll. The missed-search symptom came from extraction: after submission, Google can temporarily remove focus and expose the query in a non-editable search chip/container rather than an exact known ID or `EditText`. The extractor now checks semantic search IDs and search-like controls, continues to use window-title query extraction, and retains the last observed query for up to 15 seconds while the same Google foreground session is rebuilding its UI. The focus action remains only as a single last-resort attempt per session.

The continuity cache is not a global blocking cooldown. It only preserves the current search state during a short UI rebuild window; a new Google foreground session clears it and performs a fresh extraction.
