package com.example.url_blocker

import com.example.url_blocker.extractor.ContentExtractor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncognitoDetectionTest {

    // ── Strong text signals (only appear on Chrome's incognito NTP) ──

    @Test
    fun incognitoNewTabHeadingIsDetected() {
        assertTrue(ContentExtractor.matchesStrongIncognitoText("You've gone incognito"))
        assertTrue(ContentExtractor.matchesStrongIncognitoText("You have gone incognito"))
        assertTrue(ContentExtractor.matchesStrongIncognitoText("Now you can browse privately"))
        assertTrue(ContentExtractor.matchesStrongIncognitoText("You've gone incognito. Now you can browse privately."))
    }

    @Test
    fun ordinaryPageTextIsNotDetected() {
        // Articles merely mentioning incognito must NOT block (Chrome page text
        // is never used to block — incognito detection respects the same rule).
        assertFalse(ContentExtractor.matchesStrongIncognitoText("What is incognito mode and how does it work?"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("Incognito mode explained"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("Incognito tabs: how to use them"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("Wikipedia — Incognito"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("The history of incognito browsing"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("Search results"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("About 1,240,000 results"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("New Tab"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText(""))
    }

    @Test
    fun normalNewTabPageFooterIsNotDetected() {
        // Regression test for a critical false positive: Chrome's normal
        // (non-incognito) new-tab page shows this footer as native, non-WebView
        // text. It contains the standalone word "incognito" but must NEVER
        // trigger a block — this is exactly what happened before the
        // standalone-word text signal was removed.
        assertFalse(ContentExtractor.matchesStrongIncognitoText("You can also browse privately with Incognito mode"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("You can also browse privately with incognito mode"))
        assertFalse(ContentExtractor.matchesStrongIncognitoText("Open an Incognito window"))
    }

    // ── Active-state chrome signals (tab counter, close-incognito, chip) ──

    @Test
    fun activeIncognitoStateTextIsDetected() {
        // These describe an EXISTING incognito session → must match.
        assertTrue(ContentExtractor.isActiveIncognitoStateText("Incognito, 2 tabs"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("Incognito, 2 tabs open"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("2 Incognito tabs"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("1 incognito tab"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("Incognito tabs"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("Incognito window"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("Close Incognito tabs"))
        assertTrue(ContentExtractor.isActiveIncognitoStateText("Close all Incognito tabs"))
    }

    @Test
    fun normalChromeOfferTextIsNeverDetectedAsActiveState() {
        // Chrome's NORMAL new-tab page / menus expose the word "incognito" as
        // OFFERS to start incognito. None of these may ever trigger a block.
        assertFalse(ContentExtractor.isActiveIncognitoStateText("You can also browse privately with Incognito mode"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("Incognito"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("Open an Incognito window"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("Open a new Incognito tab"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("New Incognito tab"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("Switch to Incognito"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("What is incognito mode?"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("Incognito mode explained"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText(""))
    }

    // ── Chrome incognito UI chrome (view resource ids / class names) ──

    @Test
    fun incognitoChromeViewIdsAndClassesAreDetected() {
        // Nodes whose resource id / class name marks them as Chrome's OWN
        // incognito UI — these exist only while an incognito session is active,
        // so they detect the state even when the NTP heading text isn't exposed.
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_new_tab_page_title", "android.widget.TextView"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("", "org.chromium.chrome.browser.incognito.IncognitoNewTabPageView"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_tab_switcher", "android.widget.FrameLayout"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_close_all_button", ""))
    }

    @Test
    fun normalChromeViewIdsAndClassesAreNeverDetected() {
        // NORMAL Chrome views — the toolbar, NTP, URL bar, and the tab-switcher
        // mode selector that OFFERS incognito — must never count as an active
        // incognito session.
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/tab_switcher_button", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/url_bar", "android.widget.EditText"))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("", "android.widget.FrameLayout"))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier(null, null))
        // The tab-switcher mode selector / toggle exists in NORMAL Chrome too
        // (it is the offer to switch to incognito) — excluded explicitly in
        // BOTH underscore form (resource ids) and CamelCase form (class names).
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/tab_model_selector", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_tab_model_selector", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("", "org.chromium.chrome.browser.tab_ui.IncognitoToggleTabLayout"))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("", "org.chromium.chrome.browser.tabmodel.IncognitoTabModelSelector"))
    }

    @Test
    fun normalNtpIncognitoShortcutTileIsNeverDetected() {
        // Chrome's NORMAL new-tab page shows an "Incognito" shortcut tile whose
        // resource id mentions "incognito" but is an OFFER, not an active
        // session — opening normal Chrome must never block (the reported false
        // positive when simply opening Chrome).
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_shortcut", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_tile", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_shortcut_tile", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_tile_button", ""))
    }

    @Test
    fun realIncognitoIdsStillDetectedWithShortcutTileMarkers() {
        // The added NTP offer markers (shortcut/tile) must not suppress genuine
        // incognito-only ids.
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_new_tab_page_title", "android.widget.TextView"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_tab_switcher", "android.widget.FrameLayout"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_close_all_button", ""))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("", "org.chromium.chrome.browser.incognito.IncognitoNewTabPageView"))
    }

    @Test
    fun overflowMenuIncognitoItemIsNeverDetected() {
        // Chrome's ⋮ overflow menu exposes "New Incognito tab"/"New Incognito
        // window" menu items whose resource ids contain both "incognito" and
        // "menu". These are OFFERS, not an active session — opening the ⋮ menu
        // must never trigger a block (the user's reported false positive). The
        // protection lives in the precise signal rules (the "menu" offer marker
        // and the startsWith "new incognito" rejection), NOT in a tree-wide
        // menu state gate — such a gate matched Chrome's always-present
        // AppMenuButton toolbar class and disabled ALL incognito detection.
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/menu_new_incognito_tab", "android.widget.ListMenuItemView"))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/menu_new_incognito_window", "android.widget.ListMenuItemView"))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/new_incognito_tab_menu_button", ""))
        // And the menu item TEXT is rejected by the active-state discriminator.
        assertFalse(ContentExtractor.isActiveIncognitoStateText("New Incognito tab"))
        assertFalse(ContentExtractor.isActiveIncognitoStateText("New Incognito window"))
        // Defense in depth: menu ids that omit the "menu" token are still offers.
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/new_incognito_tab", ""))
        assertFalse(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/new_incognito_window", ""))
    }

    @Test
    fun realIncognitoChromeIdsStillDetectedWithMenuMarker() {
        // Adding the "menu" offer marker must not break detection of genuine
        // incognito-only UI chrome (which never has "menu" in its id/class).
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_new_tab_page_title", "android.widget.TextView"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_tab_switcher", "android.widget.FrameLayout"))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("com.android.chrome:id/incognito_close_all_button", ""))
        assertTrue(ContentExtractor.isIncognitoChromeIdentifier("", "org.chromium.chrome.browser.incognito.IncognitoNewTabPageView"))
    }
}
