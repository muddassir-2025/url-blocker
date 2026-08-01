package com.example.url_blocker

import com.example.url_blocker.extractor.ContentExtractor
import com.example.url_blocker.extractor.GoogleSignalParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleContentExtractorTest {

    @Test
    fun parsesGoogleSearchTitle() {
        assertEquals(
            "blockedkeyword",
            GoogleSignalParser.queryFromWindowTitle("blockedkeyword - Google Search")
        )
    }

    @Test
    fun parsesChromeGoogleSearchTitleWithChromePrefix() {
        // Chrome's accessibility window title prepends "Chrome: " — this is the
        // exact format the user's device produced on a Google Images search.
        // The parsed result still contains the query keyword, so matching works.
        val parsed = GoogleSignalParser.queryFromWindowTitle("Chrome: women - Google Search")
        assertEquals("Chrome: women", parsed)
    }

    @Test
    fun doesNotTreatArbitraryPageTitleAsGoogleQuery() {
        assertNull(GoogleSignalParser.queryFromWindowTitle("Blocked result title - YouTube"))
    }

    // ── Query from URL (scheme-less Chrome URLs must not crash) ─────

    @Test
    fun extractsQueryFromSchemeLessUrl() {
        // Chrome exposes the address-bar URL WITHOUT a scheme. android.net.Uri
        // would treat "google.com" as an opaque scheme and throw on
        // getQueryParameter — this must return the query instead of crashing
        // (the user's observed Images-tab crash).
        assertEquals(
            "women",
            GoogleSignalParser.queryFromUrl(
                "google.com/search?client=ms-android-motorola-rvo3&q=women&source=lnms&udm=2"
            )
        )
    }

    @Test
    fun extractsQueryFromSchemeLessImagesUrlWithoutQParamIsNull() {
        // The Images-tab URL from the user's logs has NO q= parameter — the
        // query only lives in the window title, so URL extraction is null
        // (the title fallback handles the keyword).
        assertNull(
            GoogleSignalParser.queryFromUrl(
                "google.com/search?client=ms-android-motorola-rvo3&hs=y01&sca_esv=abc&udm=2&fbs=xyz"
            )
        )
    }

    @Test
    fun extractsQueryFromUrlWithScheme() {
        assertEquals(
            "test query",
            GoogleSignalParser.queryFromUrl("https://www.google.com/search?q=test+query&tbm=isch")
        )
    }

    @Test
    fun queryFromUrlHandlesMalformedInputs() {
        assertNull(GoogleSignalParser.queryFromUrl(null))
        assertNull(GoogleSignalParser.queryFromUrl(""))
        assertNull(GoogleSignalParser.queryFromUrl("not a url at all"))
    }

    @Test
    fun acceptsExplicitEmbeddedUrlFromWindowTitle() {
        assertEquals(
            true,
            GoogleSignalParser.isExplicitUrl("https://www.youtube.com/shorts/example")
        )
    }

    // ── YouTube videos watched inside Chrome (title blocking) ───────

    @Test
    fun extractsVideoTitleFromChromeWindowTitle() {
        // The exact format from the user's log: Chrome prefixes "Chrome: " and
        // YouTube appends " - YouTube". The parsed title is what gets checked
        // against the keyword set (the video title IS the content identifier).
        assertEquals(
            "American Doctor SHOCKED By Foreign Sex Ed Videos",
            ContentExtractor.youtubeTitleFromChromeWindowTitle(
                "Chrome: American Doctor SHOCKED By Foreign Sex Ed Videos - YouTube"
            )
        )
    }

    @Test
    fun youtubeTitleFromChromeWindowTitleHandlesPlainFormat() {
        // No "Chrome: " prefix (e.g. custom-tab style titles) still parses.
        assertEquals(
            "bra video",
            ContentExtractor.youtubeTitleFromChromeWindowTitle("bra video - YouTube")
        )
    }

    @Test
    fun youtubeTitleFromChromeWindowTitleRejectsNonVideoScreens() {
        // YouTube UI screens (home, Shorts, ...) are not video titles and must
        // never block.
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: YouTube"))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: Shorts"))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: Subscriptions - YouTube"))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: Home - YouTube"))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: Search - YouTube"))
    }

    @Test
    fun youtubeTitleFromChromeWindowTitleRejectsNonYoutubeTitles() {
        // A Google search window title in Chrome must NOT be treated as a
        // YouTube video title (the " - Google Search" suffix is not YouTube).
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: women - Google Search"))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle("Chrome: Home"))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle(""))
        assertNull(ContentExtractor.youtubeTitleFromChromeWindowTitle(null))
    }

    @Test
    fun isYouTubeDomainDetection() {
        // YouTube hosts — including the mobile host from the user's log.
        assertTrue(ContentExtractor.isYouTubeDomain("m.youtube.com/watch?v=Y5kJxCdZJEM"))
        assertTrue(ContentExtractor.isYouTubeDomain("m.youtube.com"))
        assertTrue(ContentExtractor.isYouTubeDomain("https://www.youtube.com/watch?v=abc"))
        assertTrue(ContentExtractor.isYouTubeDomain("https://music.youtube.com/"))
        // Non-YouTube domains must never match.
        assertFalse(ContentExtractor.isYouTubeDomain("https://example.com"))
        assertFalse(ContentExtractor.isYouTubeDomain("google.com/search?q=women"))
        assertFalse(ContentExtractor.isYouTubeDomain(""))
        assertFalse(ContentExtractor.isYouTubeDomain(null))
    }

    // ── Google tab detection (tab-restricted keywords) ──────────────

    @Test
    fun detectsImagesTabFromUrl() {
        assertEquals(
            "Images",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&tbm=isch")
        )
    }

    @Test
    fun detectsImagesTabFromEncodedUrl() {
        assertEquals(
            "Images",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&tbm%3Disch")
        )
    }

    @Test
    fun detectsVideosTabFromUrl() {
        assertEquals(
            "Videos",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&tbm=vid")
        )
    }

    @Test
    fun detectsNewsAndShoppingTabs() {
        assertEquals(
            "News",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&tbm=nws")
        )
        assertEquals(
            "Shopping",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&tbm=shop")
        )
    }

    @Test
    fun allTabAndPlainUrlsReturnNull() {
        assertNull(ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=woman"))
        assertNull(ContentExtractor.googleTabFromUrl("https://www.google.com/"))
        assertNull(ContentExtractor.googleTabFromUrl(null))
        assertNull(ContentExtractor.googleTabFromUrl(""))
    }

    // ── udm layout codes (Chrome mobile Google search) ──────────────

    @Test
    fun detectsImagesTabFromUdm2() {
        assertEquals(
            "Images",
            ContentExtractor.googleTabFromUrl(
                "google.com/search?client=ms-android-motorola-rvo3&sca_esv=1&udm=2&fbs=xyz&q=women"
            )
        )
    }

    @Test
    fun detectsImagesTabFromExactUserLogUrl() {
        // The exact scheme-less Images-tab URL from the user's logcat — tab
        // detection must work on the raw Chrome address-bar format (no scheme).
        assertEquals(
            "Images",
            ContentExtractor.googleTabFromUrl(
                "google.com/search?client=ms-android-motorola-rvo3&hs=y01&sca_esv=a64f8b6be24e5a42&udm=2&fbs=ABfTbFWbDLQDVV53GUOXUHWpvaI-p8CNBIKIJu0tJyF5d"
            )
        )
        // And the same URL on the All tab must NOT be an Images/Videos tab.
        assertNull(
            ContentExtractor.googleTabFromUrl(
                "google.com/search?client=ms-android-motorola-rvo3&hs=xfMq&sca_esv=1&q=women&source=lnms"
            )
        )
    }

    @Test
    fun detectsImagesTabFromEncodedUdm2() {
        assertEquals(
            "Images",
            ContentExtractor.googleTabFromUrl(
                "https://www.google.com/search?q=test&udm%3D2%26fbs=xyz"
            )
        )
    }

    @Test
    fun detectsVideosTabFromUdm7And39() {
        assertEquals(
            "Videos",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&udm=7")
        )
        assertEquals(
            "Videos",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&udm=39")
        )
    }

    @Test
    fun udm28ShoppingIsNotMistakenForImages() {
        // udm=28 must NOT be treated as Images (udm=2) — value boundaries matter.
        assertEquals(
            "Shopping",
            ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&udm=28")
        )
        assertNull(ContentExtractor.googleTabFromUrl("https://www.google.com/search?q=test&udm=14"))
    }

    // ── Google tab chip labels (accessibility-tree detection) ─────────

    @Test
    fun mapsTabChipLabels() {
        assertEquals("Images", ContentExtractor.googleTabFromLabel("Images"))
        assertEquals("Images", ContentExtractor.googleTabFromLabel("images"))
        assertEquals("Videos", ContentExtractor.googleTabFromLabel("Videos"))
        assertEquals("All", ContentExtractor.googleTabFromLabel("All"))
        assertEquals("News", ContentExtractor.googleTabFromLabel("News"))
        assertEquals("Shopping", ContentExtractor.googleTabFromLabel("Shopping"))
    }

    @Test
    fun nonTabLabelsReturnNull() {
        assertNull(ContentExtractor.googleTabFromLabel(null))
        assertNull(ContentExtractor.googleTabFromLabel(""))
        assertNull(ContentExtractor.googleTabFromLabel("  " ))
        assertNull(ContentExtractor.googleTabFromLabel("women"))
        assertNull(ContentExtractor.googleTabFromLabel("About 1,240,000 results"))
    }
}
