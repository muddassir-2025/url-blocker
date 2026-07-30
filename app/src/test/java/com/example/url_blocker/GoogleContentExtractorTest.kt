package com.example.url_blocker

import com.example.url_blocker.extractor.GoogleSignalParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun doesNotTreatArbitraryPageTitleAsGoogleQuery() {
        assertNull(GoogleSignalParser.queryFromWindowTitle("Blocked result title - YouTube"))
    }

    @Test
    fun acceptsExplicitEmbeddedUrlFromWindowTitle() {
        assertEquals(
            true,
            GoogleSignalParser.isExplicitUrl("https://www.youtube.com/shorts/example")
        )
    }
}
