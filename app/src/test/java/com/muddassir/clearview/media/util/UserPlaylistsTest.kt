package com.muddassir.clearview.media.util

import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.model.UserPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPlaylistsTest {

    private fun video(id: String) = MediaVideo(
        videoId = id,
        title = "Video $id",
        channelId = "c",
        channelName = "C",
        publishedAtEpochMillis = 0L,
        thumbnailUrl = "",
        viewCount = 0L,
        isShort = false
    )

    private fun playlist(id: String, name: String, vararg ids: String) = UserPlaylist(
        id = id,
        name = name,
        videos = ids.map(::video),
        createdAtEpochMillis = 1_000L
    )

    @Test
    fun `encode decode round-trips playlists and their video order`() {
        val list = listOf(
            playlist("p1", "Favorites", "a", "b", "c"),
            playlist("p2", "Empty", )
        )
        val decoded = UserPlaylists.decode(UserPlaylists.encode(list))
        assertEquals(list, decoded)
        assertEquals(listOf("a", "b", "c"), decoded[0].videos.map { it.videoId })
        assertTrue(decoded[1].videos.isEmpty())
    }

    @Test
    fun `decode handles blank and corrupt input`() {
        assertTrue(UserPlaylists.decode(null).isEmpty())
        assertTrue(UserPlaylists.decode("").isEmpty())
        assertTrue(UserPlaylists.decode("not json").isEmpty())
        assertTrue(UserPlaylists.decode("[{\"name\":\"no id\"}]").isEmpty())
    }

    @Test
    fun `rename updates only the matching playlist`() {
        val list = listOf(playlist("p1", "Old"), playlist("p2", "Keep"))
        val renamed = UserPlaylists.withRenamed(list, "p1", "  New name  ")
        assertEquals("New name", renamed[0].name)
        assertEquals("Keep", renamed[1].name)
    }

    @Test
    fun `rename rejects blank names`() {
        val list = listOf(playlist("p1", "Old"))
        assertEquals(list, UserPlaylists.withRenamed(list, "p1", "   "))
    }

    @Test
    fun `remove deletes only the matching playlist`() {
        val list = listOf(playlist("p1", "A"), playlist("p2", "B"))
        val remaining = UserPlaylists.withRemoved(list, "p1")
        assertEquals(listOf("p2"), remaining.map { it.id })
    }

    @Test
    fun `adding videos appends and dedupes`() {
        val list = listOf(playlist("p1", "A", "a", "b"))
        val updated = UserPlaylists.withVideosAdded(list, "p1", listOf(video("b"), video("c"), video("d")))
        assertEquals(listOf("a", "b", "c", "d"), updated[0].videos.map { it.videoId })
    }

    @Test
    fun `adding to a different playlist is a no-op`() {
        val list = listOf(playlist("p1", "A", "a"))
        assertEquals(list, UserPlaylists.withVideosAdded(list, "nope", listOf(video("x"))))
    }

    @Test
    fun `removing a video keeps the rest in order`() {
        val list = listOf(playlist("p1", "A", "a", "b", "c"))
        val updated = UserPlaylists.withVideoRemoved(list, "p1", "b")
        assertEquals(listOf("a", "c"), updated[0].videos.map { it.videoId })
    }

    @Test
    fun `moving a video reorders within the playlist`() {
        val list = listOf(playlist("p1", "A", "a", "b", "c", "d"))
        // Move "b" (index 1) down to index 3 → a, c, d, b
        val down = UserPlaylists.withVideoMoved(list, "p1", 1, 3)
        assertEquals(listOf("a", "c", "d", "b"), down[0].videos.map { it.videoId })
        // "d" is now at index 2 in [a, c, d, b]; move it up to index 0 → d, a, c, b
        val up = UserPlaylists.withVideoMoved(down, "p1", 2, 0)
        assertEquals(listOf("d", "a", "c", "b"), up[0].videos.map { it.videoId })
    }

    @Test
    fun `moving with out-of-range or identical indices is a no-op`() {
        val list = listOf(playlist("p1", "A", "a", "b"))
        assertEquals(list, UserPlaylists.withVideoMoved(list, "p1", 0, 0))
        assertEquals(list, UserPlaylists.withVideoMoved(list, "p1", -1, 1))
        assertEquals(list, UserPlaylists.withVideoMoved(list, "p1", 0, 99))
        assertEquals(list, UserPlaylists.withVideoMoved(list, "nope", 0, 1))
    }

    @Test
    fun `empty video lists encode and decode`() {
        val decoded = UserPlaylists.decode(UserPlaylists.encode(emptyList()))
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `duplicates never occur after repeated adds`() {
        var list = listOf(playlist("p1", "A", "a"))
        repeat(3) {
            list = UserPlaylists.withVideosAdded(list, "p1", listOf(video("a"), video("b")))
        }
        val ids = list[0].videos.map { it.videoId }
        assertEquals(2, ids.size)
        assertFalse(ids.filter { it == "a" }.size > 1)
    }

    // ── Offline-audio entries (isOfflineAudio): a playlist can hold BOTH a
    // video and its downloaded audio side by side — dedup and removal are
    // keyed on (videoId, audio flag), never on the id alone.

    private fun audio(id: String) = video(id).copy(isOfflineAudio = true)

    @Test
    fun `adding the same video and its audio keeps both entries`() {
        val list = listOf(playlist("p1", "A", "a"))
        val updated = UserPlaylists.withVideosAdded(list, "p1", listOf(audio("a"), audio("b")))
        // "a" (video) + "a" (audio) both present; "b" (audio) appended.
        assertEquals(
            listOf("a" to false, "a" to true, "b" to true),
            updated[0].videos.map { it.videoId to it.isOfflineAudio }
        )
    }

    @Test
    fun `re-adding an audio entry already present is deduped`() {
        val list = listOf(playlist("p1", "A", "a"))
        val withAudio = UserPlaylists.withVideosAdded(list, "p1", listOf(audio("a")))
        val again = UserPlaylists.withVideosAdded(withAudio, "p1", listOf(audio("a"), audio("b")))
        assertEquals(3, again[0].videos.size)
    }

    @Test
    fun `removing the video leaves its audio entry behind`() {
        val list = listOf(playlist("p1", "A", "a"))
        val withAudio = UserPlaylists.withVideosAdded(list, "p1", listOf(audio("a")))
        val withoutVideo = UserPlaylists.withVideoRemoved(withAudio, "p1", "a", isOfflineAudio = false)
        assertEquals(listOf("a" to true), withoutVideo[0].videos.map { it.videoId to it.isOfflineAudio })
    }

    @Test
    fun `removing the audio leaves its video entry behind`() {
        val list = listOf(playlist("p1", "A", "a"))
        val withAudio = UserPlaylists.withVideosAdded(list, "p1", listOf(audio("a")))
        val withoutAudio = UserPlaylists.withVideoRemoved(withAudio, "p1", "a", isOfflineAudio = true)
        assertEquals(listOf("a" to false), withoutAudio[0].videos.map { it.videoId to it.isOfflineAudio })
    }

    @Test
    fun `plain remove still removes a video entry (backward compatible)`() {
        val list = listOf(playlist("p1", "A", "a", "b"))
        val updated = UserPlaylists.withVideoRemoved(list, "p1", "a")
        assertEquals(listOf("b"), updated[0].videos.map { it.videoId })
    }

    @Test
    fun `audio entries survive encode-decode round trip`() {
        val list = listOf(playlist("p1", "Mix", "a"))
        val withAudio = UserPlaylists.withVideosAdded(list, "p1", listOf(audio("a")))
        val decoded = UserPlaylists.decode(UserPlaylists.encode(withAudio))
        assertEquals(withAudio, decoded)
        assertEquals(
            listOf("a" to false, "a" to true),
            decoded[0].videos.map { it.videoId to it.isOfflineAudio }
        )
    }
}
