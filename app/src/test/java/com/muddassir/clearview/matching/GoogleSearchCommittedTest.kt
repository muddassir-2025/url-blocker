package com.muddassir.clearview.matching

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the Google-app gate that prevents blocking while the user is
 * still TYPING in the search box (the "blocks as soon as I type" bug).
 *
 * The Google app exposes the live search-box text as the query while typing
 * (SEARCH_BAR / EDITABLE_FIELD / ACCESSIBILITY_EVENT sources). A query must
 * only be checked once the search is actually SUBMITTED — signalled by the
 * results page: querySource == WINDOW_TITLE, visible tab chips (googleTab),
 * a parseable "... - Google Search" window title, or a google.com/search URL.
 */
class GoogleSearchCommittedTest {

    private fun snapshot(
        query: String? = "porn",
        querySource: QuerySource = QuerySource.SEARCH_BAR,
        googleTab: String? = null,
        title: String? = null,
        url: String? = null
    ) = ContentSnapshot(
        packageName = "com.google.android.googlequicksearchbox",
        url = url,
        query = query,
        title = title,
        querySource = querySource
    ).copy(googleTab = googleTab)

    // ── While typing: NOT committed, must not block ────────────────

    @Test
    fun `typing in search box is never a committed search`() {
        // Typing "ass..." (start of "assignment") on the Google home screen:
        // search-box source, no chips, plain "Google" title, no URL.
        val s = snapshot(query = "ass", querySource = QuerySource.SEARCH_BAR, title = "Google")
        assertFalse(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `edit-text fallback extraction while typing is not committed`() {
        val s = snapshot(query = "hot", querySource = QuerySource.EDITABLE_FIELD, title = "Google")
        assertFalse(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `raw accessibility event text while typing is not committed`() {
        val s = snapshot(query = "cock", querySource = QuerySource.ACCESSIBILITY_EVENT, title = "Google")
        assertFalse(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `no source and no results page is not committed`() {
        val s = snapshot(query = "sex", querySource = QuerySource.NONE, title = "Google")
        assertFalse(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    // ── After submission: committed, must block ────────────────────

    @Test
    fun `window-title query on the results page is committed`() {
        val s = snapshot(query = "porn", querySource = QuerySource.WINDOW_TITLE, title = "porn - Google Search")
        assertTrue(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `visible tab chips on the results page are committed`() {
        // Search box still holds the query (SEARCH_BAR source) but the
        // All/Images/Videos chips are in the tree — the search was submitted.
        val s = snapshot(query = "porn", querySource = QuerySource.SEARCH_BAR, googleTab = "All", title = "porn - Google Search")
        assertTrue(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `parseable results window title is committed even without chips`() {
        // Chip scan missed (defense in depth): the "... - Google Search"
        // window title alone proves the results page is showing.
        val s = snapshot(query = "porn", querySource = QuerySource.SEARCH_BAR, title = "porn - Google Search")
        assertTrue(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `google search url is committed`() {
        val s = snapshot(query = "porn", url = "https://www.google.com/search?q=porn&source=lnms")
        assertTrue(KeywordMatcher.isGoogleSearchCommitted(s))
    }

    @Test
    fun `innocent word on the results page is still committed`() {
        // The gate is about SUBMISSION, not content: an innocent submitted
        // query must still pass the gate so the keyword check decides.
        val s = snapshot(query = "class assignment", querySource = QuerySource.WINDOW_TITLE, title = "class assignment - Google Search")
        assertTrue(KeywordMatcher.isGoogleSearchCommitted(s))
    }
}
