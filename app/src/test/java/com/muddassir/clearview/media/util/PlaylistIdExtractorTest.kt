package com.muddassir.clearview.media.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistIdExtractorTest {

    private val pl = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"

    @Test
    fun `extracts from a plain playlist URL`() {
        assertEquals(pl, extractYouTubePlaylistId("https://www.youtube.com/playlist?list=$pl"))
    }

    @Test
    fun `extracts from a watch URL with a list parameter`() {
        assertEquals(
            pl,
            extractYouTubePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=$pl&index=3")
        )
    }

    @Test
    fun `extracts when the URL carries a share si parameter after list`() {
        // Share links append `&si=…` after list= — must not swallow the id.
        assertEquals(
            pl,
            extractYouTubePlaylistId("https://youtube.com/playlist?list=$pl&si=y-NZvE3N6nU0CLcS")
        )
    }

    @Test
    fun `extracts from music youtube and youtu be forms`() {
        assertEquals(pl, extractYouTubePlaylistId("https://music.youtube.com/playlist?list=$pl"))
        assertEquals(
            pl,
            extractYouTubePlaylistId("https://youtu.be/dQw4w9WgXcQ?list=$pl")
        )
    }

    @Test
    fun `extracts from the playlist path form`() {
        assertEquals(pl, extractYouTubePlaylistId("https://www.youtube.com/playlist/$pl"))
    }

    @Test
    fun `accepts a bare playlist id`() {
        assertEquals(pl, extractYouTubePlaylistId(pl))
    }

    @Test
    fun `accepts short system list ids like watch later`() {
        assertEquals("WL", extractYouTubePlaylistId("WL"))
    }

    @Test
    fun `rejects a bare video id (11 chars)`() {
        assertNull(extractYouTubePlaylistId("dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects a video-only watch URL`() {
        assertNull(extractYouTubePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects non-youtube URLs and garbage`() {
        assertNull(extractYouTubePlaylistId("https://example.com/list=$pl"))
        assertNull(extractYouTubePlaylistId("hello world"))
        assertNull(extractYouTubePlaylistId(""))
        assertNull(extractYouTubePlaylistId("   "))
    }

    @Test
    fun `does not match a list parameter inside a word`() {
        // A plain text word containing "list=" must not extract.
        assertNull(extractYouTubePlaylistId("this is a list=of words"))
    }
}
