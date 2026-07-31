package com.example.url_blocker

import com.example.url_blocker.extractor.GoogleAppUrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAppUrlExtractorTest {

    @Test
    fun parsesDomainFromCloseDescription() {
        assertEquals("youtube.com", GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close, youtube.com"))
        assertEquals("youtube.com", GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close youtube.com"))
        assertEquals("youtube.com", GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close: youtube.com"))
        assertEquals("youtube.com", GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close - youtube.com"))
        assertEquals("m.youtube.com", GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close, m.youtube.com"))
        assertEquals("www.youtube.com", GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close, www.youtube.com"))
    }

    @Test
    fun closeDescriptionWithoutDomainIsIgnored() {
        assertNull(GoogleAppUrlExtractor.parseDomainFromCloseDescription("Close"))
        assertNull(GoogleAppUrlExtractor.parseDomainFromCloseDescription("Remove"))
        assertNull(GoogleAppUrlExtractor.parseDomainFromCloseDescription("Not interested"))
        assertNull(GoogleAppUrlExtractor.parseDomainFromCloseDescription(""))
    }

    @Test
    fun bareDomainDetection() {
        assertTrue(GoogleAppUrlExtractor.looksLikeBareDomain("youtube.com"))
        assertTrue(GoogleAppUrlExtractor.looksLikeBareDomain("www.youtube.com"))
        assertTrue(GoogleAppUrlExtractor.looksLikeBareDomain("testblocked.com"))
        assertFalse(GoogleAppUrlExtractor.looksLikeBareDomain("About 1,240,000 results"))
        assertFalse(GoogleAppUrlExtractor.looksLikeBareDomain("testblocked"))
        assertFalse(GoogleAppUrlExtractor.looksLikeBareDomain("https://youtube.com"))
        assertFalse(GoogleAppUrlExtractor.looksLikeBareDomain("1.2.3.4"))
        assertFalse(GoogleAppUrlExtractor.looksLikeBareDomain("not a url"))
    }

    @Test
    fun fullUrlDetection() {
        assertTrue(
            GoogleAppUrlExtractor.looksLikeFullUrl(
                "https://www.youtube.com/results?search_query=testblocked"
            )
        )
        assertTrue(GoogleAppUrlExtractor.looksLikeFullUrl("http://example.com"))
        assertTrue(GoogleAppUrlExtractor.looksLikeFullUrl("www.example.com"))
        assertFalse(GoogleAppUrlExtractor.looksLikeFullUrl("youtube.com"))
    }

    @Test
    fun domainUrlConversion() {
        assertEquals("https://youtube.com/", GoogleAppUrlExtractor.toDomainUrl("youtube.com"))
        assertEquals("https://www.youtube.com/", GoogleAppUrlExtractor.toDomainUrl("www.youtube.com"))
    }

    @Test
    fun domainFromUrl() {
        assertEquals(
            "youtube.com",
            GoogleAppUrlExtractor.extractDomainFromUrl("https://www.youtube.com/watch?v=abc")
        )
        assertEquals(
            "youtube.com",
            GoogleAppUrlExtractor.extractDomainFromUrl("https://youtube.com/results?search_query=testblocked")
        )
        assertEquals("testblocked.com", GoogleAppUrlExtractor.extractDomainFromUrl("https://testblocked.com"))
        assertEquals("testblocked.com", GoogleAppUrlExtractor.extractDomainFromUrl("testblocked.com"))
        assertNull(GoogleAppUrlExtractor.extractDomainFromUrl("not a url"))
    }

    @Test
    fun googleSearchTitleDetection() {
        assertTrue(GoogleAppUrlExtractor.isGoogleSearchTitle("testblocked - Google Search"))
        assertTrue(GoogleAppUrlExtractor.isGoogleSearchTitle("Android development - Google"))
        assertFalse(GoogleAppUrlExtractor.isGoogleSearchTitle("YouTube"))
        assertFalse(GoogleAppUrlExtractor.isGoogleSearchTitle("testblocked - YouTube"))
        assertFalse(GoogleAppUrlExtractor.isGoogleSearchTitle(null))
    }

    // ── Decision logic (the anti-false-positive rule) ───────────────────

    @Test
    fun addressBarUrlAloneIsSufficient() {
        assertEquals(
            "https://www.youtube.com/" to true,
            GoogleAppUrlExtractor.decide("https://www.youtube.com/", null, null)
        )
    }

    @Test
    fun bareHttpTextWithoutCloseDomainIsIgnored() {
        // Search-results page can expose arbitrary http text — must NOT trigger.
        assertEquals(
            null to false,
            GoogleAppUrlExtractor.decide(null, "https://www.youtube.com/", null)
        )
    }

    @Test
    fun httpTextAcceptedWhenCloseDomainProvesInAppBrowser() {
        assertEquals(
            "https://www.youtube.com/" to true,
            GoogleAppUrlExtractor.decide(null, "https://www.youtube.com/", "youtube.com")
        )
    }

    @Test
    fun closeDomainAloneActivatesInAppBrowser() {
        assertEquals(
            null to true,
            GoogleAppUrlExtractor.decide(null, null, "youtube.com")
        )
    }

    @Test
    fun noSignalsMeansInactive() {
        assertEquals(null to false, GoogleAppUrlExtractor.decide(null, null, null))
    }
}
