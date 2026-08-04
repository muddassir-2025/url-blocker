package com.muddassir.clearview.media.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoIdExtractorTest {

    @Test
    fun `extracts id from watch urls`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun `extracts id from youtu dot be and shorts`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ")
        )
    }

    @Test
    fun `extracts bare eleven character ids`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(extractYouTubeVideoId(""))
        assertNull(extractYouTubeVideoId("not a url"))
        assertNull(extractYouTubeVideoId("https://example.com/foo"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/channel/UCabc"))
        assertNull(extractYouTubeVideoId("short"))
    }
}
